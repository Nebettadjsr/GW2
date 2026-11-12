/**
 * Browser probe server for the steady warm capacity-discovery driver (STORY-KPI-002).
 *
 * Serves the UNCHANGED built frontend (`dist/`) from one loopback origin and forwards `/api/` to the
 * isolated fixture application (same-origin, like the production frontend container). A Java driver
 * talks to it over stdin/stdout, one JSON object per line, strictly sequentially:
 *
 *   {"id":1,"cmd":"login","principal":"p0","key":"..."}          untimed: connect one browser context
 *   {"id":2,"cmd":"probe","principal":"p0","journey":"bank","expectCount":12}
 *   {"cmd":"close"}
 *
 * A probe measures initiation (the navigation click/hash change) to the rendered usable marker of the
 * existing UI (`data-test` hooks only). Loading, error, empty and retained states are never counted
 * as usable. The probe holds no credential beyond the key it is handed per login and never logs it.
 *
 * Usage: node scripts/capacity-probe.mjs --backend http://127.0.0.1:PORT [--dist dist]
 * Environment: GW2_BROWSER_PATH, GW2_PROBE_TIMEOUT_MS (default 15000)
 */
import { createServer, request as httpRequest } from 'node:http'
import { createInterface } from 'node:readline'
import { readFile } from 'node:fs/promises'
import { extname, join, normalize, resolve } from 'node:path'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { isAllowedUrl, isRequiredApiFailure } from './capacityGuard.mjs'
import { evaluate, pathOf } from './requestCorrelation.mjs'

const args = process.argv.slice(2)
const opt = (name, fallback) => (args.includes(name) ? args[args.indexOf(name) + 1] : fallback)
const backend = new URL(opt('--backend'))
const dist = resolve(opt('--dist', 'dist'))
const TIMEOUT_MS = Number(process.env.GW2_PROBE_TIMEOUT_MS ?? 15_000)
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon' }

/** Static-asset requests that reached the loopback server (a warm browser cache answers without reaching it). */
const staticHits = new Map()
const server = createServer(async (req, res) => {
  if (req.url.startsWith('/api/')) {
    const headers = { ...req.headers, host: backend.host }
    if (headers.origin) headers.origin = backend.origin
    const upstream = httpRequest({ host: backend.hostname, port: backend.port, path: req.url, method: req.method, headers }, (u) => {
      res.writeHead(u.statusCode ?? 502, u.headers)
      u.pipe(res)
    })
    upstream.on('error', () => { res.writeHead(502); res.end('backend unreachable') })
    req.pipe(upstream)
    return
  }
  try {
    const clean = normalize(new URL(req.url, 'http://x').pathname).replace(/^(\.\.[/\\])+/, '')
    let file = join(dist, clean === '/' || clean === '\\' ? 'index.html' : clean)
    if (!file.startsWith(dist)) throw new Error('outside dist')
    let body
    try { body = await readFile(file) } catch { file = join(dist, 'index.html'); body = await readFile(file) }
    const pathname = new URL(req.url, 'http://x').pathname
    staticHits.set(pathname, (staticHits.get(pathname) ?? 0) + 1)
    // Fingerprinted build assets are cacheable like the production frontend serves them; index.html is revalidated.
    const cacheable = pathname.startsWith('/assets/')
    res.writeHead(200, { 'content-type': TYPES[extname(file)] ?? 'application/octet-stream', 'cache-control': cacheable ? 'public, max-age=3600, immutable' : 'no-cache' })
    res.end(body)
  } catch (e) { res.writeHead(404); res.end('not found') }
})

const CHAR = /^\/api\/account\/characters\/[^/]+$/
/** The resolved recipe must be the one the click requested (identity echoed by the response). */
function resolutionMatches(e) {
  let asked
  try { asked = JSON.parse(e.post ?? '{}').recipeId } catch { return 'unreadable resolution request' }
  if (typeof asked !== 'number') return 'resolution request has no recipe identity'
  if (e.json?.recipeId !== asked) return 'resolution answered a different recipe'
  return e.json?.consistency ? null : 'resolution has no consistency'
}

