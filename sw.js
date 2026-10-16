// Dayline service worker: offline app shell + notification buttons
const CACHE = 'sankalp-v84';
const ACTS = 'dayline-acts'; // taps on Done/Snooze waiting for the app (works even if the app was asleep or closed)
const SHELL = ['./', './index.html', './manifest.json', './icon-192.png', './icon-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE && k !== ACTS).map(k => caches.delete(k))))
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
    // Newest version from the internet, but never wait more than 3 seconds: then open the saved copy (and save the new one when it arrives).
    const net = fetch(req, { cache: 'no-store' }).then(res => { if (res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put('./index.html', copy)); } return res; });
    e.respondWith((async () => {
      const saved = await caches.match('./index.html');
      if (!saved) return net.catch(() => Response.error());
      const slow = new Promise(r => setTimeout(() => r(saved), 3000));
      try { return await Promise.race([net, slow]); } catch (x) { return saved; }
    })());
    return;
  }
  e.respondWith(caches.match(req).then(hit => hit || fetch(req)));
});

// Swiped away on this device: tell the app so the same reminder closes on your other device
self.addEventListener('notificationclose', e => {
  const data = e.notification.data || {}; if (!data.id) return;
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(all => { if (all[0]) all[0].postMessage({ type: 'seen', id: data.id, sub: data.sub }); }));
});
// Done / Snooze buttons on reminders
self.addEventListener('notificationclick', e => {
  const n = e.notification;
  const data = n.data || {};
  n.close();
  const sm = /^s(\d+)$/.exec(e.action || ''); // "10 min" / "30 min" buttons on the second card
  const type = e.action === 'done' ? 'done' : (e.action === 'snooze' || sm) ? 'snooze' : e.action === 'resched' ? 'resched' : e.action === 'later' ? 'pick' : data.step === 'pick' ? 'later' : 'open';
  e.waitUntil((async () => {
    if (type === 'pick') {
      // Snooze… tapped (on the phone or the watch): swap the card for one with the times, so it works without opening the app
      await self.registration.showNotification(n.title.replace(/^Snooze: /, '').replace(/^/, 'Snooze: '), { body: 'For how long? Tap the card for other times.', tag: n.tag, data: { ...data, step: 'pick' }, icon: 'icon-192.png', badge: 'icon-192.png', renotify: true, vibrate: [80], actions: [{ action: 's10', title: '10 min' }, { action: 's30', title: '30 min' }] });
      return;
    }
    if (type === 'open' && data.url) { await self.clients.openWindow(data.url); return; }
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if ((type === 'done' || type === 'snooze') && data.id) {
      // Save the tap first, so it is never lost (watch taps arrive while the app is asleep). The app applies it as soon as it runs.
      const c = await caches.open(ACTS); await c.put(new Request('./__act/' + Date.now() + '-' + Math.random().toString(36).slice(2)), new Response(JSON.stringify({ type, id: data.id, sub: data.sub || null, mins: sm ? +sm[1] : (type === 'snooze' ? 10 : 0), at: Date.now() })));
      all.forEach(w => w.postMessage({ type: 'drain' }));
      return;
    }
    if (all.length) {
      const c = all[0];
      c.postMessage({ type, id: data.id, sub: data.sub });
      if (type === 'open' || type === 'resched' || type === 'later') { try { await c.focus(); } catch (err) { } }
      return;
    }
    const q = data.id ? `?${type}=${encodeURIComponent(data.id)}${data.sub ? '&sub=' + encodeURIComponent(data.sub) : ''}` : '';
    await self.clients.openWindow('./' + q);
  })());
});
