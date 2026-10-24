// Dayline reminder service: rings your phone, watch and laptop on time even when Dayline is asleep.
// Every minute it looks at the reminders each signed-in device listed (pushq/{uid}), checks the task is still open,
// claims the reminder (same claim the app uses, so nothing rings twice) and sends a Web Push to every device.
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { onRequest } = require('firebase-functions/v2/https');
const admin = require('firebase-admin');
const webpush = require('web-push');

admin.initializeApp();
const db = admin.firestore();
const REGION = 'asia-south1';
const ekey = k => encodeURIComponent(k).replace(/\./g, '%2E');
const FN = `https://${REGION}-${process.env.GCLOUD_PROJECT || JSON.parse(process.env.FIREBASE_CONFIG || '{}').projectId}.cloudfunctions.net/act`;
const WINDOW = 20 * 60e3; // a reminder older than this is not sent any more

async function vapid() {
  const ref = db.doc('admin/vapid'); const s = await ref.get();
  if (s.exists) return s.data();
  const k = webpush.generateVAPIDKeys(); const v = { publicKey: k.publicKey, privateKey: k.privateKey };
  await ref.set(v); await db.doc('pub/vapid').set({ publicKey: k.publicKey });
  return v;
}

async function taskOf(uid, id) {
  const s = await db.doc(`users/${uid}/items/${ekey('task:' + id)}`).get();
  if (!s.exists) return null; const v = s.data(); if (v.x || !v.d) return null;
  try { const t = JSON.parse(v.d); t.__u = v.u || 0; return t; } catch (e) { return null; }
}

async function claim(uid, ck) {
  const ref = db.doc(`users/${uid}/items/${ekey('claim:' + ck)}`);
  try {
    return await db.runTransaction(async tx => {
      const d = await tx.get(ref);
      if (d.exists && Date.now() - (d.data().u || 0) < 6 * 3600e3) return false;
      tx.set(ref, { d: '1', u: Date.now(), x: false, k: 'claim', by: 'server' }); return true;
    });
  } catch (e) { return false; }
}

async function sendAll(uid, subs, payload) {
  let n = 0;
  await Promise.all(subs.map(async s => {
    const body = JSON.stringify({ ...payload, data: { ...(payload.data || {}), u: uid, dev: s.id, tok: s.tok } });
    try { await webpush.sendNotification(s.sub, body, { TTL: 900, urgency: 'high', topic: (payload.close ? 'c' + payload.id : (payload.tag || 'd')).replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32) || 'd' }); n++; }
    catch (e) { if (e.statusCode === 404 || e.statusCode === 410) await db.doc(`pushq/${uid}/subs/${s.id}`).delete().catch(() => { }); else console.warn('push', uid, s.id, e.statusCode, e.body); }
  }));
  return n;
}

// What the server should still do after a Done / Snooze tapped while the app was asleep
// (only taps newer than the task's last change count: once the app has applied them, the task itself says what to do)
function actState(acts, it, tu) {
  const mine = (acts || []).filter(a => a.id === it.id && (a.sub || null) === (it.sub || null) && a.at > (tu || 0) - 5000);
  if (mine.some(a => a.type === 'done')) return 'drop';
  const z = mine.filter(a => a.type === 'snooze').sort((a, b) => b.until - a.until)[0];
  if (z && !it.z && it.at < z.until + 6 * 3600e3) return 'drop';
  if (it.z && it.aat && tu > it.aat + 5000) return 'drop';
  return 'ok';
}

// The minute timer: each run books the next one a minute later (Cloud Tasks). The scheduler job and any app that
// finds the service quiet can restart the chain; named tasks make sure only one chain ever runs.
const PROJECT = process.env.GCLOUD_PROJECT || JSON.parse(process.env.FIREBASE_CONFIG || '{}').projectId;
const TICK_URL = `https://${REGION}-${PROJECT}.cloudfunctions.net/tick`;
let tasksClient = null;
async function chain() {
  try {
    const { CloudTasksClient } = require('@google-cloud/tasks'); tasksClient = tasksClient || new CloudTasksClient();
    const num = '776337315217'; // project number: the default compute account runs the timer
    const parent = tasksClient.queuePath(PROJECT, REGION, 'dayline');
    const next = (Math.floor(Date.now() / 60e3) + 1) * 60e3;
    await tasksClient.createTask({ parent, task: { name: `${parent}/tasks/tick-${next / 60e3}`, scheduleTime: { seconds: next / 1000 }, dispatchDeadline: { seconds: 60 },
      httpRequest: { httpMethod: 'POST', url: TICK_URL, headers: { 'Content-Type': 'application/json' }, body: Buffer.from('{}').toString('base64'), oidcToken: { serviceAccountEmail: `${num}-compute@developer.gserviceaccount.com`, audience: TICK_URL } } } });
  } catch (e) { if (e.code !== 6) console.warn('chain', e.code, e.message); }
}

