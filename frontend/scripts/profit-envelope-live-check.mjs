/**
 * Read-only check that the browser really receives the Crafting Profit freshness envelope the
 * backend delivers (STORY-WEB-034, `TARGET_ARCHITECTURE.md` 9.1, STORY-APP-014's HTTP contract).
 *
 * The controlled-origin checks (`smoke:profit`, `smoke:profit:refresh`) prove the page's behaviour
 * against responses this repository writes. They cannot prove that the shape those responses
 * imitate is the shape the backend sends — a frontend reading a field the backend never emits
 * passes every one of them. This check closes exactly that gap and nothing else: it loads the page
 * against the current backend and asserts the envelope in the response the browser itself received,
 * then asserts the page's state agrees with it.
 *
 * It is deliberately not a performance or a data check. Timing is STORY-PERF-004's subject, row
 * economics are the real-database equivalence checks' (`TEST_STRATEGY.md` 34), and nothing here
 * compares a monetary value or prints account contents.
 *
 * **Read-only and never synchronizing.** It clicks no control at all: the page's opening request is
 * the whole interaction. Whatever refresh the backend admits for its own inputs is the backend's
 * decision, exactly as it is for any ordinary page visit; this process issues no synchronization
 * request and asserts that none was issued from the page either.
 *
 * Requires a running backend and an origin serving the current frontend with `/api` proxied to it
 * (`npm run preview`, or the dev server). Outside CI: CI has an empty database and a placeholder
 * GW2 API key (`TEST_STRATEGY.md` 20, 36).
 *
 * Usage:  GW2_FRONTEND_URL=http://127.0.0.1:5173 node scripts/profit-envelope-live-check.mjs
 * Environment:
 *   GW2_FRONTEND_URL      origin serving the frontend  (default http://127.0.0.1:5173)
 *   GW2_BROWSER_PATH      browser executable           (default: first installed Chrome/Edge)
 *   GW2_SMOKE_TIMEOUT_MS  per-step wait                (default 60000)
 *
 * Exits 0 when every step passed, 1 otherwise.
 */
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

const ORIGIN = process.env.GW2_FRONTEND_URL ?? 'http://127.0.0.1:5173'
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 60_000)
const PROFIT_PATH = '/api/crafting/profit'

const steps = []