/** journey -> { hash, steps before timing, timed action, usable predicate evaluated in the page } */
const JOURNEYS = {
  bank: { expect: [{ method: 'GET', path: '/api/account/bank', accept: (e) => (e.json?.slots ? null : 'bank response has no slots') }],  nav: 'bank', usable: `document.querySelectorAll('[data-test="bank-screen"] [data-test="bank-slot"]').length > 0 && !document.querySelector('[data-test="bank-loading"],[data-test="bank-error"]')`, count: `document.querySelectorAll('[data-test="bank-screen"] [data-test="bank-slot"]').length` },
  materials: { expect: [{ method: 'GET', path: '/api/account/materials', accept: (e) => (Array.isArray(e.json?.categories) && e.json.categories.length ? null : 'materials response has no categories') }],  nav: 'materials', usable: `!!document.querySelector('[data-test="materials-screen"]') && !document.querySelector('[data-test="materials-loading"],[data-test="materials-error"],[data-test="materials-empty"]') && document.querySelectorAll('[data-test="materials-screen"] [title]').length > 0` },
  characters: { expect: [{ method: 'GET', path: '/api/account/characters' }],  nav: 'characters', usable: `document.querySelectorAll('[data-test="roster-item"]').length > 0 && !document.querySelector('[data-test="roster-loading"],[data-test="roster-error"]')`, count: `document.querySelectorAll('[data-test="roster-item"]').length` },
  'character-core': { expect: [{ method: 'GET', pattern: CHAR }, { method: 'GET', pattern: /^\/api\/account\/characters\/[^/]+\/equipment-tabs$/ }], thenExpect: [{ method: 'GET', pattern: /^\/api\/account\/characters\/[^/]+\/build-tabs$/ }],  nav: 'characters', pre: 'select-character', usable: `document.querySelectorAll('[data-test="equipment-entry"]').length > 0 && !document.querySelector('[data-test="detail-loading"],[data-test="equipment-loading"],[data-test="detail-error"],[data-test="equipment-error"]')`, then: 'section-builds', thenUsable: `!!document.querySelector('[data-test="builds-panel"]') && !document.querySelector('[data-test="builds-loading"],[data-test="builds-error"]')` },
  'character-inventory': { expect: [{ method: 'GET', pattern: /^\/api\/account\/characters\/[^/]+\/inventory$/ }],  nav: 'characters', pre: 'select-character', after: 'section-inventory', usable: `document.querySelectorAll('[data-test="inventory-grid"],[data-test="inventory-bag"],[data-test="inventory-slot-item"],[data-test="inventory-slot-empty"]').length > 0 && !document.querySelector('[data-test="inventory-loading"],[data-test="inventory-error"]')` },
  // Profit is kept alive across navigation, so reactivating it shows retained rows. The timed action is the
  // screen's own "Refresh data & results" control and the sample only completes once that new calculation answered.
  'crafting-profit': { expect: [{ method: 'POST', path: '/api/crafting/profit', accept: (e) => (e.json ? null : 'profit response has no body') }],  nav: 'crafting', pre: 'profit-rows-ready', after: 'refresh-data-and-results', freshCall: '/api/crafting/profit', usable: `document.querySelectorAll('[data-test="profit-row"]').length > 0 && !document.querySelector('[data-test="request-retry"],[data-test="selector-error"]') && document.querySelector('[data-test="refresh-data-and-results"]')?.disabled === false` },
  // The timed click selects a row that is NOT currently selected, so a new resolution request is always initiated.
  'recipe-detail': { expect: [{ method: 'POST', path: '/api/crafting/profit/resolution', accept: resolutionMatches }],  nav: 'crafting', pre: 'profit-rows-ready', after: 'select-row', afterUnselected: true, freshCall: '/api/crafting', usable: `!!document.querySelector('[data-test="resolution-tree"]') && !document.querySelector('[data-test="resolution-loading"],[data-test="resolution-failed"],[data-test="resolution-unavailable"]')` },
  'system-status': { expect: [{ method: 'GET', path: '/api/system/status' }],  nav: 'synchronization', usable: `!!document.querySelector('[data-test="account-health"],[data-test="global-health"]') && !document.querySelector('[data-test="status-needs-connection"]') && !/Loading status|Status unavailable/.test(document.querySelector('main')?.innerText ?? document.body.innerText)` }
}