exports.tick = onSchedule({ schedule: 'every 1 minutes', region: REGION, timeZone: 'Asia/Kolkata', retryCount: 0, memory: '256MiB', timeoutSeconds: 50 }, async () => {
  await chain();
  await runTick();
});

async function runTick() {
  const v = await vapid();
  webpush.setVapidDetails('mailto:dayline@example.invalid', v.publicKey, v.privateKey);
  const now = Date.now();
  await db.doc('pub/beat').set({ t: now });
  const users = await db.collection('pushq').get();
  for (const u of users.docs) {
    const uid = u.id, q = u.data() || {};
    const stRef = db.doc(`pushs/${uid}`); const st = (await stRef.get()).data() || {};
    const sent = st.sent || {}; let acts = (st.acts || []).filter(a => now - a.at < 24 * 3600e3);
    // Snoozes tapped on the watch / lock screen while the app was asleep: ring again at the chosen time
    const extra = [];
    acts.filter(a => a.type === 'snooze').forEach(a => extra.push({ k: (a.sub ? 's' + a.sub : 't' + a.id) + '|z' + a.until, at: a.until, id: a.id, sub: a.sub || null, title: a.title || 'Reminder', body: a.sub ? 'Snoozed. ' + (a.ptitle ? 'Part of ' + a.ptitle : '') : 'Snoozed reminder', tag: a.tag || ('t' + a.id), z: 1, aat: a.at }));
    // Water nudge (works while the app sleeps; buttons log straight from the lock screen or watch)
    if (q.water && q.water.on) {
      const ws = { ...(st.ws || {}) }; const wx = (st.wx || []).filter(e => now - e.t < 36 * 3600e3);
      const nd = waterDecide(q.water, ws, wx, now);
      if (nd && !(await claim(uid, 'water|' + ws.day + '|' + Math.floor(now / 30e3)))) { /* another tick already sent this one */ } else if (nd) { const wsubs = (await db.collection(`pushq/${uid}/subs`).get()).docs.map(d => ({ id: d.id, ...d.data() })).filter(x => x.sub && x.sub.endpoint);
        await sendAll(uid, wsubs, { title: nd.title, body: nd.body, tag: 'water', actions: waterActions(+q.water.cup || 250), data: { water: 1, fn: FN }, pop: false }); }
      if (JSON.stringify(ws) !== JSON.stringify(st.ws || {})) await stRef.set({ ws }, { merge: true });
    }
    const items = [...(q.items || []), ...extra].filter(it => it && it.k && it.at <= now + 15e3 && now - it.at < WINDOW && !sent[it.k]);
    if (!items.length) { if (Object.keys(sent).length > 300 || acts.length !== (st.acts || []).length) await stRef.set({ sent: prune(sent, now), acts }, { merge: true }); continue; }
    // The Dayline phone app (full-screen card) already rings the phone and watch: then send only to the laptop
    const subs = (await db.collection(`pushq/${uid}/subs`).get()).docs.map(d => ({ id: d.id, ...d.data() })).filter(s => s.sub && s.sub.endpoint && !(q.native && s.phone));
    for (const it of items) {
      sent[it.k] = now;
      const t = await taskOf(uid, it.id);
      if (!t || t.done) continue;
      if (actState(acts, it, t.__u) === 'drop') continue;
      if (it.sub) { const c = (t.subs || []).find(x => x.id === it.sub); if (!c || c.d) continue; }
      if (!subs.length) continue;
      if (!(await claim(uid, it.k))) continue;
      await sendAll(uid, subs, { title: it.title, body: it.body, tag: it.tag, data: { id: it.id, sub: it.sub || undefined, k: it.k, title: it.title, ptitle: it.ptitle || '', fn: FN }, pop: q.pop !== false, card: q.card !== false });
    }
    await stRef.set({ sent: prune(sent, now), acts }, { merge: true });
  }
}

