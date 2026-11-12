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
import { isAllowedUrl } from './capacityGuard.mjs'

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

/** journey -> { hash, steps before timing, timed action, usable predicate evaluated in the page } */
const JOURNEYS = {
  bank: { nav: 'bank', usable: `document.querySelectorAll('[data-test="bank-screen"] [data-test="bank-slot"]').length > 0 && !document.querySelector('[data-test="bank-loading"],[data-test="bank-error"]')`, count: `document.querySelectorAll('[data-test="bank-screen"] [data-test="bank-slot"]').length` },
  materials: { nav: 'materials', usable: `!!document.querySelector('[data-test="materials-screen"]') && !document.querySelector('[data-test="materials-loading"],[data-test="materials-error"],[data-test="materials-empty"]') && document.querySelectorAll('[data-test="materials-screen"] [title]').length > 0` },
  characters: { nav: 'characters', usable: `document.querySelectorAll('[data-test="roster-item"]').length > 0 && !document.querySelector('[data-test="roster-loading"],[data-test="roster-error"]')`, count: `document.querySelectorAll('[data-test="roster-item"]').length` },
  'character-core': { nav: 'characters', pre: 'select-character', usable: `document.querySelectorAll('[data-test="equipment-entry"]').length > 0 && !document.querySelector('[data-test="detail-loading"],[data-test="equipment-loading"],[data-test="detail-error"],[data-test="equipment-error"]')`, then: 'section-builds', thenUsable: `!!document.querySelector('[data-test="builds-panel"]') && !document.querySelector('[data-test="builds-loading"],[data-test="builds-error"]')` },
  'character-inventory': { nav: 'characters', pre: 'select-character', after: 'section-inventory', usable: `document.querySelectorAll('[data-test="inventory-grid"],[data-test="inventory-bag"],[data-test="inventory-slot-item"],[data-test="inventory-slot-empty"]').length > 0 && !document.querySelector('[data-test="inventory-loading"],[data-test="inventory-error"]')` },
  'crafting-profit': { nav: 'crafting', usable: `document.querySelectorAll('[data-test="profit-row"]').length > 0 && !document.querySelector('[data-test="request-retry"],[data-test="selector-error"]')` },
  'recipe-detail': { nav: 'crafting', pre: 'profit-rows-ready', after: 'select-row', afterNth: true, usable: `!!document.querySelector('[data-test="resolution-tree"]') && !document.querySelector('[data-test="resolution-loading"],[data-test="resolution-failed"],[data-test="resolution-unavailable"]')` },
  'system-status': { nav: 'synchronization', usable: `!!document.querySelector('[data-test="account-health"],[data-test="global-health"]') && !document.querySelector('[data-test="status-needs-connection"]')` }
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
  page.on('response', (r) => { const u = new URL(r.url()); if (u.pathname.startsWith('/api/')) apiCalls.push({ path: u.pathname.replace(/\/[0-9a-f-]{8,}/gi, '/{id}'), status: r.status() }) })
  await page.goto(origin + '/#/synchronization', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-test="api-key-input"]')
  await page.fill('[data-test="api-key-input"]', key)
  const done = page.waitForResponse((r) => r.url().endsWith('/api/session') && r.request().method() === 'POST')
  await page.click('[data-test="connect"]')
  const resp = await done
  if (resp.status() !== 200) throw new Error('login answered HTTP ' + resp.status())
  await page.waitForSelector('[data-test="disconnect"]')
  sessions.set(principal, { context, page, apiCalls })
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
  const navSel = `[data-test="nav-${j.nav}"]`
  let start
  if (!j.pre) {
    start = performance.now()
    await page.click(navSel)
  } else {
    await page.click(navSel)
    if (j.pre === 'select-character') {
      await page.waitForSelector('[data-test="roster-item"]')
      start = performance.now()
      await page.click('[data-test="roster-item"] >> nth=0')
    } else if (j.pre === 'profit-rows-ready') {
      // Untimed: only wait for the rows. The single timed action is the 'after' click below.
      await page.waitForSelector('[data-test="profit-row"]')
    }
  }
  if (j.after) {
    await page.waitForSelector(`[data-test="${j.after}"]`)
    start = performance.now()
    await page.click(`[data-test="${j.after}"]${j.afterNth ? ' >> nth=0' : ''}`)
  }
  let usable = true
  try {
    await page.waitForFunction(j.usable, null, { timeout: TIMEOUT_MS, polling: 'raf' })
    if (j.then) {
      await page.click(`[data-test="${j.then}"]`)
      await page.waitForFunction(j.thenUsable, null, { timeout: TIMEOUT_MS, polling: 'raf' })
    }
  } catch { usable = false }
  // Ceil: a fractional overrun of the 2000 ms threshold must never round down into a pass.
  const durationMillis = Math.ceil(performance.now() - start)
  let correct = true
  let detail = ''
  if (usable && cmd.expectCount != null && j.count) {
    const n = await page.evaluate(j.count)
    if (n !== cmd.expectCount) { correct = false; detail = `rendered ${n} entries, expected ${cmd.expectCount}` }
  }
  const errorCalls = s.apiCalls.filter((c) => c.status >= 500 || c.status === 401 || c.status === 403)
  if (errorCalls.length) { correct = false; detail += ' error responses: ' + errorCalls.map((c) => `${c.path}=${c.status}`).join(',') }
  return { durationMillis, usable, correct, detail, httpCalls: s.apiCalls.length, polls: s.apiCalls.filter((c) => c.path.startsWith('/api/sync/tasks')).length }
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
