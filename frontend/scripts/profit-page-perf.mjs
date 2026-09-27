/**
 * The browser-boundary reverification of `TARGET_ARCHITECTURE.md` §33, taken with the shared item
 * image in place (`STORY-WEB-010`, §12.1): navigation action → complete, interactive Crafting Profit
 * page, in a real browser, against the real backend and the user's real PostgreSQL database.
 *
 * Nothing here is a fixture, and nothing here is inferred:
 *
 *   - **The timer starts at the navigation action** — the `goto` for the first opening of a browser
 *     session, the click on the Crafting Profit link for a repeat — and ends at the *last observed
 *     change* of the displayed page, detected by quiescence (`TEST_STRATEGY.md` §34). The settle
 *     window is excluded from the reported elapsed time; the number of calculations the page issued
 *     is part of the sampled state, so a redundant second reload recomputing identical numbers still
 *     moves the sample and still lands inside the measurement.
 *   - **Completion includes the images.** Every icon inside the viewport must have finished (loaded
 *     or failed) before a page counts as complete. Offscreen entries that native lazy loading has
 *     deferred are *counted and reported separately*, never waited out and never used to call a page
 *     finished.
 *   - **The requested result set is not truncated.** Each phase measures the default view *and* a
 *     complete-set opening in which every display filter is cleared and "Show all" is switched on as
 *     soon as the controls exist, inside the same continuous timer, so the elapsed time covers the
 *     full requested row count rendered in the DOM.
 *   - **Browser, backend and upstream requests are all recorded.** The browser's own requests come
 *     from the driver; the backend's come from `scripts/recordingProxy.mjs`, which the page's `/api`
 *     routing points at; upstream image fetches are CONNECT lines in `scripts/upstream-proxy.mjs`'s
 *     log. A rendered image is never read as evidence of where its bytes came from.
 *
 *     What a CONNECT count is, exactly: **tunnels, not requests.** One tunnel carries many fetches,
 *     so the number is a lower bound on upstream activity and an upper bound of zero — `0` for a
 *     freshly started backend process does prove no upstream fetch happened, because it had no open
 *     tunnel to reuse, while a positive count says only that at least one fetch went out.
 *
 * One phase per invocation, because the cache states are external conditions:
 *   cold-browser-cold-app   a fresh browser profile, against a backend whose configured icon storage
 *                           is empty
 *   cold-browser-warm-app   a fresh browser profile, against that storage once warm
 *   warm-browser-warm-app   the profile the previous phase left behind, same warm storage
 *
 * Cold browser cache means a fresh, isolated, disposable profile directory created here — never the
 * user's own browser profile or cache, which this check must not touch.
 *
 * Environment:
 *   GW2_PERF_PAGE_URL        the page origin: a preview/dev server that proxies /api to the recorder
 *   GW2_PERF_RECORDER_PORT   port the recording proxy listens on           (default 8199)
 *   GW2_PERF_BACKEND_ORIGIN  the backend the recorder forwards to          (default 127.0.0.1:8091)
 *   GW2_PERF_PROFILE_DIR     disposable browser profile directory          (required)
 *   GW2_PERF_UPSTREAM_LOG    the upstream proxy's log, read to count CONNECT tunnels
 *   GW2_PERF_ICON_DIR        the backend's configured icon storage, reported and counted only
 *   GW2_BROWSER_PATH         browser executable
 *
 * It reads no credential, holds none, and prints counts rather than account contents.
 */
import { existsSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { cpus, totalmem } from 'node:os'
import { execFileSync } from 'node:child_process'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startRecordingProxy } from './recordingProxy.mjs'

const PHASE = process.argv[2] ?? 'cold-browser-cold-app'
const PAGE_URL = process.env.GW2_PERF_PAGE_URL ?? 'http://127.0.0.1:5179'
const RECORDER_PORT = Number(process.env.GW2_PERF_RECORDER_PORT ?? 8199)
const BACKEND_ORIGIN = process.env.GW2_PERF_BACKEND_ORIGIN ?? 'http://127.0.0.1:8091'
const PROFILE_DIR = process.env.GW2_PERF_PROFILE_DIR
const UPSTREAM_LOG = process.env.GW2_PERF_UPSTREAM_LOG
const ICON_DIR = process.env.GW2_PERF_ICON_DIR