// ---------- Water: a gentle nudge only when you're behind ----------
const fmtMl = ml => ml >= 1000 ? (Math.round(ml / 100) / 10).toString().replace(/\.0$/, '') + ' L' : ml + ' ml';
const hm2m = s => { const [h, m] = String(s || '0:0').split(':').map(Number); return h * 60 + (m || 0); };
function waterTotal(W, wx, day) {
  const log = (W.log || []).filter(x => x && x[0]);
  const extra = (wx || []).filter(e => e.day === day && !log.some(([t, ml]) => Math.abs(t - e.t) < 3000 && ml === e.ml));
  return { tot: log.reduce((a, [, ml]) => a + (+ml || 0), 0) + extra.reduce((a, e) => a + e.ml, 0), last: Math.max(0, ...log.map(x => x[0]), ...extra.map(e => e.t)) };
}
// Returns a nudge to send now (and updates ws), or null. Same rules as the app uses when it is open.
function waterDecide(W, ws, wx, now) {
  if (!W || !W.on) return null;
  const L = new Date(now + (W.tz || 330) * 6e4), day = L.toISOString().slice(0, 10), hm = L.getUTCHours() * 60 + L.getUTCMinutes();
  if (ws.day !== day || W.day !== day) { if (ws.day !== day) Object.assign(ws, { day, n: 0, miss: 0, ign: 0, last: 0, lastTot: 0, meals: {}, snz: 0 }); if (W.day !== day) W = { ...W, log: [] }; }
  const a = hm2m(W.from || '09:00'), b = hm2m(W.to || '21:00'); if (hm < a || hm >= b) return null;
  if (W.quiet) { const [qs, qe] = W.quiet.map(hm2m); if (qs <= qe ? (hm >= qs && hm < qe) : (hm >= qs || hm < qe)) return null; }
  const goal = +W.goal || 2500, cup = +W.cup || 250; const { tot, last } = waterTotal(W, wx, day);
  if (tot >= goal) return null;
  if ((W.focus || 0) > now) return null;
  if ((W.mtg || []).some(([s, e]) => s <= now && now < e)) return null;
  if (now < Math.max(W.snz || 0, ws.snz || 0)) return null;
  if (now - last < 45 * 6e4) return null;
  if ((ws.miss || 0) >= (+W.maxN || 6)) return null; // only nudges you didn't act on count towards the daily limit
  const pace = Math.round(goal * Math.max(0, Math.min(1, (hm - a) / Math.max(1, b - a))) / 50) * 50, behind = pace - tot;
  const sinceNudge = now - (ws.last || 0);
  let kind = null;
  const mEnd = W.meet !== false && (W.mtg || []).find(([s, e]) => e - s >= 30 * 6e4 && now >= e && now - e < 10 * 6e4);
  const meal = W.meals ? ['13:30', '20:30'].find(m => hm >= hm2m(m) && hm < hm2m(m) + 10 && !(ws.meals || {})[m]) : null;
  if ((mEnd || meal) && behind > 0 && sinceNudge >= 30 * 6e4) kind = mEnd ? 'meet' : 'meal';
  else if (behind >= cup && sinceNudge >= ((ws.ign || 0) >= 2 ? 180 : ws.last && tot > (ws.lastTot || 0) ? 60 : 90) * 6e4) kind = 'behind'; // you answered the last one: a little sooner
  if (!kind) return null;
  const missed = !!ws.last && tot <= (ws.lastTot || 0); ws.ign = missed ? (ws.ign || 0) + 1 : 0; if (missed) ws.miss = (ws.miss || 0) + 1;
  ws.last = now; ws.n = (ws.n || 0) + 1; ws.lastTot = tot; if (meal) (ws.meals ||= {})[meal] = 1;
  const g = Math.max(1, Math.round(behind / cup));
  const title = kind === 'meet' ? '💧 Meeting done. Have a glass' : kind === 'meal' ? '💧 A glass with your meal' : g === 1 ? '💧 1 glass behind' : '💧 Time for a glass';
  return { title, body: `${fmtMl(tot)} of ${fmtMl(goal)} · ${g >= 2 && behind >= cup ? g + ' glasses behind · tap +2' : behind >= cup ? 'one now keeps you on track' : fmtMl(goal - tot) + ' to go'}`, pct: Math.round(tot / goal * 100) };
}
const waterActions = cup => [{ action: 'w' + cup, title: '+1 glass' }, { action: 'w' + cup * 2, title: '+2' }, { action: 'wsnz', title: 'Later' }];

