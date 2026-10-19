// Darb service worker: keeps the app shell and its libraries available offline.
// Map tiles, search, routing and Overpass requests are not intercepted; they always go to the network.
// Bump VERSION whenever this file or the precache list changes.
const VERSION = 'darb-v1';

const SHELL = [
  './',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png'
];
const LIBS = [
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js',
  'https://cdn.jsdelivr.net/npm/@tabler/icons-webfont@3/dist/tabler-icons.min.css'
];
const LIB_HOSTS = ['unpkg.com', 'cdn.jsdelivr.net'];

self.addEventListener('install', e => {
  e.waitUntil((async () => {
    const c = await caches.open(VERSION);
    await c.addAll(SHELL);
    // Libraries are best-effort: a CDN hiccup must not block installation.
    await Promise.all(LIBS.map(u => c.add(u).catch(() => {})));
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
  } else if (url.origin === self.location.origin || LIB_HOSTS.includes(url.hostname)) {
    e.respondWith(staleWhileRevalidate(e));
  }
});
