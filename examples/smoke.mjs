// End-to-end smoke test through Nginx (4200) → BFF → backend.
const B = (process.env.RACELAB_URL ?? 'http://localhost:4200') + '/api';
const j = async (url, opts = {}) => {
  const r = await fetch(url, { ...opts, headers: { 'Content-Type': 'application/json', ...(opts.headers ?? {}) } });
  const t = await r.text();
  try { return { status: r.status, body: JSON.parse(t) }; } catch { return { status: r.status, body: t }; }
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

console.log('instances', JSON.stringify((await j(`${B}/instances`)).body.map((i) => [i.instanceId, i.available])));

// SSE through nginx: create without autoStart, open the stream, then start.
const created = (await j(`${B}/experiments`, { method: 'POST', body: JSON.stringify({ presetId: 'lost-update' }) })).body;
const ctrl = new AbortController();
const sse = await fetch(`${B}/experiments/${created.id}/events`, { headers: { accept: 'text/event-stream' }, signal: ctrl.signal });
const reader = sse.body.getReader();
await j(`${B}/experiments/${created.id}/start`, { method: 'POST' });
let text = '', chunks = 0;
while (true) {
  const { value, done } = await reader.read();
  if (done) break;
  chunks++;
  text += new TextDecoder().decode(value);
  if (/event:\s?end/.test(text)) break;
}
const ids = [...text.matchAll(/^id:\s?(\d+)\s*$/gm)].map((m) => +m[1]);
console.log(`SSE: ${ids.length} events in ${chunks} chunks, sequential=${ids.every((v, i) => v === i + 1)}, end=${/event:\s?end/.test(text)}`);

const ALL = 'lost-update,lost-update-optimistic,deadlock,overselling,idempotency,jvm-synchronized,write-skew,transactional-outbox,locking-redis,lock-expiration,locking-pessimistic,isolation-read-committed';
for (const p of (process.env.PRESETS ?? ALL).split(',')) {
  const c = await j(`${B}/experiments`, { method: 'POST', body: JSON.stringify({ presetId: p, autoStart: true }) });
  if (c.status !== 201) { console.log(p, 'CREATE FAILED', c.status, JSON.stringify(c.body)); continue; }
  let st;
  for (let i = 0; i < 120; i++) {
    st = (await j(`${B}/experiments/${c.body.id}`)).body.status;
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(st)) break;
    await sleep(250);
  }
  const r = (await j(`${B}/experiments/${c.body.id}/result`)).body;
  const instances = [...new Set((r.transactions ?? []).map((t) => t.instanceId))].join('+');
  console.log(`${p.padEnd(26)} ${st} consistent=${r.consistent} [${instances}] ${String(r.summary).slice(0, 150)}`);
}

for (const f of ['json', 'csv', 'markdown', 'pdf']) {
  const r = await fetch(`${B}/experiments/${created.id}/export?format=${f}`);
  console.log('export', f, r.status, r.headers.get('content-type'), (await r.arrayBuffer()).byteLength, 'bytes');
}
const d = (await j(`${B}/dashboard`)).body;
console.log('dashboard totals', JSON.stringify(d.totals));
console.log('inspector', (await j(`${B}/inspector/snapshot`)).status, 'code', (await j(`${B}/code-examples/bank-withdrawal/ATOMIC_UPDATE`)).body.sql?.length, 'sql blocks');
