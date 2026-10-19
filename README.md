# Darb · درب

A lightweight, bilingual (Arabic / English) turn-by-turn driving navigation web app built on OpenStreetMap. It runs entirely in the browser as a single `index.html` — no build step, no backend, no API keys.

**Live app:** https://07x01313480.github.io/darb/

## Features

- Place search (Photon, with Nominatim fallback) biased to your current location
- Long-press the map to drop a pin, with reverse-geocoded address
- Car routing with alternatives (OSRM), route picker, and "via" road names
- Turn-by-turn navigation: maneuver banner, distance to next turn, ETA / time / distance remaining
- Voice guidance in Arabic or English (Web Speech API), with timing scaled to speed
- Automatic rerouting when you leave the route
- Speed display, with posted speed limits from OSM and an over-limit warning
- Hazard reports (jam, police / checkpoint, crash, hazard, closure, speed camera) with alerts ahead — stored on the device only
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

**On a phone:** open the GitHub Pages link above, allow location access, then use the browser's "Add to Home Screen" for a full-screen app.

## How it's built

| Concern | Service |
| --- | --- |
| Map rendering | [Leaflet](https://leafletjs.com) 1.9.4 |
| Map tiles | CARTO Voyager / Dark Matter (free API key in `CARTO_KEY`), falling back to OpenStreetMap |
| Search | [Photon](https://photon.komoot.io), fallback [Nominatim](https://nominatim.org) |
| Routing | OSRM via routing.openstreetmap.de, fallback router.project-osrm.org |
| Speed limits, nearby places | Overpass API |
| Icons | Tabler Icons webfont |

All of these are free public services with fair-use limits; heavy use should move to self-hosted or paid equivalents.

Settings, saved places, recents and reports live in the browser's `localStorage` under the `darb.` prefix.

## Credits

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
