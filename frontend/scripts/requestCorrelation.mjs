/**
 * Request correlation of the capacity browser probe (STORY-KPI-002).
 *
 * A timed browser action may only complete when the requests it started - matched by their actual START
 * (sequence at or after the action mark), exact method and exact path (and, where relevant, selected
 * identity) - have finished with HTTP 200 and a completely read body. A response that began before the
 * action, an unrelated path (e.g. a broader prefix), a slow, failed or non-200 response, or retained
 * content on screen never completes a fresh sample.
 *
 * entry: { seq, method, path, post, status, done, failed, json }
 * expect: [{ method, path (string, exact) | pattern (RegExp), identity?: (entry) => boolean, accept?: (entry) => string|null }]
 */
export function evaluate(entries, mark, expect) {
  const started = entries.filter((e) => e.seq >= mark)
  const matched = []
  for (const x of expect) {
    const candidates = started.filter((e) => e.method === x.method
      && (x.path !== undefined ? e.path === x.path : x.pattern.test(e.path))
      && (!x.identity || x.identity(e)))
    if (!candidates.length) return { state: 'waiting', reason: 'no matching request started by the action: ' + (x.path ?? x.pattern) }
    // The earliest still-unfinished or the latest finished candidate decides; any hard failure is final.
    const failed = candidates.find((e) => e.done && (e.failed || e.status !== 200))
    if (failed && candidates.every((e) => e.done && (e.failed || e.status !== 200))) {
      return { state: 'failed', reason: `${x.method} ${x.path ?? x.pattern} ended ${failed.failed ? 'failed' : 'HTTP ' + failed.status}` }
    }
    const ok = candidates.find((e) => e.done && !e.failed && e.status === 200)
    if (!ok) return { state: 'waiting', reason: `${x.method} ${x.path ?? x.pattern} not complete` }
    if (x.accept) {
      const bad = x.accept(ok)
      if (bad) return { state: 'failed', reason: bad }
    }
    matched.push(ok)
  }
  return { state: 'complete', matched }
}

/** Normalizes a URL path: ids stay in place (exact identity matching), query and hash are dropped. */
export function pathOf(url) {
  return new URL(url, 'http://x').pathname
}