let browser
const sessions = new Map()
/** Requests the browser attempted to non-loopback hosts; each one is aborted before it can leave the machine. */
const blocked = []
const out = (o) => process.stdout.write(JSON.stringify(o) + '\n')

async function login(principal, key, origin) {
  if (sessions.has(principal)) await sessions.get(principal).context.close()
  const context = await browser.newContext()
  // Offline enforcement WITHOUT context.route(): routing disables the browser HTTP cache, which would make
  // a "warm asset" experiment silently cold. Non-loopback hosts cannot resolve (--host-resolver-rules) so the
  // request never leaves the machine; the attempt is still recorded here as the explicit blocked-host proof.
  context.on('request', (req) => {
    const url = req.url()
    if (!isAllowedUrl(url)) blocked.push(new URL(url, 'http://invalid').host)
  })
  const page = await context.newPage()
  page.setDefaultTimeout(TIMEOUT_MS)
  const apiCalls = []
  const reqs = []
  const totals = { http: 0, polls: 0 }
  page.on('response', (r) => { const u = new URL(r.url()); if (u.pathname.startsWith('/api/')) apiCalls.push({ path: u.pathname.replace(/\/[0-9a-f-]{8,}/gi, '/{id}'), status: r.status() }) })
  // Every /api request is recorded at its START (monotonic sequence) and completed only after its body was read.
  const byRequest = new Map()
  page.on('request', (r) => {
    const path = pathOf(r.url())
    if (!path.startsWith('/api/')) return
    const e = { seq: reqs.length, method: r.method(), path, post: r.postData(), status: null, done: false, failed: false, json: null }
    reqs.push(e)
    byRequest.set(r, e)
  })
  page.on('requestfinished', async (r) => {
    const e = byRequest.get(r)
    if (!e) return
    try {
      const resp = await r.response()
      e.status = resp.status()
      const text = await resp.text()
      if (e.status === 200 && /json/.test(resp.headers()['content-type'] ?? '')) { try { e.json = JSON.parse(text) } catch { /* not json */ } }
    } catch { e.failed = true }
    totals.http += 1
    if (e.path.startsWith('/api/sync/tasks')) totals.polls += 1
    e.done = true
  })
  page.on('requestfailed', (r) => { const e = byRequest.get(r); if (e) { e.failed = true; e.done = true; totals.http += 1 } })
  await page.goto(origin + '/#/synchronization', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-test="api-key-input"]')
  await page.fill('[data-test="api-key-input"]', key)
  const done = page.waitForResponse((r) => r.url().endsWith('/api/session') && r.request().method() === 'POST')
  await page.click('[data-test="connect"]')
  const resp = await done
  if (resp.status() !== 200) throw new Error('login answered HTTP ' + resp.status())
  await page.waitForSelector('[data-test="disconnect"]')
  sessions.set(principal, { context, page, apiCalls, reqs, totals })
}