/** §33's limit, quoted here only so the report can say whether a reading met it. */
const LIMIT_MS = 7_000
const POLL_MS = 50
const SETTLE_MS = 3_000
const OPEN_TIMEOUT_MS = Number(process.env.GW2_PERF_TIMEOUT_MS ?? 240_000)
const VIEWPORT = { width: 1440, height: 900 }

const ICON_ROUTE = /^\/api\/items\/\d+\/icon\/[0-9a-f]{64}\.(png|jpg)$/

function check(condition, message) {
  if (!condition) throw new Error(message)
}

/** CONNECT tunnels the backend has opened to ArenaNet so far, counted in the proxy's own log. */
function upstreamTunnels() {
  if (UPSTREAM_LOG === undefined || !existsSync(UPSTREAM_LOG)) return null
  return readFileSync(UPSTREAM_LOG, 'utf8')
    .split('\n')
    .filter((line) => line.startsWith('CONNECT')).length
}

/** Committed entries in the backend's configured icon storage — the application cache's own state. */
function storedIcons() {
  if (ICON_DIR === undefined) return null
  const assets = `${ICON_DIR}/assets-v1`
  if (!existsSync(assets)) return 0
  return readdirSync(assets).filter((name) => /\.(png|jpg)$/.test(name)).length
}

function gitRevision() {
  try {
    return execFileSync('git', ['rev-parse', '--short', 'HEAD'], { encoding: 'utf8' }).trim()
  } catch {
    return '(unknown)'
  }
}

/**
 * The displayed state of the page, as one comparable string.
 *
 * Deliberately not just a row count: it carries the identity and a displayed value of the first and
 * last row, the summary line, the loading/error notices and what every icon is doing, so a reload that
 * replaces the rows with identical numbers, a late icon, or a second settling pass all register as a
 * change. The caller appends the number of calculations the page has issued before comparing.
 */
async function pageSnapshot(page) {
  return page.evaluate(() => {
    const rows = [...document.querySelectorAll('[data-test="profit-row"]')]
    const identity = (row) =>
      row === undefined
        ? '-'
        : [
            row.querySelector('.recipe-ids')?.textContent.trim() ?? '?',
            row.querySelector('[data-test="total-profit"]')?.textContent.trim() ?? '?',
            row.querySelector('[data-test="craftable-count"]')?.textContent.trim() ?? '?'
          ].join('/')

    const icons = [...document.querySelectorAll('[data-test="item-icon"]')]
    const counts = { image: 0, 'no-url': 0, failed: 0 }
    let visiblePending = 0
    let deferred = 0
    let decoded = 0
    for (const icon of icons) {
      counts[icon.getAttribute('data-icon-state')] += 1
      const image = icon.querySelector('img')
      if (image === null) continue
      const box = image.getBoundingClientRect()
      const inView = box.bottom > 0 && box.top < window.innerHeight
      if (image.complete) {
        if (image.naturalWidth > 0) decoded += 1
      } else if (inView) visiblePending += 1
      else deferred += 1
    }

    return {
      rows: rows.length,
      first: identity(rows[0]),
      last: identity(rows[rows.length - 1]),
      summary: document.querySelector('[data-test="summary"]')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
      settings:
        document.querySelector('[data-test="effective-settings"]')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
      scope: document.querySelector('[data-test="effective-scope"]')?.textContent.trim() ?? null,
      loading: document.querySelector('[data-test="loading"]') !== null,
      error: document.querySelector('[data-test="request-error"]') !== null,
      controls: document.querySelector('[data-test="show-all"]') !== null,
      icons: counts,
      visiblePending,
      deferred,
      decoded
    }
  })
}

/** Everything about a sample that has to stop changing before the page counts as complete. */
function comparable(snapshot, calculations) {
  return JSON.stringify({ ...snapshot, calculations })
}

/**
 * Clears every display filter and switches "Show all" on, so the table holds the complete requested
 * result set rather than the opening view's first 250 matching rows.
 *
 * Called *inside* the running timer: the application has no way to be navigated straight into this
 * state, so the interaction that asks for the full set is part of the elapsed time rather than
 * something done afterwards and left out of it.
 */
