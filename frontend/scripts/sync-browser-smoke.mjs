/**
 * Real-browser check for System Status synchronization (task tracking and owner-only catalog refreshes).
 *
 * Runs the built frontend against a *controlled* API boundary: `scripts/stubOrigin.mjs` serves `dist/`
 * and this script answers the trigger, status and task routes itself, with scripted task lifecycles.
 * The real App restores and proves a synthetic retained key through `controlledAccountSession.mjs`,
 * which also blocks any off-origin request and rejects unexpected routes, methods and raw keys. Nothing
 * real is synchronized: no GW2 API call, database write or user data is touched, so this establishes
 * browser interaction and task-state presentation, and nothing about a real synchronization or about
 * performance.
 *
 * Current contracts covered: the account refresh (`POST /api/sync/account`) and one owner-only full
 * catalog refresh (`POST /api/sync/full/recipes`) are tracked through the shared
 * `/api/sync/tasks/<id>` route; owner capability is discovered only from `/api/system/status/details`
 * (200 = owner controls, 403 = ordinary account with no owner control and no owner request).
 *
 * Usage:  npm run build && npm run smoke:sync
 * Environment:
 *   GW2_SYNC_SMOKE_PORT   port for the stub origin   (default 5174)
 *   GW2_BROWSER_PATH      browser executable         (default: the first installed Chrome/Edge)
 *   GW2_SMOKE_TIMEOUT_MS  per-step wait              (default 30000)
 *
 * Exits 0 when every step passed, 1 otherwise.
 */
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { requireFreshBundle } from './bundleFreshness.mjs'
import { createControlledAccountSession } from './controlledAccountSession.mjs'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startStubOrigin } from './stubOrigin.mjs'

const PORT = Number(process.env.GW2_SYNC_SMOKE_PORT ?? 5174)
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
const DIST_DIR = fileURLToPath(new URL('../dist/', import.meta.url))

/** The state each operation's task reports, one entry per status lookup; the last entry repeats. */
const TASK_SCRIPTS = {
  ACCOUNT_SYNC: ['PENDING', 'RUNNING', 'SUCCEEDED'],
  FULL_RECIPE_CATALOG: ['PENDING', 'FAILED']
}
const TRIGGERS = {
  '/api/sync/account': { operation: 'ACCOUNT_SYNC', taskId: 'account-sync-1' },
  '/api/sync/full/recipes': { operation: 'FULL_RECIPE_CATALOG', taskId: 'full-recipes-1' }
}
const TASK_PATHS = Object.values(TRIGGERS).map(({ taskId }) => `/api/sync/tasks/${taskId}`)

const TASK_FAILURE = {
  error: 'SYNC_FAILED',
  message: 'Synchronization failed; steps that had already completed were not rolled back'
}

const session = createControlledAccountSession({
  calculationRoutes: ['/api/crafting/profit', '/api/sync/full/recipes'],
  keyedRoutes: ['/api/sync/account'],
  searchQueries: [],
  accountReadRoutes: [
    '/api/system/status', '/api/system/status/details',
    '/api/system/operations/summary', '/api/system/operations/periods', ...TASK_PATHS
  ]
})

const steps = []

