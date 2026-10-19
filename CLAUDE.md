# Darb — development notes

Single-file web app: everything (HTML, CSS, JS) is in `index.html`, plus PWA files: `manifest.webmanifest`, `sw.js` (service worker) and `icons/`. No build step, no dependencies to install. Deployed by GitHub Pages from the `main` branch root.

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
- **Reports** — `RT` defines types as `[icon, color, lifetimeMs]` (0 = permanent). Device-local only.
- **Nearby** — `NEAR` holds Overpass filters per category.
- **UI** — bottom sheet rendered from `SHEETS[name]()` via `sheet(name)`. All clicks go through one delegated handler keyed on `data-act`.

## PWA

- `sw.js` precaches the app shell and the Leaflet / Tabler CDN files; pages are network-first, static files stale-while-revalidate. Tiles and API calls are never intercepted.
- **Bump `VERSION` in `sw.js`** whenever `sw.js` or its precache lists change. `index.html` changes need no bump (network-first).
- `beforeinstallprompt` is stored in `installEvt`; Settings shows an Install app button while it's available.

## Conventions

- Escape any external text with `esc()` before inserting into HTML.
- Network calls go through `fetchT(url, ms, opt)` (timeout + abort).
- Keep it dependency-free and single-file unless there's a strong reason to split.
- Test on a phone over https (GitHub Pages) — GPS, voice, and wake lock need a secure context.
