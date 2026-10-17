/**
 * The browser-boundary measurement of `TARGET_ARCHITECTURE.md` §17 under the cached-result contract
 * of §9.1 (UD-013/UD-015, `TEST_STRATEGY.md` §34): navigation action → complete, interactive
 * Crafting Profit page, in a real browser, against the real backend and the user's real PostgreSQL
 * database — and, separately, the asynchronous refresh that replaces a provisional result.
 *
 * Nothing here is a fixture, and nothing here is inferred:
 *
 *   - **The timer starts at the navigation action** — the `goto` for the first opening of a browser
 *     session, the click on the Crafting Profit link for a repeat — and the *initial* interval ends
 *     at the sample that showed the complete usable page the settle window then confirmed, **inside
 *     the initial calculation context**. The settle window is excluded from the reported elapsed
 *     time, and so is everything after it: the reported instant is a sample this harness took, with
 *     every requirement below — the displayed-value read and the enabled selection control
 *     included — true *at that instant*.
 *     Contexts come from the backend's own `freshness.calculationId`, so an asynchronous
 *     replacement is measured as its own phase instead of extending the initial sample (§17), while
 *     a resubmission that captured the same input generation stays inside it. The attribution rules
 *     live in `scripts/profitTiming.mjs` and are exercised by
 *     `scripts/__tests__/profitTiming.spec.mjs`.
 *   - **Completion is a complete usable page**, never a placeholder and never the first row: rows,
 *     the result controls, no loading/pending/error notice, no in-viewport image still loading, a
 *     real displayed-value read and an enabled selection control. Offscreen entries that native
 *     lazy loading has deferred are *counted and reported separately*, never waited out and never
 *     used to call a page finished.
 *
 *     Those last two are read from the live DOM **on every sample**, so they are decided inside the
 *     calculation context being measured *and* at the instant the measurement reports: the sample
 *     the reported time names is the one they were read on. A change to either of them is a change
 *     of the displayed state, so a page that becomes readable or selectable later completes later
 *     rather than silently. The page is additionally asked once more, live, after the settle window
 *     — corroboration inside the same context, never the completion evidence.
 *   - **The refresh is followed through the backend's own task status**, not through the page's
 *     indicator: every `/api/sync/tasks/{id}` answer for the work the envelope advertised is
 *     captured, so the refresh's start, its terminal outcome and the backend's own execution span
 *     are evidence rather than inference — and the recalculation it triggers and that result's
 *     rendering are reported as separate figures after it. The indicator's termination is then
 *     *checked against* the final result's rendering instead of standing in for it.
 *   - **The requested result set is not truncated.** Each phase measures the default view *and* a
 *     complete-set opening in which every display filter is cleared and "Show all" is switched on as
 *     soon as the controls exist, inside the same continuous timer, so the elapsed time covers the
 *     full requested row count rendered in the DOM.
 *   - **The measured inputs come from the backend's own echo** — effective settings, scope, row
 *     count and the freshness envelope of every answer. A response that stopped carrying them ends
 *     the run as an evidence gap rather than being measured silently (§34).
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
 * One phase per invocation, because the cache states are external conditions. The phase label names
 * the **input** state §17 requires to be distinguished, and the harness checks the label against the
 * state the backend's envelope actually reported, so a mislabelled run fails instead of becoming
 * evidence for a state it did not measure:
 *   fresh             required account sources fresh and the required quotes covered
 *   expired-account   account sources past the configured TTL, quotes covered
 *   expired-tp        account sources fresh, required quotes past the ten-minute TTL
 *   both-expired      both past their TTL
 * Any other label is measured and reported, with the observed state recorded (the three
 * browser/application icon-cache phases `STORY-WEB-010` established still work this way).
 *
 * Cold browser cache means a fresh, isolated, disposable profile directory created here — never the
 * user's own browser profile or cache, which this check must not touch. A label containing
 * `warm-browser`, or `GW2_PERF_REUSE_PROFILE=1`, reuses the directory the previous phase left.
 *
 * Environment:
 *   GW2_PERF_PAGE_URL        the page origin: a preview/dev server that proxies /api to the recorder
 *   GW2_PERF_RECORDER_PORT   port the recording proxy listens on           (default 8199)
 *   GW2_PERF_BACKEND_ORIGIN  the backend the recorder forwards to          (default 127.0.0.1:8091)
 *   GW2_PERF_PROFILE_DIR     disposable browser profile directory          (required)
 *   GW2_PERF_UPSTREAM_LOG    the upstream proxy's log, read to count CONNECT tunnels
 *   GW2_PERF_ICON_DIR        the backend's configured icon storage, reported and counted only
 *   GW2_PERF_EXPECT_STATE    the input state this run claims to measure    (default: the label)
 *   GW2_PERF_REFRESH_MS      how long one opening's refresh is followed    (default 420000)
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
import {
  EvidenceError,
  INPUT_STATES,
  LIMIT_MS,
  SETTLE_MS,
  attribute,
  inputStateOf,
  missingAccountSources,
  requireCalculationEvidence
} from './profitTiming.mjs'

const PHASE = process.argv[2] ?? 'fresh'
const PAGE_URL = process.env.GW2_PERF_PAGE_URL ?? 'http://127.0.0.1:5179'
const RECORDER_PORT = Number(process.env.GW2_PERF_RECORDER_PORT ?? 8199)
const BACKEND_ORIGIN = process.env.GW2_PERF_BACKEND_ORIGIN ?? 'http://127.0.0.1:8091'
const PROFILE_DIR = process.env.GW2_PERF_PROFILE_DIR
const UPSTREAM_LOG = process.env.GW2_PERF_UPSTREAM_LOG
const ICON_DIR = process.env.GW2_PERF_ICON_DIR
const EXPECTED_STATE =
  process.env.GW2_PERF_EXPECT_STATE ?? (INPUT_STATES.includes(PHASE) ? PHASE : null)

const POLL_MS = 50
/** How often the attribution is recomputed to decide whether an opening is finished. */
const EVALUATE_EVERY = 10
const OPEN_TIMEOUT_MS = Number(process.env.GW2_PERF_TIMEOUT_MS ?? 240_000)
/**
 * How long one opening's asynchronous refresh is followed. Above the client's own five-minute
 * recovery budget, so its bounded termination is observed here rather than cut off by this timer.
 */
