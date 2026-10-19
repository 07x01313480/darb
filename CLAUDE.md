# Darb — development notes

Web app: all HTML, CSS and JS is in `index.html` (no build step for the website), plus PWA files (`manifest.webmanifest`, `sw.js`, `icons/`) and bundled libraries in `vendor/` (MapLibre GL JS 6.15 ESM + worker + mapbox-gl-rtl-text 0.3.0, Leaflet 1.9.4, Tabler Icons webfont 3.49 — woff2 only, supabase-js 2.118 UMD). Deployed by GitHub Pages from the `main` branch root.

The same `index.html` is packaged as an Android APK with Capacitor 8 (`android/`, `capacitor.config.json`, `scripts/build-www.mjs`) and an optional Supabase backend (`supabase/schema.sql`). User-facing setup steps are in `SETUP.md`.

## Code map (index.html)

- **Storage** — `LS` wraps `localStorage` with the `darb.` prefix. `S` = settings (`lang`, `voice`, `night`), `P` = places (`home`, `work`, `favs`), plus `recents` and `reports`.
- **i18n** — `I18N.en` / `I18N.ar`, read through `t(key, ...args)`. Some entries are functions. Every user-facing string must exist in both languages. `AR()` checks the current language; `applyLang()` sets `dir="rtl"` and refreshes `[data-t]` / `[data-tp]` elements.
- **Geo helpers** — `dist` (haversine, metres), `bearing`, `projSeg` (project point onto segment).
- **Map (`M`)** — the app never touches a map library directly; it calls the adapter `M` (`setView`, `flyTo`, `fit`, `follow`, `marker`/`unmarker`/`unmarkers(prefix)`, `routes`, `acc`, `traffic`, `theme`, `lang`, `north`, events `click`/`longpress`/`dragstart`/`moveend`/`routeclick`/`view`). `M` keeps all overlays in `M._st` and redraws them whenever the engine or style changes. Two engines:
  - **3D (`gl`)**: MapLibre GL 6 with OpenFreeMap vector styles (`liberty` day, `dark` night — a `building-3d` extrusion layer is added to dark). Loaded via a small inline `<script type=module>` so the main script stays classic. Driving view: `follow(..., navOn, tilt)` → pitch 60°, bearing = heading, position low on screen (top padding 45%). Our layers (`d-*`) sit right above `building-3d`, below labels. Labels are switched to `name:ar` / `name:en` in `lang()`. Arabic shaping via `setRTLTextPlugin` (0.3.0 classic build — MapLibre 6's worker evals it). Zoom numbers in the app are Leaflet's; the gl engine subtracts 1. `#map.__ml` exposes the live map for tests.
  - **Flat (`flat`)**: Leaflet with CARTO Voyager/Dark Matter raster tiles (`CARTO_KEY`, `?key=`), OSM fallback after repeated tile errors (night darkens OSM via `.dark.osm`).
  - `M.start(want3d)` picks gl when `S.map3d` is on and WebGL2 exists; if MapLibre or the style fails to load within ~15 s it falls back to flat. Settings → Map view switches engines live.
  - Markers: ids `me`, `dest`, `rep:<id>`, `poi:<n>`; spec `{ll, html, size, anchor:'center'|'bottom', lift, z, rot, flat, onClick, popup}`. `rot` defined → rotates with the map (gl) / rotates `[data-rot]` (flat); `rot:null` adds class `norot`.
- **GPS** — `startGPS` → `onFix` computes speed/heading (falls back to deltas between fixes), then drives `followMe` (→ `M.follow`), `navUpdate` or `freeAlerts`, `maybeLimit` and `sampleTraffic`.
- **Search** — `geocode` (Photon → Nominatim), `reverse` (Nominatim), result list in `#results`.
- **Routing** — `fetchRoutes` tries `ROUTERS` in order; `prepRoute` builds `ll` (lat/lng array), `cum` (cumulative distance), `steps`, and `sa` (distance-along of each step's maneuver).
- **Navigation** — state in `nav`. `navUpdate` snaps to the route (`snapTo`), detects off-route → `reroute`, advances step index `k`, updates banner / numbers, triggers `voiceFor` (far / near / now prompts) and report alerts, and detects arrival.
- **Instructions** — `instr(step)` builds text from OSRM maneuver type + modifier, separately for Arabic and English; `mIcon` maps maneuvers to Tabler icons.
- **Reports** — `RT` defines types as `[icon, color, lifetimeMs]` (0 = permanent). Local when signed out or offline; shared through Supabase when signed in (see Cloud below). Server lifetimes are in `add_report` in `supabase/schema.sql` and must match `RT`.
- **Nearby** — `NEAR` holds Overpass filters per category.
- **UI** — bottom sheet rendered from `SHEETS[name]()` via `sheet(name)`. All clicks go through one delegated handler keyed on `data-act`.

## PWA

- `sw.js` precaches the app shell and `vendor/` (MapLibre's worker is requested as `maplibre-gl-worker.mjs?v=<version>` — keep that exact URL in the list when upgrading); pages are network-first, same-origin static files stale-while-revalidate. Tiles and API calls (including Supabase) are never intercepted. Not registered inside the APK.
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
- `Capacitor.registerPlugin` comes from `@capacitor/core`'s `dist/capacitor.js`, **not** from Android's native bridge. `scripts/build-www.mjs` copies it into `www/` and adds its `<script>` tag; without it every plugin is null (no voice, no sign-in — the 1.0.3 bug). Test native paths with a bridge mock that provides only `isNativePlatform`/`PluginHeaders`/`nativePromise`/`nativeCallback` plus `window.androidBridge`, never a fake `registerPlugin`.
- Voice: `speak(text | () => text)`. `ttsSay` retries while the TTS engine initialises and, if `ar-SA` is unsupported, re-renders the text in English via `withLang('en', fn)` and flags `voiceProblem` (shown in Settings with Test voice / Install voices).
- `npm run sync` = build `www/` (esbuild lowers JS to chrome60) + `npx cap sync android`. `www/`, `android/app/src/main/assets/public` and `capacitor-cordova-android-plugins` are generated, not committed.
- App id `iq.layth.darb` — never change it (installed apps would stop updating).
- Signing: `android/release.keystore.enc` (AES-256, pbkdf2 200k) + GitHub secret `DARB_KEYSTORE_PASSWORD`. Never commit a plaintext keystore. Version code = workflow run number.
- After adding a Capacitor plugin: add it to `package.json` (exact version), run `npm install` and `npx cap sync android`, commit `android/` changes.

## Car dashboard (BYD instrument cluster)

- Native side: `android/app/src/main/java/iq/layth/darb/DashboardPlugin.java` (registered in `MainActivity`), JS plugin name `Dashboard` → `DASH`. Methods: `info`, `start`, `guidance`, `end`, `showMap`, `hideMap`, `update`.
- Turn arrows: broadcast `AUTONAVI_STANDARD_BROADCAST_SEND` to `com.byd.amapservice` (DiLink 4/5) or `com.example.amapservice` (DiLink 3), `KEY_TYPE` 10001 guidance (`NEW_ICON`, `ROUNG_ABOUT_NUM`, `SEG_REMAIN_DIS`, `NEXT_ROAD_NAME`, `ROUTE_REMAIN_DIS/TIME` + DiLink 3 text extras `*_AUTO`, `ETA_TEXT`), 10019 state (8 start, 9 end). `amapIcon()` maps OSRM steps to AMap codes (2 L, 3 R, 4/5 slight, 6/7 sharp, 8 U-turn, 9 straight, 11 roundabout + exit, 12 exit roundabout, 15 arrive). Road names are sent as-is, Arabic included (the user confirmed the cluster renders Arabic). Manifest `<queries>` lists both packages. Protocol facts learned from public BYD projects (DiPlay is GPL — do not copy its code).
- Map: `Presentation` on the display whose name contains `XDJAScreenProjection` (DiLink 4/5; `shared_…_0` = 5.1 full map, inset top ~150 px) or `xdjaVirtualSurface` (DiLink 3 — usually needs ADB mode switching, not supported). It hosts a WebView serving `assets/public` from `https://appassets.androidplatform.net/public/index.html?cluster=1`. That page (`CLUSTER`) has no GPS/cloud/UI; `window.darbCluster(json)` receives `{me, head, speed, z, lang, dark, map3d, inset, dest, banner, rv, route}` from `dashPush()` (route only when `dashRoute` changes).
- Settings: `S.dash` = `off` | `arrows` (default) | `map`; Check (diagnostics) and Test buttons. Untested on a real car until the user reports back — keep the diagnostics.
- Type-check Java locally: `android.jar` (API 33) from `raw.githubusercontent.com/Sable/android-platforms` + small Capacitor stubs, `javac --release 17`; CI build logs can't be read from this sandbox.

## Conventions

- Escape any external text with `esc()` before inserting into HTML.
- Network calls go through `fetchT(url, ms, opt)` (timeout + abort).
- Keep the web app single-file; update a vendored library by replacing its file in `vendor/` and bumping `VERSION` in `sw.js`.
- Every new Tabler icon name must exist in `vendor/tabler/tabler-icons.min.css`.
- New map features go through `M` and must work in both engines; test both (`S.map3d` true/false) and the no-WebGL2 fallback.
- `scripts/build-www.mjs` must never lower `vendor/maplibre/*` (ES modules using `import.meta`).
- Test the web app with a headless browser (Playwright is available) — mock Supabase for cloud features.
- Test on a phone over https (GitHub Pages) — GPS, voice, and wake lock need a secure context.