function record(name, detail) {
  steps.push({ name, detail })
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

function check(condition, message) {
  if (!condition) throw new Error(message)
}

/** taskId -> { operation, lookups } */
const tasks = new Map()
/** Phase switch: the owner-details discovery answers 200 first, then a non-owner 403. */
let ownerDiscovery = 'owner'

function acceptTask(sendJson, response, { operation, taskId }) {
  tasks.set(taskId, { operation, lookups: 0 })
  const statusUrl = `/api/sync/tasks/${taskId}`
  response.setHeader('Location', statusUrl)
  sendJson(202, { taskId, operation, statusUrl })
}

function answerStatus(sendJson, taskId) {
  const task = tasks.get(taskId)
  if (task === undefined) return sendJson(404, { error: 'TASK_NOT_FOUND', message: `No task ${taskId}.` })
  const script = TASK_SCRIPTS[task.operation]
  const state = script[Math.min(task.lookups, script.length - 1)]
  task.lookups += 1
  const isTerminal = state === 'SUCCEEDED' || state === 'FAILED'
  sendJson(200, {
    taskId,
    operation: task.operation,
    state,
    submittedAt: '2026-09-25T10:00:00Z',
    startedAt: state === 'PENDING' ? null : '2026-09-25T10:00:01Z',
    finishedAt: isTerminal ? '2026-09-25T10:00:09Z' : null,
    failure: state === 'FAILED' ? TASK_FAILURE : null
  })
}

const OWNER_DETAILS = {
  running: false, lastCheckedAt: null, lastChangedAt: null, lastRecipeSyncAt: null, lastGraphRebuildAt: null,
  lastFailure: null, cachedPriceItems: 0, stalePriceItems: 0, newestPriceFetchedAt: null, priceCacheError: null,
  items: null, recipes: null, tradingPostPrices: null,
  accounts: { state: 'AVAILABLE', totalAccounts: 3, activeLast60Minutes: 1 }
}

const OWNER_ACTIVITY = {
  generatedAt: '2026-09-25T10:00:00Z',
  foregroundAccounts: { state: 'UNAVAILABLE', exactCount: null, lowerBound: null },
  databaseSize: { state: 'UNAVAILABLE', bytes: null, measuredAt: null },
  historyAvailable: false, retentionTruncated: false, minutes: [], latestMinute: null,
  oracleConsole: { configured: false, url: null }
}

function answerApi(context) {
  if (session.answer(context)) return
  const { request, response, url, sendJson } = context
  if (request.method === 'POST' && url.pathname in TRIGGERS) return acceptTask(sendJson, response, TRIGGERS[url.pathname])
  if (url.pathname === '/api/system/status') {
    return sendJson(200, { globalHealth: 'OK', priceHealth: 'NOT_RUN', accountLastRefreshedAt: null, accountRefreshScope: null })
  }
  if (url.pathname === '/api/system/status/details') {
    return ownerDiscovery === 'owner'
      ? sendJson(200, OWNER_DETAILS)
      : sendJson(403, { error: 'FORBIDDEN', message: 'Not an owner account.' })
  }
  if (url.pathname === '/api/system/operations/summary') return sendJson(200, OWNER_ACTIVITY)
  if (url.pathname === '/api/system/operations/periods') return sendJson(200, { available: false, periods: [] })
  if (url.pathname.startsWith('/api/sync/tasks/')) return answerStatus(sendJson, url.pathname.slice('/api/sync/tasks/'.length))

  // Crafting Profit is the area navigated to and back from below; it is answered so the check runs
  // on a page without unrelated errors. These two bodies are not what this check is about.
  if (url.pathname === '/api/crafting/selector-options') {
    return sendJson(200, { defaultScopeKind: 'ALL', disciplines: ['Chef'], characterOptionCount: 0, characterOptions: [] })
  }
  if (url.pathname === '/api/crafting/profit') {
    return sendJson(200, {
      scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 },
      settings: { useOwnMats: true, allowBuying: false, maxBuyCopper: 10000, listingSell: false, listingBuy: false, allowDailyCrafts: true },
      rowCount: 0,
      rows: []
    })
  }
  sendJson(404, { error: 'NOT_FOUND', message: url.pathname })
}

function stateSelector(operation) {
  return `[data-test="sync-state-${operation}"]`
}

async function waitForState(page, operation, expected) {
  await page.waitForFunction(
    ([selector, wanted]) => document.querySelector(selector)?.textContent?.trim() === wanted,
    [stateSelector(operation), expected],
    { timeout: TIMEOUT_MS }
  )
}

async function textOf(page, selector) {
  return (await page.$eval(selector, (element) => element.textContent ?? '')).replace(/\s+/g, ' ').trim()
}

