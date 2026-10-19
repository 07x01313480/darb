# Darb — development notes

Web app: all HTML, CSS and JS is in `index.html` (no build step for the website), plus PWA files (`manifest.webmanifest`, `sw.js`, `icons/`) and bundled libraries in `vendor/` (Leaflet 1.9.4, Tabler Icons webfont 3.49 — woff2 only, supabase-js 2.118 UMD). Deployed by GitHub Pages from the `main` branch root.

The same `index.html` is packaged as an Android APK with Capacitor 8 (`android/`, `capacitor.config.json`, `scripts/build-www.mjs`) and an optional Supabase backend (`supabase/schema.sql`). User-facing setup steps are in `SETUP.md`.

## Code map (index.html)

- **Storage** — `LS` wraps `localStorage` with the `darb.` prefix. `S` = settings (`lang`, `voice`, `night`), `P` = places (`home`, `work`, `favs`), plus `recents` and `reports`.
- **i18n** — `I18N.en` / `I18N.ar`, read through `t(key, ...args)`. Some entries are functions. Every user-facing string must exist in both languages. `AR()` checks the current language; `applyLang()` sets `dir="rtl"` and refreshes `[data-t]` / `[data-tp]` elements.
- **Geo helpers** — `dist` (haversine, metres), `bearing`, `projSeg` (project point onto segment).
- **Map** — Leaflet; tile layers in `LAYERS`: CARTO Voyager (day) / Dark Matter (night), with OSM as fallback after repeated tile errors. CARTO requires an API key (`CARTO_KEY`, appended as `?key=`) and watermarks tiles without one; an empty `CARTO_KEY` means OSM only. On OSM, night mode darkens tiles with a CSS filter (`.dark.osm`). `applyTheme()` picks the layer.
- **GPS** — `startGPS` → `onFix` computes speed/heading (falls back to deltas between fixes), then drives `followMe`, `navUpdate` or `freeAlerts`, and `maybeLimit`.
- **Search** — `geocode` (Photon → Nominatim), `reverse` (Nominatim), result list in `#results`.
- **Routing** — `fetchRoutes` tries `ROUTERS` in order; `prepRoute` builds `ll` (lat/lng array), `cum` (cumulative distance), `steps`, and `sa` (distance-along of each step's maneuver).
- **Navigation** — state in `nav`. `navUpdate` snaps to the route (`snapTo`), detects off-route → `reroute`, advances step index `k`, updates banner / numbers, triggers `voiceFor` (far / near / now prompts) and report alerts, and detects arrival.
- **Instructions** — `instr(step)` builds text from OSRM maneuver type + modifier, separately for Arabic and English; `mIcon` maps maneuvers to Tabler icons.
- **Reports** — `RT` defines types as `[icon, color, lifetimeMs]` (0 = permanent). Local when signed out or offline; shared through Supabase when signed in (see Cloud below). Server lifetimes are in `add_report` in `supabase/schema.sql` and must match `RT`.
- **Nearby** — `NEAR` holds Overpass filters per category.
- **UI** — bottom sheet rendered from `SHEETS[name]()` via `sheet(name)`. All clicks go through one delegated handler keyed on `data-act`.

## PWA

- `sw.js` precaches the app shell and `vendor/`; pages are network-first, same-origin static files stale-while-revalidate. Tiles and API calls (including Supabase) are never intercepted. Not registered inside the APK.
- **Bump `VERSION` in `sw.js`** whenever `sw.js` or its precache lists change. `index.html` changes need no bump (network-first).
- `beforeinstallprompt` is stored in `installEvt`; Settings shows an Install app button while it's available.

## Cloud (Supabase)

- Project: `darb`, ref `qxxnyhwifclvrdwsurqo`, region eu-central-1 (Frankfurt), free plan. Through the Supabase connector, SQL containing `delete`/`revoke` needs a confirmation that can't be shown on the user's phone, so schema changes with those go to the user to run in the SQL Editor; additive changes can be applied with `apply_migration`.
- Config: `SB_URL` / `SB_KEY` (publishable key) near the top of the script. Both empty → `CLOUD=false` and every cloud feature is hidden (device-only mode). The app must keep working fully in that mode.
- The app only calls RPC functions: `get_traffic`, `get_reports` (anon OK), `submit_traffic`, `add_report`, `remove_report` (signed in). Tables have RLS on with no policies. Change `supabase/schema.sql` and tell the user to re-run it in the SQL Editor (it's idempotent).
- Auth: Google via `signInWithOAuth`, PKCE. Web redirects back to the page; the APK opens the system browser (`Browser` plugin) and returns via `darb://auth` (`appUrlOpen` → `finishAppSignIn`).
- Traffic: `sampleTraffic()` (on each GPS fix) queues the 45–90 s average speed every 10 s while driving; `flushTraffic()` sends batches every 30 s. Grid is 0.0015° cells × 8 heading buckets, same formula on client (`cellKey`) and server. `trafLevel()`: 0 flowing, 1 slow, 2 jam.
- Reports: local ones have ids `r…`; shared ones have `remote:true` and `mine`. Only local ones go to `localStorage`.

## Android (Capacitor)

- `NATIVE` is true inside the APK. Native plugins are reached with `Capacitor.registerPlugin(name)` — no bundler: `TextToSpeech` (speechSynthesis doesn't exist in Android WebView), `KeepAwake`, `App` (back button, deep links), `Browser`.
- `npm run sync` = build `www/` (esbuild lowers JS to chrome60) + `npx cap sync android`. `www/`, `android/app/src/main/assets/public` and `capacitor-cordova-android-plugins` are generated, not committed.
- App id `iq.layth.darb` — never change it (installed apps would stop updating).
- Signing: `android/release.keystore.enc` (AES-256, pbkdf2 200k) + GitHub secret `DARB_KEYSTORE_PASSWORD`. Never commit a plaintext keystore. Version code = workflow run number.
- After adding a Capacitor plugin: add it to `package.json` (exact version), run `npm install` and `npx cap sync android`, commit `android/` changes.

## Conventions

- Escape any external text with `esc()` before inserting into HTML.
- Network calls go through `fetchT(url, ms, opt)` (timeout + abort).
- Keep the web app single-file; update a vendored library by replacing its file in `vendor/` and bumping `VERSION` in `sw.js`.
- Every new Tabler icon name must exist in `vendor/tabler/tabler-icons.min.css`.
- Test the web app with a headless browser (Playwright is available) — mock Supabase for cloud features.
- Test on a phone over https (GitHub Pages) — GPS, voice, and wake lock need a secure context.
