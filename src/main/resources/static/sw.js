// Minimal service worker: makes the app installable and keeps static assets available offline.
// Pages are always fetched from the network (they contain personal, per-user data) - never cached.
var CACHE = 'mealtoken-static-v2';
var PRECACHE = ['/offline.html', '/css/app.css', '/js/app.js', '/js/scan.js', '/js/jsQR.min.js', '/icons/icon-192.png'];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(PRECACHE); }).then(function () { return self.skipWaiting(); }));
});
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});
self.addEventListener('fetch', function (e) {
  var req = e.request;
  if (req.method !== 'GET') return;
  var url = new URL(req.url);
  if (url.origin !== location.origin) return;
  if (req.mode === 'navigate') {
    e.respondWith(fetch(req).catch(function () { return caches.match('/offline.html'); }));
    return;
  }
  if (/^\/(css|js|icons)\//.test(url.pathname)) {
    e.respondWith(fetch(req).then(function (res) {
      var copy = res.clone(); caches.open(CACHE).then(function (c) { c.put(req, copy); }); return res;
    }).catch(function () { return caches.match(req); }));
  }
});
