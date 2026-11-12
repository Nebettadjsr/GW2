/**
 * Request policy of the capacity browser probe (STORY-KPI-002): the real browser may only talk to the
 * loopback same-origin proxy. Anything else - in particular an ArenaNet render/API host - is aborted.
 */
const LOOPBACK = new Set(['127.0.0.1', 'localhost', '[::1]'])

export function isAllowedUrl(raw) {
  let u
  try { u = new URL(raw) } catch { return false }
  if (u.protocol === 'data:' || u.protocol === 'blob:' || u.protocol === 'about:') return true
  return (u.protocol === 'http:' || u.protocol === 'ws:') && LOOPBACK.has(u.hostname)
}