function prune(sent, now) { const o = {}; Object.entries(sent).forEach(([k, t]) => { if (now - t < 3 * 24 * 3600e3) o[k] = t; }); return o; }

// Done / Snooze tapped on a notification (phone lock screen or watch): note it right away, and close the same reminder on your other devices
exports.act = onRequest({ region: REGION, cors: true, memory: '256MiB' }, async (req, res) => {
  try {
    const b = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
    const { u, dev, tok, type, id } = b;
    if (!u || !dev || !tok || (!['kick', 'water', 'wsnz'].includes(type) && !id) || !['done', 'snooze', 'seen', 'kick', 'close', 'water', 'wsnz'].includes(type)) { res.status(400).send('bad'); return; }
    const s = await db.doc(`pushq/${u}/subs/${String(dev).replace(/\//g, '')}`).get();
    if (!s.exists || s.data().tok !== tok) { res.status(403).send('no'); return; }
    if (type === 'kick') { // an app found the service quiet: restart the minute timer
      const b2 = await db.doc('pub/beat').get(); if (!b2.exists || Date.now() - (b2.data().t || 0) > 150e3) { await chain(); await runTick(); }
      res.status(200).send('ok'); return; }
    if (type === 'water' || type === 'wsnz') { // +1 glass or Later from the lock screen / watch
      const at = +b.at || Date.now(); const ref = db.doc(`pushs/${u}`); const st = (await ref.get()).data() || {}; const q = (await db.doc(`pushq/${u}`).get()).data() || {}; const W = q.water || {};
      const day = new Date(at + (W.tz || 330) * 6e4).toISOString().slice(0, 10);
      if (type === 'wsnz') { const ws = { ...(st.ws || {}) }; ws.snz = at + 60 * 6e4; await ref.set({ ws }, { merge: true }); res.status(200).send('ok'); return; }
      const ml = Math.max(10, Math.min(3000, Math.round(+b.ml || W.cup || 250)));
      const wx = [...(st.wx || []).filter(e => Date.now() - e.t < 36 * 3600e3), { t: at, ml, day }];
      const before = waterTotal(W.day === day ? W : { ...W, log: [] }, st.wx || [], day).tot, after = before + ml;
      await ref.set({ wx }, { merge: true });
      if (W.goal && before < W.goal && after >= W.goal) { const v = await vapid(); webpush.setVapidDetails('mailto:dayline@example.invalid', v.publicKey, v.privateKey);
        const subs = (await db.collection(`pushq/${u}/subs`).get()).docs.map(d => ({ id: d.id, ...d.data() })).filter(x => x.sub && x.sub.endpoint);
        await sendAll(u, subs, { title: '💧 Water goal reached', body: `${fmtMl(after)} today. Nicely done.`, tag: 'water', actions: [], data: { water: 1 }, pop: false }); }
      res.status(200).send('ok'); return;
    }
    const at = +b.at || Date.now(); const mins = Math.min(1440, Math.max(1, +b.mins || 10));
    const a = { type, id: String(id), sub: b.sub ? String(b.sub) : null, at, title: String(b.title || '').slice(0, 200), ptitle: String(b.ptitle || '').slice(0, 200), tag: String(b.tag || '').slice(0, 80) };
    if (type === 'snooze') a.until = at + mins * 6e4;
    // close the same reminder on your other devices (quiet message, nothing new pops up)
    if (type !== 'seen') {
      const v = await vapid(); webpush.setVapidDetails('mailto:dayline@example.invalid', v.publicKey, v.privateKey);
      const others = (await db.collection(`pushq/${u}/subs`).get()).docs.map(d => ({ id: d.id, ...d.data() })).filter(x => x.id !== dev && x.sub && x.sub.endpoint);
      await sendAll(u, others, { close: 1, id: String(id), sub: b.sub ? String(b.sub) : undefined });
    }
    if (type === 'close') { res.status(200).send('ok'); return; }
    if (type !== 'seen') await db.doc(`pushs/${u}`).set({ acts: admin.firestore.FieldValue.arrayUnion(a) }, { merge: true });
    // close it on your other devices (they pick this up through sync)
    if (b.tag) await db.doc(`users/${u}/items/${ekey('pact:' + at + '-' + Math.random().toString(36).slice(2, 7))}`).set({ d: JSON.stringify({ tag: a.tag, id: a.id, sub: a.sub }), u: Date.now(), x: false, k: 'pact' });
    res.status(200).send('ok');
  } catch (e) { console.warn(e); res.status(500).send('err'); }
});
