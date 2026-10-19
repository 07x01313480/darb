// Builds www/ — the web files bundled inside the Android APK.
// Copies the app as-is, but lowers modern JavaScript syntax (?., ??, etc.) so it also runs on
// older Android WebViews such as those in some car head units. The website itself is served
// straight from the repo root and doesn't use this.
import { transform } from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const OUT = path.join(ROOT, 'www');
const TARGET = 'chrome60'; // Capacitor's minimum Android WebView

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const copy = rel => {
  const src = path.join(ROOT, rel), dst = path.join(OUT, rel);
  fs.cpSync(src, dst, { recursive: true });
};
['manifest.webmanifest', 'icons', 'vendor'].forEach(copy);

// Capacitor's JS core. Android's native bridge provides Capacitor.isNativePlatform/nativePromise but NOT
// Capacitor.registerPlugin(), which index.html uses to reach the native plugins (voice, browser, app, keep-awake).
fs.copyFileSync(path.join(ROOT, 'node_modules/@capacitor/core/dist/capacitor.js'), path.join(OUT, 'capacitor.js'));

const lower = async (code, label) => {
  try { return (await transform(code, { loader: 'js', target: TARGET, charset: 'utf8' })).code; }
  catch (e) { console.error(`Could not transform ${label}`); throw e; }
};

// Inline app script in index.html
let html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
const m = html.match(/<script>([\s\S]*?)<\/script>/);
if (!m) throw new Error('inline <script> not found in index.html');
const js = await lower(m[1], 'index.html inline script');
html = html.replace(m[0], () => `<script>\n${js}</script>`);
const libTag = '<script src="vendor/leaflet/leaflet.js"></script>';
if (!html.includes(libTag)) throw new Error('leaflet script tag not found in index.html');
html = html.replace(libTag, `<script src="capacitor.js"></script>\n${libTag}`);
fs.writeFileSync(path.join(OUT, 'index.html'), html);

// Bundled libraries
// Never vendor/maplibre/*: those are ES modules that rely on import.meta.
for (const rel of ['capacitor.js', 'vendor/supabase/supabase.js', 'vendor/leaflet/leaflet.js']) {
  const p = path.join(OUT, rel);
  fs.writeFileSync(p, await lower(fs.readFileSync(p, 'utf8'), rel));
}

console.log(`www/ built for ${TARGET}`);