async function run() {
  const bundle = requireFreshBundle()

  const browserPath = resolveBrowserPath()
  const stub = await startStubOrigin({ port: PORT, distDir: DIST_DIR, answerApi })
  const requests = stub.requests
  const apiRequestsTo = (prefix) => stub.requestsTo(prefix)
  console.log(`Browser : ${browserPath}`)
  console.log(`Bundle  : ${bundle}`)
  console.log(`Page    : ${stub.origin} (stub backend in this process)\n`)

  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage()
  await session.install(page, stub.origin)
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))

  try {
    // Opened by its own URL, which is also the check that the synchronization area is addressable.
    await page.goto(`${stub.origin}/#/synchronization`, { waitUntil: 'networkidle', timeout: TIMEOUT_MS })
    // Proof the page came from this script's server. Without it, a foreign origin answering here
    // would be driven instead — and its trigger routes could start a real synchronization.
    check(
      stub.servedCount() > 0,
      `The page at ${stub.origin} was not served by this script. Nothing was checked, and the ` +
        'browser may have been pointed at a real backend — stop whatever else is listening there.'
    )
    await page.waitForSelector('[data-test="account-status"]', { timeout: TIMEOUT_MS })
    await page.waitForSelector('[data-test="owner-admin"]', { timeout: TIMEOUT_MS })
    check((await page.$('[data-test="sync-trigger-PRICE_REFRESH_PROFIT"]')) === null, 'A Profit-only price refresh action remained.')
    check((await page.$('[data-test="sync-trigger-GLOBAL_SYNC"]')) === null, 'A retired global synchronization action remained.')
    for (const key of ['FULL_ITEM_CATALOG', 'FULL_RECIPE_CATALOG', 'FULL_TP_CATALOG']) {
      check((await page.$(`[data-test="sync-trigger-${key}"]`)) !== null, `Owner control ${key} was missing.`)
    }
    check(
      apiRequestsTo('/api/sync').filter((request) => request.method === 'POST').length === 0 &&
        apiRequestsTo('/api/prices').length === 0,
      'Loading the page issued a synchronization request.'
    )
    check(
      (await page.title()) === 'System Status · GW2 Crafting Tool · Unofficial fan project',
      `The document title did not name the open area: ${await page.title()}`
    )
    record('System Status opened for an owner with the account card and the three catalog controls, nothing submitted')

    for (const operation of ['ACCOUNT_SYNC', 'FULL_RECIPE_CATALOG']) {
      await page.click(`[data-test="sync-trigger-${operation}"]`)
    }
    // Submission is asynchronous; the button must become (and, below, stay) disabled while unfinished.
    await page.waitForFunction(
      () => document.querySelector('[data-test="sync-trigger-ACCOUNT_SYNC"]')?.disabled === true,
      undefined, { timeout: TIMEOUT_MS }
    ).catch(() => { throw new Error('The account trigger stayed enabled while its task was unfinished.') })
    record('account and owner recipe refresh submitted, the account button disabled while unfinished')

    // While the account task is still unfinished: leaving the area and returning must keep it.
    await page.click('[data-test="nav-crafting"]')
    await page.waitForSelector('[data-test="profit-table"], [data-test="empty"], [data-test="calculate"]', { timeout: TIMEOUT_MS })
    check(
      (await page.$('[data-test="account-status"]')) === null,
      'The System Status cards are still on the Crafting Profit page.'
    )
    const triggersBeforeReturn = apiRequestsTo('/api/sync/account').length
    const activity = await textOf(page, '[data-test="nav-sync-activity"]')
    check(/task[s]? running/.test(activity), `The activity indication was missing: ${activity}`)
    await page.click('[data-test="nav-synchronization"]')
    await page.waitForSelector('[data-test="account-status"]', { timeout: TIMEOUT_MS })
    check(
      apiRequestsTo('/api/sync/account').length === triggersBeforeReturn,
      'Returning to the synchronization area submitted the operation again.'
    )
    const trackedAfterReturn = await textOf(page, '[data-test="sync-diagnostics-ACCOUNT_SYNC"]')
    check(
      trackedAfterReturn.includes('account-sync-1'),
      `The tracked task was lost by navigating: ${trackedAfterReturn}`
    )
    record('tracking survived leaving and returning', `activity indication read "${activity}"`)

    await waitForState(page, 'ACCOUNT_SYNC', 'Completed')
    record('account task tracked to completion', `${tasks.get('account-sync-1').lookups} status lookups`)

    await waitForState(page, 'FULL_RECIPE_CATALOG', 'Failed')
    const failureText = await textOf(page, '[data-test="sync-task-failure-FULL_RECIPE_CATALOG"]')
    check(failureText.includes('SYNC_FAILED'), `Failure text lacks the backend code: ${failureText}`)
    check(failureText.includes('were not rolled back'), `Failure text lacks the no-rollback wording: ${failureText}`)
    check(
      failureText.indexOf('were not rolled back') < failureText.indexOf('SYNC_FAILED'),
      `The backend code is presented ahead of the meaningful failure text: ${failureText}`
    )
    record('failed owner task reported from an HTTP 200 status', failureText)

    const lookupsWhenTerminal = apiRequestsTo('/api/sync/tasks/').length
    await page.waitForTimeout(6_000)
    check(
      apiRequestsTo('/api/sync/tasks/').length === lookupsWhenTerminal,
      'Polling continued after every tracked task reached a terminal or unresolvable state.'
    )
    record('polling stopped', `${lookupsWhenTerminal} status lookups in total, none afterwards`)

    const triggers = requests.filter((request) => request.method === 'POST' && request.path.startsWith('/api/sync/'))
    check(triggers.length === 2, `Expected exactly two triggers, saw ${triggers.length}.`)
    const asText = triggers.map((trigger) => `${trigger.path} ${trigger.body}`).join(' | ')
    check(asText.includes('/api/sync/account {}'), `Account trigger body was not empty: ${asText}`)
    check(asText.includes('/api/sync/full/recipes {}'), `Recipe trigger body was not empty: ${asText}`)
    record('each action sent exactly once, with the documented empty body', asText)

    // A non-owner account: the backend denies owner discovery, so no owner control is offered or sent.
    ownerDiscovery = 'denied'
    const syncPosts = () => requests.filter((request) => request.method === 'POST' && request.path.startsWith('/api/sync/')).length
    const postsBeforeReload = syncPosts()
    await page.reload({ waitUntil: 'networkidle', timeout: TIMEOUT_MS })
    await page.waitForSelector('[data-test="account-status"]', { timeout: TIMEOUT_MS })
    await page.waitForSelector('[data-test="shared-service-line"]', { timeout: TIMEOUT_MS })
    check((await page.$('[data-test="owner-admin"]')) === null, 'A non-owner account was shown owner administration.')
    check((await page.$('[data-test="sync-trigger-FULL_RECIPE_CATALOG"]')) === null, 'A non-owner account was offered a catalog refresh.')
    check((await page.$('[data-test="sync-trigger-ACCOUNT_SYNC"]')) !== null, 'The ordinary account refresh disappeared for a non-owner.')
    check(
      syncPosts() === postsBeforeReload,
      'Opening the page as a non-owner submitted a synchronization request.'
    )
    record('non-owner reload shows the account refresh and shared status line, with no owner control')

    check(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`)
    record('synthetic account proofs, strict routes and methods, no off-origin request', session.verify())

    console.log(`\nSynchronization browser check PASSED (${steps.length} steps).`)
    console.log('Every backend answer was produced by this script; no real synchronization ran.')
  } finally {
    await browser.close()
    await stub.close()
  }
}

run().catch((error) => {
  console.error(`\nSynchronization browser check FAILED after ${steps.length} step(s): ${error.message}`)
  process.exitCode = 1
})
