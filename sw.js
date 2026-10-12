// Sankalp service worker: offline app shell + notification buttons
const CACHE = 'sankalp-v28';
const SHELL = ['./', './index.html', './manifest.json', './icon-192.png', './icon-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// Network first for the app page (so updates show up), cache first for everything else on this site.
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  if (req.mode === 'navigate') {
    e.respondWith(
      fetch(req, { cache: 'no-store' }).then(res => { const copy = res.clone(); caches.open(CACHE).then(c => c.put('./index.html', copy)); return res; })
        .catch(() => caches.match('./index.html'))
    );
    return;
  }
  e.respondWith(caches.match(req).then(hit => hit || fetch(req)));
});

// Done / Snooze buttons on reminders
self.addEventListener('notificationclick', e => {
  const n = e.notification;
  const data = n.data || {};
  n.close();
  const type = e.action === 'done' ? 'done' : e.action === 'snooze' ? 'snooze' : 'open';
  e.waitUntil((async () => {
    if (type === 'open' && data.url) { await self.clients.openWindow(data.url); return; }
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (all.length) {
      const c = all[0];
      c.postMessage({ type, id: data.id, sub: data.sub });
      if (type === 'open') { try { await c.focus(); } catch (err) { } }
      return;
    }
    const q = data.id ? `?${type}=${encodeURIComponent(data.id)}${data.sub ? '&sub=' + encodeURIComponent(data.sub) : ''}` : '';
    await self.clients.openWindow('./' + q);
  })());
});
