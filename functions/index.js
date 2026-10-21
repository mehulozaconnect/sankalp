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
    try { await webpush.sendNotification(s.sub, body, { TTL: 900, urgency: 'high', topic: (payload.tag || 'd').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32) || 'd' }); n++; }
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

function prune(sent, now) { const o = {}; Object.entries(sent).forEach(([k, t]) => { if (now - t < 3 * 24 * 3600e3) o[k] = t; }); return o; }

// Done / Snooze tapped on a notification (phone lock screen or watch): note it right away, and close the same reminder on your other devices
exports.act = onRequest({ region: REGION, cors: true, memory: '256MiB' }, async (req, res) => {
  try {
    const b = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
    const { u, dev, tok, type, id } = b;
    if (!u || !dev || !tok || (type !== 'kick' && !id) || !['done', 'snooze', 'seen', 'kick'].includes(type)) { res.status(400).send('bad'); return; }
    const s = await db.doc(`pushq/${u}/subs/${String(dev).replace(/\//g, '')}`).get();
    if (!s.exists || s.data().tok !== tok) { res.status(403).send('no'); return; }
    if (type === 'kick') { // an app found the service quiet: restart the minute timer
      const b2 = await db.doc('pub/beat').get(); if (!b2.exists || Date.now() - (b2.data().t || 0) > 150e3) { await chain(); await runTick(); }
      res.status(200).send('ok'); return; }
    const at = +b.at || Date.now(); const mins = Math.min(1440, Math.max(1, +b.mins || 10));
    const a = { type, id: String(id), sub: b.sub ? String(b.sub) : null, at, title: String(b.title || '').slice(0, 200), ptitle: String(b.ptitle || '').slice(0, 200), tag: String(b.tag || '').slice(0, 80) };
    if (type === 'snooze') a.until = at + mins * 6e4;
    if (type !== 'seen') await db.doc(`pushs/${u}`).set({ acts: admin.firestore.FieldValue.arrayUnion(a) }, { merge: true });
    // close it on your other devices (they pick this up through sync)
    if (b.tag) await db.doc(`users/${u}/items/${ekey('pact:' + at + '-' + Math.random().toString(36).slice(2, 7))}`).set({ d: JSON.stringify({ tag: a.tag, id: a.id, sub: a.sub }), u: Date.now(), x: false, k: 'pact' });
    res.status(200).send('ok');
  } catch (e) { console.warn(e); res.status(500).send('err'); }
});
