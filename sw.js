// Kept service worker — cache-first app shell, network for API
const CACHE = "kept-v8";
const SHELL = ["./", "./index.html", "./manifest.json", "./icon-192.png", "./icon-512.png", "./buildflow-logo.png",
  "https://cdnjs.cloudflare.com/ajax/libs/react/18.2.0/umd/react.production.min.js",
  "https://cdnjs.cloudflare.com/ajax/libs/react-dom/18.2.0/umd/react-dom.production.min.js"];
self.addEventListener("install", ev => { ev.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener("activate", ev => { ev.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener("fetch", ev => {
  const req = ev.request;
  if (req.method !== "GET") return;                    // API calls are POST — always network
  const url = new URL(req.url);
  if (url.pathname.endsWith("config.js")) {
    // always from the network (no HTTP cache); last good copy only when offline
    const key = new Request(url.origin + url.pathname);
    ev.respondWith(fetch(req, { cache: "no-store" }).then(r => { const cp = r.clone(); caches.open(CACHE).then(c => c.put(key, cp)); return r; })
      .catch(() => caches.match(key)));
    return;
  }
  if (req.mode === "navigate") {
    // network-first so updates land quickly, cache fallback offline
    ev.respondWith(fetch(req, { cache: "no-cache" }).then(r => { const cp = r.clone(); caches.open(CACHE).then(c => c.put(req, cp)); return r; }).catch(() => caches.match(req).then(r => r || caches.match("./index.html"))));
    return;
  }
  ev.respondWith(caches.match(req).then(r => r || fetch(req).then(res => { const cp = res.clone(); caches.open(CACHE).then(c => c.put(req, cp)); return res; })));
});