const REFRESH_TIMEOUT_MS = Number(process.env.GW2_PERF_REFRESH_MS ?? 420_000)
const VIEWPORT = { width: 1440, height: 900 }

const ICON_ROUTE = /^\/api\/items\/\d+\/icon\/[0-9a-f]{64}\.(png|jpg)$/
/** `web.SyncTaskApiController`'s status resource: one refresh task's lifecycle. */
const TASK_STATUS_ROUTE = /^\/api\/sync\/tasks\/[^/]+$/

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

function shortId(calculationId) {
  return calculationId === null || calculationId === undefined ? '(none)' : calculationId.slice(0, 8)
}

/**
 * The displayed state of the page, as one comparable object.
 *
 * Deliberately not just a row count: it carries the identity and a displayed value of the first and
 * last row, the summary line, every notice the screen can show, the refresh indicator's own state
 * and what every icon is doing, so a replacement that renders identical numbers, a late icon, or a
 * second settling pass all register as a change of the displayed state.
 *
 * The effective *settings* are deliberately not read from here: the page no longer renders them
 * (STORY-PERF-003 F003), and §34 requires them from an actual backend echo, which is where this
 * harness now takes them.
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

    // The usability half of "complete usable page", read on every sample so it belongs to the
    // calculation context that sample falls in: the value a result row actually displays, and
    // whether that row can be selected. Both are read from the row itself, so a page whose table
    // has not rendered — or whose rows are inert — cannot satisfy them.
    const firstRow = rows[0]
    const value = firstRow?.querySelector('[data-test="total-profit"]')?.textContent.trim() ?? null
    const select = firstRow?.querySelector('[data-test="select-row"]') ?? null

    return {
      rows: rows.length,
      first: identity(rows[0]),
      last: identity(rows[rows.length - 1]),
      displayedValue: value,
      selectionEnabled: select === null ? false : !select.disabled,
      summary: document.querySelector('[data-test="summary"]')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
      scope: document.querySelector('[data-test="effective-scope"]')?.textContent.trim() ?? null,
      loading: document.querySelector('[data-test="loading"]') !== null,
      error: document.querySelector('[data-test="request-error"]') !== null,
      pending: document.querySelector('[data-test="pending"]') !== null,
      controls: document.querySelector('[data-test="show-all"]') !== null,
      /** UD-015's "Stale data – refreshing…" state: a provisional result with recovery running. */
      staleIndicator: document.querySelector('[data-test="stale-data-indicator"]') !== null,
      refreshFailed: document.querySelector('[data-test="refresh-failed"]') !== null,
      refreshRetry: document.querySelector('[data-test="refresh-retry"]') !== null,
      icons: counts,
      visiblePending,
      deferred,
      decoded
    }
  })
}