async function requestCompleteSet(page) {
  await page.waitForSelector('[data-test="show-all"]', { timeout: OPEN_TIMEOUT_MS })
  for (const filter of ['filter-zero-craftable', 'filter-not-allowed', 'filter-non-positive-profit']) {
    const box = await page.$(`[data-test="${filter}"]`)
    if (box !== null && (await box.isChecked())) await box.uncheck()
  }
  const showAll = await page.$('[data-test="show-all"]')
  if (showAll !== null && !(await showAll.isChecked())) await showAll.check()
}

/**
 * One opening, measured from the navigation action to the last change of the displayed page.
 *
 * @param {object} options
 * @param {() => Promise<void>} options.navigate   the navigation action itself, inside the timer
 * @param {boolean} options.completeSet            ask for every requested row, not the opening view
 */
/**
 * Waits for the browser to stop fetching images before an opening's timer starts, so one opening's
 * tail of offscreen lazy-loaded icons is not counted against the next one. Bounded and reported: how
 * long that tail takes is evidence, not something to wait on indefinitely.
 */
async function waitForQuietImages(page, context) {
  const deadline = Date.now() + 120_000
  while (context.imagesInFlight > 0 && Date.now() < deadline) await page.waitForTimeout(200)
  if (context.imagesInFlight > 0) {
    console.log(
      `  info ${context.imagesInFlight} image requests were still in flight after 120 s; the next ` +
        'opening’s per-window counts include their tail.'
    )
  }
}

async function measureOpening(page, recorder, context, { label, navigate, completeSet }) {
  await waitForQuietImages(page, context)
  const startedAt = process.hrtime.bigint()
  const backendMark = recorder.mark()
  const browserMark = context.browserRequests.length
  const tunnelsBefore = upstreamTunnels()
  const calculationsBefore = context.calculations

  const elapsed = () => Number(process.hrtime.bigint() - startedAt) / 1e6

  await navigate()
  if (completeSet) await requestCompleteSet(page)

  let lastChange = elapsed()
  let previous = null
  let firstRows = null
  let snapshot = null
  const deadline = Date.now() + OPEN_TIMEOUT_MS

  for (;;) {
    snapshot = await pageSnapshot(page)
    const current = comparable(snapshot, context.calculations)
    if (current !== previous) {
      previous = current
      lastChange = elapsed()
      if (firstRows === null && snapshot.rows > 0) firstRows = lastChange
    } else if (elapsed() - lastChange >= SETTLE_MS) {
      // Quiescent — but a page with an unfinished visible image is not complete, so keep watching.
      if (snapshot.visiblePending === 0 && snapshot.rows > 0 && !snapshot.loading) break
    }
    check(Date.now() < deadline, `${label}: the page never settled within ${OPEN_TIMEOUT_MS} ms.`)
    await page.waitForTimeout(POLL_MS)
  }

  // The completed table has to answer a real displayed-value read before it counts as interactive.
  const readValue = await page.$eval(
    '[data-test="profit-row"] [data-test="total-profit"]',
    (cell) => cell.textContent.trim()
  )
  check(readValue.length > 0, `${label}: the first row's total profit read as empty.`)
  const clickable = await page.$eval('[data-test="select-row"]', (button) => !button.disabled)
  check(clickable, `${label}: the first row's selection control was disabled.`)

  const backendRecords = recorder.since(backendMark)
  const images = backendRecords.filter((entry) => ICON_ROUTE.test(entry.path))
  const profitCalls = backendRecords.filter((entry) => entry.path === '/api/crafting/profit')
  const windowRequests = context.browserRequests.slice(browserMark)

  return {
    label,
    completeSet,
    completeMs: Math.round(lastChange),
    firstRowsMs: firstRows === null ? null : Math.round(firstRows),
    snapshot,
    requestedRowCount: context.lastRowCount,
    calculations: context.calculations - calculationsBefore,
    backendRequests: backendRecords.length,
    backendProfitMs: profitCalls.map((entry) => entry.ms),
    backendImageRequests: images.length,
    backendImage200: images.filter((entry) => entry.status === 200).length,
    backendImage304: images.filter((entry) => entry.status === 304).length,
    backendImageOther: images.filter((entry) => entry.status !== 200 && entry.status !== 304).length,
    browserRequests: windowRequests.length,
    browserImageRequests: windowRequests.filter((request) => request.type === 'image').length,
    /**
     * Image requests the browser had issued and not yet finished at the moment the page counted as
     * complete. Reported rather than waited for: these are offscreen icons, so they are not part of
     * the complete page, but calling them "deferred" would hide that the network is still busy.
     */
    imagesInFlight: context.imagesInFlight,
    upstreamTunnels: tunnelsBefore === null ? null : upstreamTunnels() - tunnelsBefore,
    storedIcons: storedIcons()
  }
}

