# Darb · درب

A lightweight, bilingual (Arabic / English) turn-by-turn driving navigation app built on OpenStreetMap. It runs in the browser as a single `index.html` with no build step, installs as a PWA, and is also packaged as an Android APK. An optional Supabase backend adds Google sign-in, live traffic from drivers, and shared road reports.

**Live app:** https://07x01313480.github.io/darb/  
**Android APK:** https://github.com/07x01313480/darb/releases/latest/download/darb.apk  
**First-time setup (signing secret, Supabase, Google sign-in):** see [SETUP.md](SETUP.md)

## Features

- Place search (Photon, with Nominatim fallback) biased to your current location
- Long-press the map to drop a pin, with reverse-geocoded address
- Car routing with alternatives (OSRM), route picker, and "via" road names
- Turn-by-turn navigation: maneuver banner, distance to next turn, ETA / time / distance remaining
- Voice guidance in Arabic or English (Web Speech API), with timing scaled to speed
- Automatic rerouting when you leave the route
- Speed display, with posted speed limits from OSM and an over-limit warning
- Hazard reports (jam, police / checkpoint, crash, hazard, closure, speed camera) with alerts ahead — shared with other drivers when signed in, otherwise kept on the device
- Live traffic: signed-in drivers anonymously share their average speed while driving; slow and jammed areas show on the map and on your route
- Optional Google sign-in (Supabase Auth)
- Nearby fuel, parking, hospitals and ATMs (Overpass API)
- Home / Work shortcuts, favourites and recent places
- Share a place or live ETA
- Day / night map themes (auto by time of day), RTL layout for Arabic
- Keeps the screen awake while navigating (Wake Lock API)

## Running it

GPS only works over `https://` (or `localhost`), so open it through GitHub Pages or a local server — not by double-clicking the file.

**Locally:**

```sh
python3 -m http.server 8000
# then open http://localhost:8000
```

**On a phone:** open the GitHub Pages link above and allow location access. To install it as an app, tap **Install app** in Darb's Settings (when the browser offers it), or use the browser menu → **Install app** / **Add to Home screen**. Once installed, the app shell opens even offline; maps, search and routing still need a connection.

## How it's built

| Concern | Service |
| --- | --- |
| Map rendering | [Leaflet](https://leafletjs.com) 1.9.4 |
| Map tiles | CARTO Voyager / Dark Matter (free API key in `CARTO_KEY`), falling back to OpenStreetMap |
| Search | [Photon](https://photon.komoot.io), fallback [Nominatim](https://nominatim.org) |
| Routing | OSRM via routing.openstreetmap.de, fallback router.project-osrm.org |
| Speed limits, nearby places | Overpass API |
| Icons | Tabler Icons webfont |
| Accounts, traffic, shared reports | [Supabase](https://supabase.com) (Postgres + Auth), schema in `supabase/schema.sql` |
| Android app | [Capacitor](https://capacitorjs.com) 8, built by GitHub Actions |

Leaflet, Tabler Icons and supabase-js are bundled in `vendor/`, so the app shell works offline. The map, search, routing and Overpass services are free public services with fair-use limits; heavy use should move to self-hosted or paid equivalents.

Settings, saved places, recents and device-only reports live in `localStorage` under the `darb.` prefix.

## Android APK

Every push to `main` runs `.github/workflows/android.yml`, which builds `www/` (the web app with JavaScript lowered for older Android WebViews), wraps it with Capacitor and publishes a signed APK as a GitHub Release. Inside the APK, voice guidance uses Android's text-to-speech and the screen is kept on natively.

Build locally (needs Android SDK + JDK 21):

```sh
npm ci
npm run sync
cd android && ./gradlew assembleDebug
```

## Privacy

Traffic samples are sent only when signed in, sharing is on, and the car is moving. Each sample is the average speed over the last minute at a position the server turns into a ~150 m grid cell; no account ID is stored with it, and samples are deleted after 8 days. Shared reports keep the author's ID so they can delete their own, but it's never shown to other users.

## Credits

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