function record(name, detail) {
  steps.push(name)
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

function check(condition, message) {
  if (!condition) throw new Error(message)
}

/** Every field `web.dto.CalculationFreshnessDto` declares, with the type the browser reads it as. */
const ENVELOPE_FIELDS = [
  ['requestId', 'string'],
  ['calculationId', 'string'],
  ['evaluatedAt', 'string'],
  ['provisional', 'boolean'],
  ['resultAvailable', 'boolean'],
  ['accountSources', 'array'],
  ['tradingPost', 'object'],
  ['outstandingWork', 'array'],
  ['failures', 'array']
]

const COVERAGE_FIELDS = [
  ['requiredItems', 'number'],
  ['freshItems', 'number'],
  ['staleItems', 'number'],
  ['missingItems', 'number'],
  ['covered', 'boolean']
]

function typeOf(value) {
  if (Array.isArray(value)) return 'array'
  if (value === null) return 'null'
  return typeof value
}

function checkFields(where, body, fields) {
  for (const [field, expected] of fields) {
    check(
      Object.hasOwn(body, field),
      `${where} carries no "${field}". The backend serving this origin predates the cached-result ` +
        `contract — rebuild and restart it. Received keys: ${Object.keys(body).join(', ')}`
    )
    check(
      typeOf(body[field]) === expected,
      `${where}.${field} is ${typeOf(body[field])}, not ${expected}.`
    )
  }
}

async function run() {
  const browserPath = resolveBrowserPath()
  console.log(`Browser : ${browserPath}`)
  console.log(`Page    : ${ORIGIN} (real backend behind its /api proxy)\n`)

  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const pageErrors = []
  const apiRequests = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path.startsWith('/api/')) apiRequests.push({ method: request.method(), path })
  })

  try {
    const answered = page.waitForResponse(
      (response) => new URL(response.url()).pathname === PROFIT_PATH,
      { timeout: TIMEOUT_MS }
    )
    answered.catch(() => undefined) // handled at creation, so a failure here is not the last rejection
    await page.goto(`${ORIGIN}/#/crafting`, { waitUntil: 'domcontentloaded', timeout: TIMEOUT_MS })

    const response = await answered
    const status = response.status()
    check(
      status === 200 || status === 202,
      `${PROFIT_PATH} answered HTTP ${status}; the cached-result contract answers 200 or 202.`
    )
    const body = await response.json()
    record('the browser reached the backend’s Crafting Profit route', `HTTP ${status}`)

    // 1. The response the browser received carries the delivered envelope, field for field.
    check(
      body.status === 'CALCULATED' || body.status === 'PENDING',
      `The response status is ${JSON.stringify(body.status)}, not CALCULATED or PENDING.`
    )
    check(
      (status === 200) === (body.status === 'CALCULATED'),
      `HTTP ${status} does not match the body status ${body.status}.`
    )
    checkFields('freshness', body.freshness ?? {}, ENVELOPE_FIELDS)
    checkFields('freshness.tradingPost', body.freshness.tradingPost ?? {}, COVERAGE_FIELDS)
    for (const source of body.freshness.accountSources) {
      checkFields('freshness.accountSources[]', source, [
        ['source', 'string'],
        ['state', 'string']
      ])
      check(
        ['FRESH', 'STALE', 'MISSING'].includes(source.state),
        `An account source reported state ${JSON.stringify(source.state)}.`
      )
    }
    for (const work of body.freshness.outstandingWork) {
      checkFields('freshness.outstandingWork[]', work, [
        ['kind', 'string'],
        ['sources', 'array']
      ])
      check(
        ['ACCOUNT', 'TRADING_POST'].includes(work.kind),
        `Outstanding work reported kind ${JSON.stringify(work.kind)}.`
      )
      check(
        work.taskStatusUrl === null || work.taskStatusUrl.startsWith('/api/'),
        `A task status location is not a backend path: ${JSON.stringify(work.taskStatusUrl)}.`
      )
    }
    record(
      'the received body carries the delivered freshness envelope',
      `status ${body.status}, provisional ${body.freshness.provisional}, ` +
        `${body.freshness.accountSources.length} account sources, ` +
        `${body.freshness.tradingPost.requiredItems} required items ` +
        `(covered ${body.freshness.tradingPost.covered}), ` +
        `${body.freshness.outstandingWork.length} outstanding, ` +
        `${body.freshness.failures.length} failure(s)`
    )

    // 2. The two outcomes of the contract are kept apart in the body itself: a calculated answer
    //    carries rows and a count, a pending one carries neither rather than an empty result.
    if (body.status === 'CALCULATED') {
      check(
        Array.isArray(body.rows) && Number.isInteger(body.rowCount),
        `A CALCULATED answer did not carry rows and a count: rows ${typeOf(body.rows)}, ` +
          `rowCount ${typeOf(body.rowCount)}`
      )
      check(
        body.rowCount === body.rows.length,
        `rowCount ${body.rowCount} disagrees with ${body.rows.length} rows.`
      )
    } else {
      check(
        (body.rows ?? null) === null && (body.rowCount ?? null) === null,
        'A PENDING answer carried rows or a count, which the contract makes absent.'
      )
    }
    record(
      'rows and count follow the body status',
      body.status === 'CALCULATED' ? `${body.rowCount} rows` : 'absent, as PENDING requires'
    )

    // 3. The page's own state agrees with what the backend said about its inputs. Only the states
    //    the envelope implies are read here — no monetary value is compared.
    const expectedRegion = body.status === 'CALCULATED' ? 'profit-table' : 'pending'
    await page.waitForSelector(`[data-test="${expectedRegion}"]`, { timeout: TIMEOUT_MS })
    const rendered = await page.evaluate(() => ({
      rows: document.querySelectorAll('[data-test="profit-row"]').length,
      indicator: document.querySelector('[data-test="stale-data-indicator"]') !== null,
      failure: document.querySelector('[data-test="refresh-failed"]') !== null,
      pending: document.querySelector('[data-test="pending"]') !== null,
      requestError: document.querySelector('[data-test="request-error"]') !== null
    }))
    check(!rendered.requestError, 'The page reported a failed calculation request.')
    if (body.status === 'PENDING') {
      check(
        rendered.pending && rendered.rows === 0,
        `A PENDING answer produced ${rendered.rows} row(s) on screen: ${JSON.stringify(rendered)}`
      )
    } else {
      check(!rendered.pending, `A CALCULATED answer showed the pending state: ${JSON.stringify(rendered)}`)
    }
    if (body.freshness.provisional) {
      check(
        rendered.indicator || rendered.failure,
        `A provisional answer showed neither the refreshing indicator nor a failure: ` +
          JSON.stringify(rendered)
      )
    } else {
      check(
        !rendered.indicator,
        `A final answer still showed the refreshing indicator: ${JSON.stringify(rendered)}`
      )
    }
    record(
      'the page state agrees with the received envelope',
      `${rendered.rows} row(s) listed, indicator ${rendered.indicator}, failure ${rendered.failure}`
    )

    // 4. Nothing was triggered from this page: the only writes the backend performs are the ones it
    //    admitted for itself while answering.
    const triggered = apiRequests.filter(
      (request) => request.method === 'POST' && /^\/api\/(sync\/(account|global)|prices\/refresh)$/.test(request.path)
    )
    check(
      triggered.length === 0,
      `The page issued a synchronization request: ${JSON.stringify(triggered)}`
    )
    const polled = [...new Set(apiRequests.filter((r) => r.path.startsWith('/api/sync/tasks/')).map((r) => r.path))]
    for (const path of polled) {
      const advertised = body.freshness.outstandingWork.some((work) => work.taskStatusUrl === path)
      check(advertised, `The page polled ${path}, which this response never advertised.`)
    }
    record(
      'no synchronization was triggered and only advertised locations were polled',
      `${apiRequests.length} API requests, ${triggered.length} triggers, ${polled.length} polled location(s)`
    )

    check(pageErrors.length === 0, `The page reported script errors: ${pageErrors.join(' | ')}`)
    record('no uncaught page errors', `${pageErrors.length} reported`)

    console.log(`\nCrafting Profit backend-envelope connection check PASSED (${steps.length} steps).`)
    console.log('Read-only: no synchronization was triggered and no account content was printed.')
  } finally {
    await browser.close()
  }
}

try {
  await run()
} catch (error) {
  console.error(`\nFAILED after ${steps.length} step(s): ${error?.stack ?? error}`)
  process.exitCode = 1
}