function reportOpening(measurement) {
  const snapshot = measurement.snapshot
  const displayed = snapshot.rows
  const requested = measurement.requestedRowCount
  console.log(
    `\n  ${measurement.label}` +
      `\n    navigation -> complete page : ${measurement.completeMs} ms` +
      ` (${measurement.completeMs <= LIMIT_MS ? 'within' : 'ABOVE'} §33's ${LIMIT_MS} ms)` +
      `\n    first rows displayed        : ${measurement.firstRowsMs} ms` +
      `\n    rows displayed / requested  : ${displayed} / ${requested}` +
      `${measurement.completeSet ? ' (complete requested set)' : ' (opening view, 250-row display limit)'}` +
      `\n    calculations issued         : ${measurement.calculations}` +
      `\n    backend requests            : ${measurement.backendRequests}` +
      ` (profit ${measurement.backendProfitMs.join('/')} ms; images ${measurement.backendImageRequests}` +
      ` = ${measurement.backendImage200}x200, ${measurement.backendImage304}x304,` +
      ` ${measurement.backendImageOther}x other)` +
      `\n    browser requests            : ${measurement.browserRequests}` +
      ` (${measurement.browserImageRequests} images, ${measurement.imagesInFlight} still in flight at` +
      ' completion)' +
      `\n    upstream CONNECT tunnels    : ${measurement.upstreamTunnels}` +
      `\n    icons on screen             : ${snapshot.icons.image} with a URL` +
      ` (${snapshot.decoded} decoded, ${snapshot.visiblePending} still pending in view,` +
      ` ${snapshot.deferred} offscreen and unfinished — deferred by lazy loading or still in flight),` +
      ` ${snapshot.icons['no-url']} without metadata, ${snapshot.icons.failed} failed` +
      `\n    stored icon entries after   : ${measurement.storedIcons}` +
      `\n    summary line                : ${snapshot.summary}` +
      `\n    effective settings          : ${snapshot.settings}`
  )
}

