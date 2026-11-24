/* owHedge: source in speed-patch/owHedge.js */
// Backup for slow cleanup calls (Nov 18). A normal cleanup returns in about 0.5 s; now and then one
// provider hangs for many seconds. If the first request has no complete answer by the deadline, a
// second one goes out (ranked differently, so it usually lands elsewhere), the first complete answer
// wins and the other is aborted. Deadline grows with text length so long dictations don't double up.
// Also records the timing the paste log picks up.
function owHedgeDelay(body) {
  const m = Array.isArray(body.messages) ? body.messages[body.messages.length - 1] : null;
  const chars = typeof m?.content === 'string' ? m.content.length : 0;
  return Math.min(8000, 1300 + chars * 2);
}
async function owSettle(p) {
  const r = await p;
  const text = await r.text();
  let ok = false, provider = null;
  try { const j = JSON.parse(text); ok = r.ok && !!j?.choices?.[0]?.message; provider = j?.provider || null; } catch {}
  return { ok, text, provider, status: r.status, statusText: r.statusText, retryAfter: r.headers.get('retry-after') };
}
function owFetch(provider, url, init, body) {
  if (String(provider).toLowerCase() !== 'openrouter' || body.stream || !Array.isArray(body.messages)) return fetch(url, init);
  const t0 = performance.now(), outer = init.signal;
  const ctl = { a: new AbortController(), b: new AbortController() };
  const stageAt = globalThis.__owCleanupStageAt;
  const info = globalThis.__owCleanupInfo = { ms: null, prepMs: stageAt ? Math.round(t0 - stageAt) : null, hedged: false, winner: null, provider: null };
  return new Promise((resolve, reject) => {
    let done = false, live = 1, firstFail = null, timer = null;
    const end = () => { done = true; clearTimeout(timer); outer?.removeEventListener('abort', onAbort); };
    const onAbort = () => { if (done) return; end(); ctl.a.abort(); ctl.b.abort(); reject(new DOMException('Aborted', 'AbortError')); };
    const give = (x, who) => {
      end();
      ctl[who === 'a' ? 'b' : 'a'].abort();
      info.ms = Math.round(performance.now() - t0); info.winner = who; info.provider = x.provider;
      if (x.err) return reject(x.err);
      const headers = { 'content-type': 'application/json' };
      if (x.retryAfter) headers['retry-after'] = x.retryAfter;
      resolve(new Response(x.text, { status: x.status, statusText: x.statusText, headers }));
    };
    const failed = (x, who) => {
      live--; firstFail ||= { x, who };
      if (!info.hedged || live === 0) give(firstFail.x, firstFail.who); // no backup running: normal error handling and retries apply
    };
    const run = (who, ini) => owSettle(fetch(url, { ...ini, signal: ctl[who].signal })).then(
      (x) => { if (!done) x.ok ? give(x, who) : failed(x, who); },
      (err) => { if (!done) failed({ err }, who); });
    if (outer?.aborted) return onAbort();
    outer?.addEventListener('abort', onAbort, { once: true });
    run('a', init);
    timer = setTimeout(() => {
      if (done) return;
      info.hedged = true; live++;
      run('b', { ...init, body: JSON.stringify({ ...body, provider: { ...(body.provider || {}), sort: 'throughput' } }) });
    }, owHedgeDelay(body));
  });
}
if (typeof module !== 'undefined') module.exports = { owFetch, owHedgeDelay };
