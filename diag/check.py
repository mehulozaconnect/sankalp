import sys, json, os, time, urllib.request, urllib.parse
T = os.environ['T']; P = 'dayline-226fb'; U = 'yGkm2hVTyqTOYtbYp87STmgTDTx2'
B = f"https://firestore.googleapis.com/v1/projects/{P}/databases/(default)/documents"
def get(u):
    r = urllib.request.Request(u, headers={'Authorization': 'Bearer ' + T}); return json.load(urllib.request.urlopen(r))
def val(v):
    for k in ('stringValue','integerValue','booleanValue','doubleValue'):
        if k in v: return v[k]
    if 'arrayValue' in v: return [val(x) for x in v['arrayValue'].get('values', [])]
    if 'mapValue' in v: return {k: val(x) for k, x in v['mapValue'].get('fields', {}).items()}
    return None
now = int(time.time()*1000)
q = {k: val(v) for k, v in get(f"{B}/pushq/{U}").get('fields', {}).items()}
print('pushq native', q.get('native'), 'pop', q.get('pop'), 'card', q.get('card'), 'updated min ago', (now-int(q.get('u',0)))//60000, 'by', str(q.get('by'))[:6])
items = q.get('items') or []
print('queued items', len(items), 'nag items', sum(1 for i in items if '|n' in i.get('k','')))
# tasks with nag on
tok = None; tasks = []; PJ = 0; TG = set()
while True:
    u = f"{B}/users/{U}/items?pageSize=300" + (f"&pageToken={tok}" if tok else '')
    d = get(u)
    for doc in d.get('documents', []):
        name = urllib.parse.unquote(doc['name'].split('/')[-1])
        if name.startswith('project:') and not val(doc.get('fields',{}).get('x',{})): PJ += 1
        if not name.startswith('task:'): continue
        f = doc.get('fields', {}); 
        if val(f.get('x', {})) : continue
        try: t = json.loads(val(f.get('d', {})) or '{}')
        except Exception: continue
        tasks.append(t); TG.update(t.get('tags') or [])
    tok = d.get('nextPageToken')
    if not tok: break
print('open tasks', sum(1 for t in tasks if not t.get('done')))
print('PROJECTS in cloud', PJ, 'TAGS in cloud', len(TG))
prefs = None
for t in tasks:
    if t.get('done'): continue
    if t.get('nag') not in (None, 0):
        tid = t.get('id')
        mine = [i for i in items if i.get('id') == tid]
        print('NAG task', tid[:5], 'created', (now-int(t.get('createdAt',0)))//60000, 'min ago', 'date', t.get('date'), 'time', t.get('time'), 'rem', t.get('reminders'), 'nag', t.get('nag'), 'prio', t.get('priority'),
              'seen', (now-int(t['seenAt']))//60000 if t.get('seenAt') else None, 'snz', t.get('snoozeUntil'), 'nagAt', t.get('nagAt'), 'nagN', t.get('nagN'), 'fired', t.get('firedKeys'), 'someday', t.get('someday'),
              'queued', [(i['k'][-6:], (int(i['at'])-now)//60000) for i in mine][:15])
st = {k: val(v) for k, v in get(f"{B}/pushs/{U}").get('fields', {}).items()}
sent = st.get('sent') or {}
rec = sorted([(k, (now-int(v))//60000) for k, v in sent.items() if now-int(v) < 8*3600e3], key=lambda x: x[1])
print('sent last 8h', [(k[-14:], m) for k, m in rec][:40])
print('acts', [(a.get('type'), str(a.get('id'))[:5], (now-int(a.get('at',0)))//60000) for a in (st.get('acts') or [])][-10:])
for k in ['meta%3Aprefs', 'meta%3Aack', 'meta%3Arv']:
    try:
        d = get(f"{B}/users/{U}/items/{urllib.parse.quote(k)}"); f = d.get('fields', {}); s = val(f.get('d', {})) or ''
        if 'prefs' in k:
            j = json.loads(s); print('prefs nagDef', j.get('nagDef'), 'quietOn', j.get('quietOn'), j.get('quietStart'), j.get('quietEnd'), 'srvPush', j.get('srvPush'), 'phoneOff', j.get('phoneOff'))
        else: print(k, (now-int(val(f.get('u',{})) or 0))//60000, 'min ago', s[:160])
    except Exception as e: print(k, 'err', e)
