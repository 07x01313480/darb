// Darb service worker: keeps the app shell and its bundled libraries (vendor/) available offline.
// Map tiles, search, routing, Overpass and Supabase requests are not intercepted; they always go to the network.
// Bump VERSION whenever this file or the precache list changes.
const VERSION = 'darb-v3';

const SHELL = [
  './',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
  './vendor/leaflet/leaflet.css',
  './vendor/leaflet/leaflet.js',
  './vendor/tabler/tabler-icons.min.css',
  './vendor/tabler/fonts/tabler-icons.woff2',
  './vendor/supabase/supabase.js',
  './vendor/maplibre/maplibre-gl.css',
  './vendor/maplibre/maplibre-gl.mjs',
  // MapLibre requests its worker with ?v=<version>; precache that exact URL.
  './vendor/maplibre/maplibre-gl-worker.mjs?v=6.15.0',
  './vendor/maplibre/mapbox-gl-rtl-text.js'
];

self.addEventListener('install', e => {
  e.waitUntil((async () => {
    const c = await caches.open(VERSION);
    await c.addAll(SHELL);
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', e => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k !== VERSION) await caches.delete(k);
    await self.clients.claim();
  })());
});

// Serve from cache right away, refresh the cached copy in the background.
async function staleWhileRevalidate(e) {
  const c = await caches.open(VERSION);
  const hit = await c.match(e.request);
  const net = fetch(e.request).then(r => {
    if (r && (r.ok || r.type === 'opaque')) c.put(e.request, r.clone());
    return r;
  }).catch(() => null);
  if (hit) { e.waitUntil(net); return hit; }
  return (await net) || Response.error();
}

// Pages: always try the network first so new versions show up, fall back to the cached shell offline.
async function networkFirstPage(e) {
  const c = await caches.open(VERSION);
  try {
    const r = await fetch(e.request);
    if (r && r.ok) c.put('./', r.clone());
    return r;
  } catch (err) {
    return (await c.match('./')) || Response.error();
  }
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (req.mode === 'navigate' && url.origin === self.location.origin) {
    e.respondWith(networkFirstPage(e));
  } else if (url.origin === self.location.origin) {
    e.respondWith(staleWhileRevalidate(e));
  }
});