/**
 * Asks the page, there and then, for a displayed value and for the state of a selection control.
 *
 * Corroboration, not the completion evidence: the reading the measurement rests on is the one
 * `pageSnapshot` took on the boundary sample, at the instant the reported time names. This one
 * happens once the settle window has elapsed, so it is later than that instant by construction —
 * it is recorded with the calculation ids answered by then, and is worth taking because it shows
 * the page is still the initial context's and still usable when asked outside the sampling loop.
 */
async function readInteractive(page) {
  return page.evaluate(() => {
    const row = document.querySelector('[data-test="profit-row"]')
    const select = row?.querySelector('[data-test="select-row"]') ?? null
    return {
      displayedValue: row?.querySelector('[data-test="total-profit"]')?.textContent.trim() ?? null,
      recipe: row?.querySelector('.recipe-ids')?.textContent.trim() ?? null,
      selectionEnabled: select === null ? false : !select.disabled,
      selectionLabel: select?.getAttribute('aria-current') ?? null
    }
  })
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

/**
 * One opening: the navigation action, the initial cached page measured inside its own calculation
 * context, and — when that page is provisional — the asynchronous refresh followed to its terminal
 * outcome as separate figures.
 *
 * @param {object} options
 * @param {() => Promise<void>} options.navigate   the navigation action itself, inside the timer
 * @param {boolean} options.completeSet            ask for every requested row, not the opening view
 * @param {object|null} options.inherited          the generation already displayed when this started
 */
async function measureOpening(page, recorder, context, { label, navigate, completeSet, inherited }) {
  await waitForQuietImages(page, context)
  const startedAt = process.hrtime.bigint()
  const elapsed = () => Number(process.hrtime.bigint() - startedAt) / 1e6
  const backendMark = recorder.mark()
  const browserMark = context.browserRequests.length
  const tunnelsBefore = upstreamTunnels()

  context.answers = []
  context.taskReads = []
  context.openingStartedAt = startedAt

  await navigate()
  if (completeSet) await requestCompleteSet(page)

  const samples = []
  const openDeadline = Date.now() + OPEN_TIMEOUT_MS
  let refreshDeadline = null
  let report = null
  let followedRefresh = false
  let refreshDeadlineReached = false
  let initialRead = null

  for (let poll = 0; ; poll += 1) {
    const state = await pageSnapshot(page)
    samples.push({ at: elapsed(), state, displayed: JSON.stringify(state) })

    // An opening can only be attributed once an answer has been parsed — or, for an in-application
    // navigation that issues none, against the generation it inherited.
    const attributable =
      poll % EVALUATE_EVERY === 0 &&
      context.parsing === 0 &&
      (context.answers.length > 0 || inherited !== null)

    if (attributable) {
      report = attribute({
        calculations: context.answers,
        samples,
        taskReads: context.taskReads,
        inherited,
        completeSet
      })

      // The moment the initial cached page is established as complete, and *before* this loop goes
      // on to follow the refresh, the page itself is asked once more for a displayed value and for
      // the state of a selection control. The calculation ids seen so far are recorded with it, so
      // this corroboration is attributable to the initial context rather than to whatever replaces
      // it minutes later. The completion evidence itself is the boundary sample's own read, taken
      // at `report.initial.completedAt`, which is the time this harness reports.
      if (initialRead === null && report.initial.settled && report.initial.resultAvailable) {
        initialRead = await readInteractive(page)
        initialRead.at = Math.round(elapsed())
        initialRead.contextsAtRead = [
          ...new Set(context.answers.map((answer) => answer.freshness?.calculationId ?? null))
        ]
        initialRead.withinInitialContext =
          initialRead.contextsAtRead.length === 0
            ? // An in-application navigation that issued no calculation: the generation it
              // inherited is still the only one that has ever answered.
              inherited !== null
            : initialRead.contextsAtRead.length === 1 &&
              initialRead.contextsAtRead[0] === report.initial.calculationId
      }

      if (report.initial.settled || report.initial.resultAvailable === false) {
        if (!report.initial.provisional) break
        // A provisional page means the refresh is the second half of the evidence (§17).
        followedRefresh = true
        if (refreshDeadline === null) refreshDeadline = Date.now() + REFRESH_TIMEOUT_MS
        const refresh = report.refresh
        if (refresh !== null && refresh.outcome === 'failed') break
        if (refresh !== null && refresh.outcome !== 'still-running' && report.final?.settled) break
        if (Date.now() >= refreshDeadline) {
          refreshDeadlineReached = true
          break
        }
      }
    }

    check(
      Date.now() < openDeadline || followedRefresh,
      `${label}: the initial page never settled within ${OPEN_TIMEOUT_MS} ms.`
    )
    await page.waitForTimeout(POLL_MS)
  }

  // The attribution the report is built from is recomputed once more with every answer parsed.
  while (context.parsing > 0) await page.waitForTimeout(50)
  report = attribute({
    calculations: context.answers,
    samples,
    taskReads: context.taskReads,
    inherited,
    completeSet
  })

  // The last state of the page, after whatever replaced the initial result. Reported as its own
  // reading and never as the initial page's: the initial one is `report.initial.displayedValueRead`
  // and `initialRead` above, both taken inside the initial calculation context.
  const finalRead = report.contexts.at(-1).resultAvailable ? await readInteractive(page) : null

  if (report.initial.resultAvailable) {
    // The completion boundary itself: the reported time is the instant of a sample on which a
    // displayed value was read and the row's selection control was enabled. Not a property of a
    // later check — of the number being reported.
    check(
      report.initial.displayedValueRead !== null &&
        report.initial.displayedValueRead.length > 0 &&
        report.initial.selectionEnabled === true,
      `${label}: the sample the reported completion names carried no interactivity evidence.`
    )
    check(
      report.initial.displayedValueReadAt === report.initial.completedAt,
      `${label}: the displayed-value read is at ${report.initial.displayedValueReadAt} ms but the ` +
        `reported completion is ${report.initial.completedAt} ms; the read must be the boundary.`
    )
    // And the corroborating live re-read, still inside the initial calculation context.
    check(
      initialRead !== null,
      `${label}: the initial page was never read interactively inside its own calculation context.`
    )
    check(
      initialRead.displayedValue !== null && initialRead.displayedValue.length > 0,
      `${label}: the initial page's displayed value read as empty.`
    )
    check(
      initialRead.selectionEnabled === true,
      `${label}: the initial page's row selection control was disabled.`
    )
  }

  const backendRecords = recorder.since(backendMark)
  const images = backendRecords.filter((entry) => ICON_ROUTE.test(entry.path))
  const profitCalls = backendRecords.filter((entry) => entry.path === '/api/crafting/profit')
  const taskReads = backendRecords.filter((entry) => entry.path.startsWith('/api/sync/tasks/'))
  const windowRequests = context.browserRequests.slice(browserMark)

  return {
    label,
    completeSet,
    report,
    samples: samples.length,
    refreshDeadlineReached,
    initialRead,
    finalRead,
    backendRequests: backendRecords.length,
    backendProfitMs: profitCalls.map((entry) => entry.ms),
    backendTaskReads: taskReads.length,
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

/** The account sources' captured states, as the envelope reported them — never as a guess. */
function describeAccount(envelope) {
  return envelope.accountSources
    .map((source) => `${source.source} ${source.state}${source.fetchedAt === null ? ' (never)' : ''}`)
    .join(', ')
}

function describePrices(prices) {
  return (
    `${prices.freshItems}/${prices.requiredItems} fresh, ${prices.staleItems} expired, ` +
    `${prices.missingItems} without a stored quote, covered ${prices.covered}`
  )
}

/**
 * What one opening measured: the instant of the boundary sample — the complete usable page, read
 * and selectable at that instant, which the settle window then confirmed.
 *
 * A context that never completed has no such instant; its last displayed change is printed instead
 * and labelled as not a completion, so an unfinished opening is visible rather than absent.
 */
function completionOf(initial) {
  return initial.completedAt ?? initial.lastChangeAt
}

function reportOpening(measurement) {
  const { report } = measurement
  const initial = report.initial
  const snapshot = initial.finalState ?? { icons: {}, visiblePending: 0, deferred: 0, decoded: 0 }
  const envelope = report.effective?.freshness ?? null
  const refresh = report.refresh

  const completion = completionOf(initial)

  const lines = [
    `\n  ${measurement.label}`,
    `    initial complete usable page: ${completion} ms` +
      ` (${completion <= LIMIT_MS ? 'within' : 'ABOVE'} §17's ${LIMIT_MS} ms)` +
      `${initial.settled ? '' : ' — NEVER COMPLETED in its own context; this is the last displayed change, not a completion'}` +
      `${initial.resultAvailable ? '' : ' — NO USABLE CACHE: this is a readiness wait, not compliance'}`,
    `    initial calculation context : ${shortId(initial.calculationId)}` +
      ` · input state ${initial.inputState}` +
      ` · ${initial.provisional ? 'provisional' : 'final'}` +
      `${initial.inherited ? ' · client-held rows, no calculation issued' : ''}`,
    `    quiescent in that context   : ${initial.settled ? 'yes' : 'NO'}` +
      ` (unchanged from ${completion} ms, confirmed at ${initial.settledAt} ms; the ${SETTLE_MS} ms` +
      ' settle window is excluded from the time above' +
      `${initial.cutByReplacement ? '; cut short by the replacement' : ''}` +
      `${initial.missing.length === 0 ? '' : `; missing: ${initial.missing.join(', ')}`})`,
    `    first rows displayed        : ${initial.firstRowsAt} ms (not completion)`,
    `    rows displayed / requested  : ${snapshot.rows ?? 0} / ${initial.requestedRowCount}` +
      `${measurement.completeSet ? ' (complete requested set)' : ' (opening view, 250-row display limit)'}`,
    `    read AT the completion above: ${JSON.stringify(initial.displayedValueRead)}` +
      ` · selection enabled ${initial.selectionEnabled} · at ${initial.displayedValueReadAt} ms` +
      ` (the boundary sample of context ${shortId(initial.calculationId)}; the reported time is this` +
      ' sample’s own instant, so the read is inside the measured interval)',
    `    live re-read, same context  : ${JSON.stringify(measurement.initialRead?.displayedValue ?? null)}` +
      ` · selection enabled ${measurement.initialRead?.selectionEnabled ?? null}` +
      ` · at ${measurement.initialRead?.at ?? '—'} ms (after the settle window — corroboration, not` +
      ' the completion evidence)' +
      ` · contexts answered by then ${JSON.stringify(
        (measurement.initialRead?.contextsAtRead ?? []).map((id) => shortId(id))
      )}` +
      ` → attributable to the initial context: ${measurement.initialRead?.withinInitialContext ?? null}`,
    `    read after any replacement  : ${JSON.stringify(measurement.finalRead?.displayedValue ?? null)}` +
      ` · selection enabled ${measurement.finalRead?.selectionEnabled ?? null}` +
      ` (context ${shortId(report.contexts.at(-1).calculationId)}; not the initial page's evidence)`,
    `    calculations in this opening: ${report.contexts.reduce((sum, c) => sum + c.answers.length, 0)}` +
      ` in ${report.contexts.length} context(s) ` +
      report.contexts
        .map((c) => `${shortId(c.calculationId)}${c.provisional ? '(provisional)' : '(final)'}`)
        .join(' → ')
  ]

  if (envelope !== null) {
    lines.push(
      `    account inputs at capture   : ${describeAccount(envelope)}`,
      `    trading post coverage       : ${describePrices(envelope.tradingPost)}`,
      `    outstanding work admitted   : ${
        envelope.outstandingWork
          .map((work) => `${work.kind}[${work.sources.join('+') || 'prices'}] ${work.taskState}/${work.reuse}`)
          .join(', ') || 'none'
      }`,
      `    backend-reported failures   : ${
        envelope.failures.map((failure) => `${failure.code} (retryable ${failure.retryable})`).join(', ') ||
        'none'
      }`,
      `    effective settings (echo)   : ${JSON.stringify(report.effective.settings)}`,
      `    effective scope (echo)      : ${JSON.stringify(report.effective.scope)}`
    )
  }

  if (refresh !== null) {
    lines.push(
      `    refresh work advertised     : ${
        refresh.advertised
          .map(
            (task) =>
              `${task.kind}[${task.sources.join('+') || 'prices'}] ${shortId(task.taskId)}` +
              ` ${task.taskState}/${task.reuse}${task.followable ? '' : ' (no status to follow)'}`
          )
          .join(', ') || 'none'
      }`,
      `    task status observed        : ${
        refresh.advertised
          .filter((task) => task.readings > 0)
          .map(
            (task) =>
              `${task.kind} ${task.readings} read(s) ${task.states.join('→')}` +
              ` · first at ${task.firstObservedAt} ms · terminal ${task.terminalState}` +
              ` at ${task.terminalObservedAt} ms · backend queued ${task.queuedMs} ms,` +
              ` executed ${task.executionMs} ms${task.failure === null ? '' : ` · failure ${JSON.stringify(task.failure)}`}`
          )
          .join('; ') || 'NONE — the advertised work was never polled'
      }`,
      `    refresh admitted → terminal : ${refresh.startToTerminalMs} ms` +
        ` (admitted ${refresh.admittedAt} ms → last terminal task outcome ${refresh.terminalAt} ms;` +
        ` outcome ${refresh.outcome}` +
        `${refresh.retryOffered === null ? '' : `, retry offered ${refresh.retryOffered}`};` +
        ' no numerical refresh budget is established by §17)',
      `    backend task execution      : ${refresh.taskExecutionMs} ms` +
        ' (the backend’s own submitted/started/finished instants, no rendering inside it)',
      `    terminal work → final answer: ${refresh.terminalToFinalAnswerMs} ms (the recalculation)`,
      `    refreshed calculation render: ${refresh.refreshedRenderMs} ms`,
      `    navigation → final result   : ${refresh.navigationToFinalMs} ms`,
      `    indicator seen → gone       : ${refresh.indicatorSeenAt} → ${refresh.indicatorGoneAt} ms` +
        ` · final result rendered at ${refresh.finalRenderedAt} ms` +
        ` (${
          refresh.finalRenderDistinguishable === true
            ? 'rendered content changed there'
            : 'the replacement rendered identical content; its answer is the earliest provable rendering'
        })`,
      `    final captured inputs       : ${
        report.final === null
          ? 'NOT REACHED within this observation window'
          : `${describeAccount(report.final.answers.at(-1).freshness)} · ${describePrices(
              report.final.answers.at(-1).freshness.tradingPost
            )}`
      }`
    )
    if (measurement.refreshDeadlineReached) {
      lines.push(
        `    note                        : the refresh was still running when this harness stopped ` +
          `following it after ${REFRESH_TIMEOUT_MS} ms`
      )
    }
  }

  lines.push(
    `    backend requests            : ${measurement.backendRequests}` +
      ` (profit ${measurement.backendProfitMs.join('/')} ms; ${measurement.backendTaskReads} task-status` +
      `; images ${measurement.backendImageRequests}` +
      ` = ${measurement.backendImage200}x200, ${measurement.backendImage304}x304,` +
      ` ${measurement.backendImageOther}x other)`,
    `    browser requests            : ${measurement.browserRequests}` +
      ` (${measurement.browserImageRequests} images, ${measurement.imagesInFlight} still in flight at` +
      ' completion)',
    `    upstream CONNECT tunnels    : ${measurement.upstreamTunnels}`,
    `    icons on screen             : ${snapshot.icons?.image ?? 0} with a URL` +
      ` (${snapshot.decoded} decoded, ${snapshot.visiblePending} still pending in view,` +
      ` ${snapshot.deferred} offscreen and unfinished — deferred by lazy loading or still in flight),` +
      ` ${snapshot.icons?.['no-url'] ?? 0} without metadata, ${snapshot.icons?.failed ?? 0} failed`,
    `    stored icon entries after   : ${measurement.storedIcons}`,
    `    summary line                : ${snapshot.summary}`,
    `    page samples taken          : ${measurement.samples}`
  )

  if (report.violations.length > 0) {
    for (const violation of report.violations) lines.push(`    CONTRACT VIOLATION          : ${violation}`)
  }

  console.log(lines.join('\n'))
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
    console.log('=== STORY-PERF-004 §17 cached-page and refresh measurement ===')
    console.log(`Phase        : ${PHASE}${EXPECTED_STATE === null ? '' : ` (expects input state ${EXPECTED_STATE})`}`)
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
    console.log(`Viewport     : ${VIEWPORT.width}x${VIEWPORT.height}`)
    console.log(`Started      : ${new Date().toISOString()}\n`)

    // A cold browser cache is a fresh, isolated, disposable profile directory — never the user's own.
    // A leftover browser process can still hold files in a previous run's directory; that is a reason
    // to point this phase at a new directory, never a reason to continue with a cache of unknown
    // warmth.
    const reuseProfile = PHASE.includes('warm-browser') || process.env.GW2_PERF_REUSE_PROFILE === '1'
    if (!reuseProfile) {
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
  const context = {
    browserRequests: [],
    answers: [],
    /** `/api/sync/tasks/{id}` answers: what the advertised refresh work actually reported. */
    taskReads: [],
    parsing: 0,
    imagesInFlight: 0,
    openingStartedAt: process.hrtime.bigint()
  }
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

  const evidenceGaps = []

  // The refresh's own lifecycle, straight from the status route the envelope advertised. Without
  // these bodies the refresh's start and terminal outcome could only be inferred from the page's
  // indicator, which reports when the client stopped waiting — a different event from the work
  // finishing (`TASK_STATUS_ROUTE` is the route `web.SyncTaskApiController` serves).
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if (!TASK_STATUS_ROUTE.test(path)) return
    const at = Number(process.hrtime.bigint() - context.openingStartedAt) / 1e6
    const taskId = path.slice(path.lastIndexOf('/') + 1)
    context.parsing += 1
    void response
      .json()
      .then((body) => {
        context.taskReads.push({
          at,
          httpStatus: response.status(),
          taskId: body.taskId ?? taskId,
          operation: body.operation ?? null,
          // A 404 is the forgotten-task case: no state, and the body is the error instead.
          state: body.state ?? (response.status() === 404 ? 'NOT_FOUND' : null),
          submittedAt: body.submittedAt ?? null,
          startedAt: body.startedAt ?? null,
          finishedAt: body.finishedAt ?? null,
          failure: body.failure ?? null
        })
      })
      .catch((cause) => {
        evidenceGaps.push(`a task status body could not be read: ${cause.message}`)
      })
      .finally(() => {
        context.parsing -= 1
      })
  })

  page.on('response', (response) => {
    if (!response.url().endsWith('/api/crafting/profit')) return
    // Stamped when the answer arrived, not when its 2 MB body finished parsing below.
    const at = Number(process.hrtime.bigint() - context.openingStartedAt) / 1e6
    context.parsing += 1
    void response
      .json()
      .then((body) => {
        const answer = {
          at,
          httpStatus: response.status(),
          status: body.status ?? null,
          rowCount: body.rowCount ?? null,
          settings: body.settings ?? null,
          scope: body.scope ?? null,
          freshness: body.freshness ?? null
        }
        try {
          requireCalculationEvidence(answer, `the answer at ${Math.round(at)} ms`)
        } catch (gap) {
          evidenceGaps.push(gap.message)
        }
        context.answers.push(answer)
      })
      .catch((cause) => {
        evidenceGaps.push(`an answer's body could not be read: ${cause.message}`)
      })
      .finally(() => {
        context.parsing -= 1
      })
  })
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))

  const measurements = []

  /** The generation displayed when the next opening starts, so an opening that issues no
   *  calculation of its own is still attributed to a real input generation. */
  const inheritedFrom = (measurement) => {
    const last = measurement?.report.contexts.at(-1)
    if (last === undefined) return null
    return {
      calculationId: last.calculationId,
      provisional: last.provisional,
      resultAvailable: last.resultAvailable,
      inputState: last.inputState,
      requestedRowCount: last.requestedRowCount,
      answers: last.answers
    }
  }

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
        inherited: null,
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
        inherited: inheritedFrom(measurements.at(-1)),
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
        inherited: inheritedFrom(measurements.at(-1)),
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
        inherited: inheritedFrom(measurements.at(-1)),
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
        inherited: inheritedFrom(measurements.at(-1)),
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

    const initials = measurements.map((measurement) => measurement.report.initial)
    const usable = measurements.filter((measurement) => measurement.report.initial.resultAvailable)
    const maximum = Math.max(...usable.map((measurement) => completionOf(measurement.report.initial)))
    const completeSetMax = Math.max(
      ...usable
        .filter((measurement) => measurement.completeSet)
        .map((measurement) => completionOf(measurement.report.initial))
    )
    const observedStates = [...new Set(initials.map((initial) => initial.inputState))]
    const violations = measurements.flatMap((measurement) =>
      measurement.report.violations.map((violation) => `${measurement.label}: ${violation}`)
    )

    console.log(`\n=== ${PHASE} summary ===`)
    console.log(`Finished     : ${new Date().toISOString()}`)
    console.log(`Observed input states (from the envelopes): ${observedStates.join(', ')}`)
    console.log(
      `Maximum initial complete usable page : ${maximum} ms (§17 limit ${LIMIT_MS} ms)` +
        `${usable.length === measurements.length ? '' : ` — over ${usable.length} of ${measurements.length} openings with a usable cached result`}`
    )
    console.log(`Maximum for the complete requested set: ${completeSetMax} ms`)
    console.log(
      'Per opening (initial / refresh start→terminal / navigation→final): ' +
        measurements
          .map((measurement) => {
            const refresh = measurement.report.refresh
            return (
              `${completionOf(measurement.report.initial)} / ` +
              `${refresh?.startToTerminalMs ?? '—'} / ${refresh?.navigationToFinalMs ?? '—'}`
            )
          })
          .join(' | ')
    )
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
    console.log(
      `Calculations issued in total: ${measurements.reduce(
        (sum, measurement) =>
          sum + measurement.report.contexts.reduce((inner, c) => inner + c.answers.length, 0),
        0
      )}`
    )

    check(evidenceGaps.length === 0, `Evidence gaps in the measured answers: ${evidenceGaps.join(' | ')}`)
    check(upstreamImages.length === 0, `The browser requested ArenaNet: ${JSON.stringify(upstreamImages)}`)
    check(offOrigin.length === 0, `The browser left this origin: ${JSON.stringify(offOrigin.slice(0, 5))}`)
    check(offRoute.length === 0, `An image was requested off the icon route: ${JSON.stringify(offRoute)}`)
    check(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`)

    if (EXPECTED_STATE !== null) {
      const first = initials.at(0)
      check(
        first.inputState === EXPECTED_STATE,
        `This phase claims to measure the ${EXPECTED_STATE} input state, but the backend's envelope ` +
          `for the first opening reported ${first.inputState} ` +
          `(${describeAccount(first.answers.at(0).freshness)}; ` +
          `${describePrices(first.answers.at(0).freshness.tradingPost)}). ` +
          'Arrange the state and rerun rather than relabelling the evidence.'
      )
      console.log(`Claimed input state ${EXPECTED_STATE} confirmed by the first opening's envelope.`)
    }

    const unusable = measurements.filter((measurement) => !measurement.report.initial.resultAvailable)
    for (const measurement of unusable) {
      const missing = missingAccountSources(measurement.report.initial.answers.at(0).freshness)
      console.log(
        `\n${measurement.label}: NO USABLE CACHED RESULT (missing sources: ${missing.join(', ')}). ` +
          'Reported as a readiness wait and eventual calculation/rendering, not as seven-second ' +
          'compliance (§17).'
      )
    }

    console.log(
      `\n${PHASE}: measurement complete. ` +
        `${
          usable.length === 0
            ? 'No opening had a usable cached result, so this phase establishes no §17 compliance.'
            : maximum <= LIMIT_MS
              ? 'Every initial cached-page reading was within §17’s limit.'
              : 'At least one initial reading was ABOVE §17’s limit — report it for planner disposition.'
        }`
    )

    check(
      violations.length === 0,
      `Observed contract violations:\n  - ${violations.join('\n  - ')}`
    )
  } finally {
    await browserContext.close()
    recorder.close()
  }
}

run().catch((error) => {
  const kind = error instanceof EvidenceError ? 'EVIDENCE GAP' : 'FAILED'
  console.error(`\nProfit page measurement (${PHASE}) ${kind}: ${error.message}`)
  process.exitCode = 1
})