async function probe(cmd) {
  const s = sessions.get(cmd.principal)
  const j = JOURNEYS[cmd.journey]
  if (!s || !j) throw new Error('unknown principal or journey')
  const { page } = s
  // Untimed set-up: leave the screen under test so it is rendered afresh, then reach any in-screen prerequisite.
  // Return a previously selected character to the roster so each journey starts from the same screen state.
  const back = await page.$('[data-test="character-back"]')
  if (back) await back.click()
  await page.click('[data-test="nav-ecto"]')
  await page.waitForTimeout(150)
  s.apiCalls.length = 0
  let rmark = 0 // sequence of the first request that can belong to the timed action
  const navSel = `[data-test="nav-${j.nav}"]`
  let start
  if (!j.pre) {
    rmark = s.reqs.length
    start = performance.now()
    await page.click(navSel)
  } else {
    await page.click(navSel)
    if (j.pre === 'select-character') {
      await page.waitForSelector('[data-test="roster-item"]')
      rmark = s.reqs.length
      start = performance.now()
      await page.click('[data-test="roster-item"] >> nth=0')
    } else if (j.pre === 'profit-rows-ready') {
      // Untimed: only wait for the rows. The single timed action is the 'after' click below.
      await page.waitForSelector('[data-test="profit-row"]')
      // Untimed: let the activation-time work (calculation / resolution of a retained selection) finish.
      await page.waitForFunction(`!document.querySelector('[data-test="loading"],[data-test="pending"],[data-test="resolution-loading"]') && document.querySelector('[data-test="refresh-data-and-results"]')?.disabled === false`, null, { timeout: TIMEOUT_MS })
    }
  }
  let mark = 0
  if (j.after) {
    await page.waitForSelector(`[data-test="${j.after}"]`)
    let target = page.locator(`[data-test="${j.after}"]`).first()
    if (j.afterUnselected) {
      const idx = await page.evaluate(() => {
        const rows = [...document.querySelectorAll('[data-test="profit-row"]')]
        const i = rows.findIndex((r) => r.getAttribute('aria-current') !== 'true')
        return i
      })
      if (idx < 0) {
        // A one-row result has no different selection to click. Reopen the page untimed, keeping
        // this context's cookie, session storage and HTTP cache, to start with no selected recipe.
        await page.reload({ waitUntil: 'domcontentloaded' })
        await page.waitForSelector('[data-test="profit-row"]')
        await page.waitForFunction(`!document.querySelector('[data-test="loading"],[data-test="pending"],[data-test="resolution-loading"]') && document.querySelector('[data-test="refresh-data-and-results"]')?.disabled === false`, null, { timeout: TIMEOUT_MS })
        target = page.locator(`[data-test="${j.after}"]`).first()
      } else {
        target = page.locator('[data-test="profit-row"]').nth(idx).locator(`[data-test="${j.after}"]`).first()
      }
    }
    mark = s.apiCalls.length
    rmark = s.reqs.length
    start = performance.now()
    await target.click()
  }
  let usable = true
  let failure = ''
  let firstUsableMillis = null
  // Waits until every request the action started (by START index, exact method/path/identity) finished with
  // 200 and a fully read body, and the DOM then shows the meaningful (non-loading) content of that response.
  const complete = async (expect, fromMark) => {
    const deadline = performance.now() + TIMEOUT_MS
    for (;;) {
      const r = evaluate(s.reqs, fromMark, expect)
      if (r.state === 'failed') throw new Error(r.reason)
      if (r.state === 'complete') return r.matched
      if (performance.now() > deadline) throw new Error('no fresh response: ' + r.reason)
      await page.waitForTimeout(5)
    }
  }
  try {
    // First usable (possibly saved/retained) content is recorded separately; it never decides capacity.
    await page.waitForFunction(j.usable, null, { timeout: TIMEOUT_MS, polling: 'raf' })
    firstUsableMillis = Math.ceil(performance.now() - start)
    const matched = await complete(j.expect, rmark)
    await page.waitForFunction(j.usable, null, { timeout: TIMEOUT_MS, polling: 'raf' })
    if (j.then) {
      const rmark2 = s.reqs.length
      await page.click(`[data-test="${j.then}"]`)
      await complete(j.thenExpect, Math.min(rmark, rmark2))
      await page.waitForFunction(j.thenUsable, null, { timeout: TIMEOUT_MS, polling: 'raf' })
    }
    if (j.count && cmd.expectCount == null && matched[0]?.json?.slots) cmd.expectCount = matched[0].json.slots.length
  } catch (e) { usable = false; failure = String(e?.message ?? e).split('\n')[0].slice(0, 160) + ' calls=' + s.apiCalls.slice(mark).map((c) => `${c.path}=${c.status}`).join(',') }
  // Ceil: a fractional overrun of the 2000 ms threshold must never round down into a pass.
  const durationMillis = Math.ceil(performance.now() - start)
  let correct = true
  let detail = failure
  if (usable && cmd.expectCount != null && j.count) {
    const n = await page.evaluate(j.count)
    if (n !== cmd.expectCount) { correct = false; detail = `rendered ${n} entries, expected ${cmd.expectCount}` }
  }
  const errorCalls = s.apiCalls.filter(isRequiredApiFailure)
  if (errorCalls.length) { correct = false; detail += ' error responses: ' + errorCalls.map((c) => `${c.path}=${c.status}`).join(',') }
  return { durationMillis, usable, correct, detail, firstUsableMillis }
}