async function run() {
  check(PROFILE_DIR !== undefined, 'GW2_PERF_PROFILE_DIR must name a disposable browser profile directory.')
  const browserPath = resolveBrowserPath()
  const recorder = await startRecordingProxy({ port: RECORDER_PORT, backendOrigin: BACKEND_ORIGIN })

  // Everything up to a launched browser gets its own guard: a failure here (a port already taken, a
  // profile directory still held by something) must release the recording proxy's port rather than
  // leave the process alive holding it, which is what makes the next attempt fail for a second reason.
  let browserContext
  try {
    console.log('=== STORY-WEB-010 §33 browser-boundary measurement ===')
    console.log(`Phase        : ${PHASE}`)
    console.log(`Revision     : ${gitRevision()}`)
    console.log(`Page         : ${PAGE_URL} (server -> ${recorder.origin} -> ${BACKEND_ORIGIN})`)
    console.log(`Browser      : ${browserPath}`)
    console.log(`Profile      : ${PROFILE_DIR}`)
    console.log(`Icon storage : ${ICON_DIR ?? '(not reported)'} — ${storedIcons()} entries before this phase`)
    console.log(
      `Upstream log : ${UPSTREAM_LOG ?? '(not counted)'} — ${upstreamTunnels()} tunnels before this phase`
    )
    console.log(
      `Host         : ${cpus().length}x ${cpus()[0]?.model.trim()}, ` +
        `${Math.round(totalmem() / 1024 ** 3)} GB RAM, node ${process.version}`
    )
    console.log(`Viewport     : ${VIEWPORT.width}x${VIEWPORT.height}\n`)

    // A cold browser cache is a fresh, isolated, disposable profile directory — never the user's own.
    // A leftover browser process can still hold files in a previous run's directory; that is a reason
    // to point this phase at a new directory, never a reason to continue with a cache of unknown
    // warmth.
    if (PHASE !== 'warm-browser-warm-app') {
      try {
        rmSync(PROFILE_DIR, { recursive: true, force: true })
      } catch (error) {
        throw new Error(
          `${PROFILE_DIR} could not be emptied (${error.code ?? error.message}), so this phase cannot ` +
            'claim a cold browser cache. Point GW2_PERF_PROFILE_DIR at an unused directory and rerun.'
        )
      }
    }

    browserContext = await chromium.launchPersistentContext(PROFILE_DIR, {
      executablePath: browserPath,
      viewport: VIEWPORT
    })
  } catch (error) {
    recorder.close()
    throw error
  }
  const page = browserContext.pages()[0] ?? (await browserContext.newPage())

  /** Shared observation state: what the browser asked for, and what the backend answered it. */
  const context = { browserRequests: [], calculations: 0, lastRowCount: null, imagesInFlight: 0 }
  page.on('request', (request) => {
    context.browserRequests.push({ url: request.url(), type: request.resourceType() })
    if (request.resourceType() === 'image') context.imagesInFlight += 1
  })
  // Lazy loading keeps fetching offscreen icons long after the page is complete, so each opening waits
  // for a quiet network before the next one starts its timer. Without it, one opening's tail of image
  // requests is counted against the following opening.
  const imageSettled = (request) => {
    if (request.resourceType() === 'image') context.imagesInFlight -= 1
  }
  page.on('requestfinished', imageSettled)
  page.on('requestfailed', imageSettled)
  page.on('response', async (response) => {
    if (!response.url().endsWith('/api/crafting/profit')) return
    context.calculations += 1
    try {
      const body = await response.json()
      if (typeof body.rowCount === 'number') context.lastRowCount = body.rowCount
    } catch {
      /* A failed body read must not disturb the measurement; the row count stays as it was. */
    }
  })
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))

  const measurements = []

  /**
   * Leaves the application entirely, so the next `goto` is a real document load rather than a hash
   * change the browser can satisfy without reloading anything. Outside every timer.
   */
  const leaveTheApplication = async () => {
    await waitForQuietImages(page, context)
    await page.goto('about:blank', { waitUntil: 'load', timeout: OPEN_TIMEOUT_MS })
  }

  /**
   * Parks on Bank and waits for *its* images too, so a bank icon still in flight is not counted
   * against the Crafting Profit opening that follows it.
   */
  const parkOnBank = async () => {
    await waitForQuietImages(page, context)
    await page.click('[data-test="nav-bank"]')
    await page.waitForSelector('[data-test="bank-slots"]', { timeout: OPEN_TIMEOUT_MS })
    await page.waitForFunction(
      () =>
        [...document.querySelectorAll('[data-test="item-icon-image"]')]
          .filter((image) => {
            const box = image.getBoundingClientRect()
            return box.bottom > 0 && box.top < window.innerHeight
          })
          .every((image) => image.complete),
      undefined,
      { timeout: OPEN_TIMEOUT_MS }
    )
  }

  try {
    console.log(`Browser version: ${browserContext.browser()?.version() ?? '(unknown)'}\n`)

    // 1. The first opening of this browser session: a document navigation, the bundle, the scope and
    //    settings load, the calculation and the table, all inside one timer.
    await leaveTheApplication()
    measurements.push(
      await measureOpening(page, recorder, context, {
        label: 'FIRST opening (document navigation, opening view)',
        completeSet: false,
        navigate: async () => {
          await page.goto(`${PAGE_URL}/#/crafting`, { waitUntil: 'commit', timeout: OPEN_TIMEOUT_MS })
        }
      })
    )

    // 2. A repeat opening that really opens the page again: a document load, which reloads the
    //    application and makes it ask for a fresh calculation. The bundle now comes from whatever the
    //    browser cache holds, which is the point of the cold/warm browser distinction.
    measurements.push(
      await measureOpening(page, recorder, context, {
        label: 'REPEAT #1 (document reload, fresh calculation, opening view)',
        completeSet: false,
        navigate: async () => {
          await page.reload({ waitUntil: 'commit', timeout: OPEN_TIMEOUT_MS })
        }
      })
    )

    // 3. A repeat opening through the application's own navigation. Recorded separately and labelled
    //    for what it is: this application keeps the calculated rows in the client, so renavigating
    //    re-renders them without asking the backend again. Reporting it as a comparable "reopening"
    //    would overstate the result.
    await parkOnBank()
    measurements.push(
      await measureOpening(page, recorder, context, {
        label: 'REPEAT #2 (in-application navigation, client-held rows, opening view)',
        completeSet: false,
        navigate: async () => {
          await page.click('[data-test="nav-crafting"]')
        }
      })
    )

    // 4. The complete requested result set, in the same continuous timer as the document navigation
    //    that reached it — no truncation, no display limit, every requested row in the DOM.
    await leaveTheApplication()
    measurements.push(
      await measureOpening(page, recorder, context, {
        label: 'COMPLETE requested set (document navigation + full-set request)',
        completeSet: true,
        navigate: async () => {
          await page.goto(`${PAGE_URL}/#/crafting`, { waitUntil: 'commit', timeout: OPEN_TIMEOUT_MS })
        }
      })
    )

    // 5. And the same complete set once more, as a repeat opening.
    measurements.push(
      await measureOpening(page, recorder, context, {
        label: 'COMPLETE requested set (repeat document reload + full-set request)',
        completeSet: true,
        navigate: async () => {
          await page.reload({ waitUntil: 'commit', timeout: OPEN_TIMEOUT_MS })
        }
      })
    )

    for (const measurement of measurements) reportOpening(measurement)

    const upstreamImages = context.browserRequests.filter((request) => /guildwars2\.com/i.test(request.url))
    const offOrigin = context.browserRequests.filter(
      (request) => !request.url.startsWith(new URL(PAGE_URL).origin)
    )
    const offRoute = context.browserRequests.filter(
      (request) => request.type === 'image' && !ICON_ROUTE.test(new URL(request.url).pathname)
    )

    const maximum = Math.max(...measurements.map((measurement) => measurement.completeMs))
    const completeSetMax = Math.max(
      ...measurements.filter((measurement) => measurement.completeSet).map((m) => m.completeMs)
    )
    const pendingAnywhere = measurements.reduce((sum, m) => sum + m.snapshot.visiblePending, 0)

    console.log(`\n=== ${PHASE} summary ===`)
    console.log(`Maximum navigation -> complete page : ${maximum} ms (§33 limit ${LIMIT_MS} ms)`)
    console.log(`Maximum for the complete requested set: ${completeSetMax} ms`)
    console.log(`Visible images left pending at completion: ${pendingAnywhere} (must be 0)`)
    console.log(
      `Browser requests: ${context.browserRequests.length} total, ` +
        `${context.browserRequests.filter((r) => r.type === 'image').length} images, ` +
        `${upstreamImages.length} to ArenaNet, ${offOrigin.length} off this origin, ` +
        `${offRoute.length} images off the icon route`
    )
    console.log(
      `Backend requests recorded: ${recorder.records.length} ` +
        `(${recorder.records.filter((entry) => ICON_ROUTE.test(entry.path)).length} on the icon route)`
    )
    console.log(`Upstream CONNECT tunnels this phase: ${upstreamTunnels()}`)
    console.log(`Stored icon entries after this phase: ${storedIcons()}`)
    console.log(`Calculations issued in total: ${context.calculations}`)
    console.log(`Requested row count last reported by the backend: ${context.lastRowCount}`)

    check(upstreamImages.length === 0, `The browser requested ArenaNet: ${JSON.stringify(upstreamImages)}`)
    check(offOrigin.length === 0, `The browser left this origin: ${JSON.stringify(offOrigin.slice(0, 5))}`)
    check(offRoute.length === 0, `An image was requested off the icon route: ${JSON.stringify(offRoute)}`)
    check(pendingAnywhere === 0, 'A page was reported complete with a visible image still loading.')
    check(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`)
    for (const measurement of measurements.filter((m) => m.completeSet)) {
      check(
        measurement.requestedRowCount !== null && measurement.snapshot.rows === measurement.requestedRowCount,
        `${measurement.label}: ${measurement.snapshot.rows} rows were displayed for a requested ` +
          `${measurement.requestedRowCount} — the requested set was truncated, so this is not a ` +
          'complete-page measurement.'
      )
    }

    console.log(
      `\n${PHASE}: measurement complete. ` +
        `${maximum <= LIMIT_MS ? 'Every reading was within §33’s limit.' : 'At least one reading was ABOVE §33’s limit — report it for planner disposition.'}`
    )
  } finally {
    await browserContext.close()
    recorder.close()
  }
}

run().catch((error) => {
  console.error(`\nProfit page measurement (${PHASE}) FAILED: ${error.message}`)
  process.exitCode = 1
})