/**
 * Real-browser cached-asset observation: reload the logged-in page and count how many /assets/ files the
 * page loaded versus how many actually reached the server. Cached assets load without reaching it.
 */
async function cacheCheck(principal) {
  const s = sessions.get(principal)
  if (!s) throw new Error('unknown principal')
  const assetResponses = new Set()
  const onResp = (r) => { const p = new URL(r.url()).pathname; if (p.startsWith('/assets/')) assetResponses.add(p) }
  s.page.on('response', onResp)
  const before = [...staticHits.entries()].filter(([p]) => p.startsWith('/assets/')).reduce((n, [, c]) => n + c, 0)
  await s.page.reload({ waitUntil: 'load' })
  const after = [...staticHits.entries()].filter(([p]) => p.startsWith('/assets/')).reduce((n, [, c]) => n + c, 0)
  s.page.off('response', onResp)
  return { assetsLoaded: assetResponses.size, assetsFromServer: after - before, cacheEnabled: assetResponses.size > 0 && after - before < assetResponses.size }
}

/** Proof hook: the logged-in page itself tries to load a URL (e.g. an ArenaNet render host); the guard must stop it. */
async function attempt(principal, url) {
  const s = sessions.get(principal)
  if (!s) throw new Error('unknown principal')
  const before = blocked.length
  const loaded = await s.page.evaluate((u) => new Promise((resolve) => {
    const img = new Image()
    img.onload = () => resolve(true)
    img.onerror = () => resolve(false)
    img.src = u
  }), url)
  return { loaded, blockedNow: blocked.length - before }
}

server.listen(0, '127.0.0.1', async () => {
  // Defence in depth with the per-context route guard: any non-loopback host fails to resolve at all.
  browser = await chromium.launch({ executablePath: resolveBrowserPath(), args: ['--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost'] })
  const origin = `http://127.0.0.1:${server.address().port}`
  out({ ready: true, origin })
  const rl = createInterface({ input: process.stdin })
  for await (const line of rl) {
    if (!line.trim()) continue
    const cmd = JSON.parse(line)
    if (cmd.cmd === 'close') break
    try {
      if (cmd.cmd === 'login') { await login(cmd.principal, cmd.key, origin); out({ id: cmd.id, ok: true }) }
      else if (cmd.cmd === 'probe') out({ id: cmd.id, ok: true, ...(await probe(cmd)) })
      else if (cmd.cmd === 'counters') out({ id: cmd.id, ok: true, httpCalls: [...sessions.values()].reduce((n, x) => n + x.totals.http, 0), polls: [...sessions.values()].reduce((n, x) => n + x.totals.polls, 0) })
      else if (cmd.cmd === 'blocked') out({ id: cmd.id, ok: true, blocked: [...blocked] })
      else if (cmd.cmd === 'cachecheck') out({ id: cmd.id, ok: true, ...(await cacheCheck(cmd.principal)) })
      else if (cmd.cmd === 'attempt') out({ id: cmd.id, ok: true, ...(await attempt(cmd.principal, cmd.url)) })
      else out({ id: cmd.id, ok: false, error: 'unknown command' })
    } catch (e) { out({ id: cmd.id, ok: false, error: String(e.message ?? e).split('\n')[0].slice(0, 200) }) }
  }
  for (const s of sessions.values()) await s.context.close().catch(() => {})
  await browser.close().catch(() => {})
  server.close()
  process.exit(0)
})
