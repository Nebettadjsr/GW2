/**
 * Real-browser check for the synchronization area (STORY-WEB-002 semantics, STORY-WEB-004 structure).
 *
 * Runs the built frontend against a *controlled* API boundary: `scripts/stubOrigin.mjs` serves
 * `dist/` and this script answers the two admin trigger routes and the shared status route itself, with
 * scripted task lifecycles. Nothing real is synchronized — no GW2 API call, no database write and no
 * user data is touched — so this establishes browser interaction and task-state presentation, and
 * nothing about a real synchronization or about performance (TEST_STRATEGY / TARGET_ARCHITECTURE 33).
 *
 * The complementary `smoke:browser` check is the one that talks to the real backend; it covers the
 * read-only Crafting Profit path, where a live call mutates nothing.
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
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startStubOrigin } from './stubOrigin.mjs'

const PORT = Number(process.env.GW2_SYNC_SMOKE_PORT ?? 5174)
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
const DIST_DIR = fileURLToPath(new URL('../dist/', import.meta.url))

/**
 * The state each operation's task reports, one entry per status lookup; the last entry repeats.
 */
const TASK_SCRIPTS = {
  ACCOUNT_SYNC: ['PENDING', 'RUNNING', 'SUCCEEDED'],
  GLOBAL_SYNC: ['PENDING', 'FAILED']
}

const TASK_FAILURE = {
  error: 'SYNC_FAILED',
  message: 'Synchronization failed; steps that had already completed were not rolled back'
}

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

function sendJson(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json' })
  response.end(JSON.stringify(body))
}

function acceptTask(response, operation) {
  const taskId = `${operation.toLowerCase()}-${tasks.size + 1}`
  tasks.set(taskId, { operation, lookups: 0 })
  const statusUrl = `/api/sync/tasks/${taskId}`
  response.setHeader('Location', statusUrl)
  sendJson(response, 202, { taskId, operation, statusUrl })
}

function answerStatus(response, taskId) {
  const task = tasks.get(taskId)
  if (task === undefined) return sendJson(response, 404, { error: 'TASK_NOT_FOUND', message: `No task ${taskId}.` })

  const script = TASK_SCRIPTS[task.operation]
  if (script === null) {
    return sendJson(response, 404, { error: 'TASK_NOT_FOUND', message: `No task ${taskId} is known.` })
  }

  const state = script[Math.min(task.lookups, script.length - 1)]
  task.lookups += 1
  const isTerminal = state === 'SUCCEEDED' || state === 'FAILED'
  sendJson(response, 200, {
    taskId,
    operation: task.operation,
    state,
    submittedAt: '2026-09-25T10:00:00Z',
    startedAt: state === 'PENDING' ? null : '2026-09-25T10:00:01Z',
    finishedAt: isTerminal ? '2026-09-25T10:00:09Z' : null,
    failure: state === 'FAILED' ? TASK_FAILURE : null
  })
}

function answerApi({ request, response, url, body }) {
  if (request.method === 'POST' && url.pathname === '/api/sync/account') return acceptTask(response, 'ACCOUNT_SYNC')
  if (request.method === 'POST' && url.pathname === '/api/sync/global') return acceptTask(response, 'GLOBAL_SYNC')
  if (request.method === 'GET' && url.pathname === '/api/system/status') return sendJson(response, 200, {
    running: false,
    lastCheckedAt: null,
    lastChangedAt: null,
    lastRecipeSyncAt: null,
    lastGraphRebuildAt: null,
    lastFailure: null,
    accountLastRefreshedAt: null,
    accountRefreshScope: null,
    cachedPriceItems: 0,
    stalePriceItems: 0,
    newestPriceFetchedAt: null,
    priceCacheError: null
  })
  if (request.method === 'GET' && url.pathname.startsWith('/api/sync/tasks/')) {
    return answerStatus(response, url.pathname.slice('/api/sync/tasks/'.length))
  }

  // Crafting Profit is the area navigated to and back from below; it is answered so the check runs
  // on a page without unrelated errors. These two bodies are not what this check is about.
  if (url.pathname === '/api/crafting/selector-options') {
    return sendJson(response, 200, {
      defaultScopeKind: 'ALL',
      disciplines: ['Chef'],
      characterOptionCount: 0,
      characterOptions: []
    })
  }
  if (url.pathname === '/api/crafting/profit') {
    return sendJson(response, 200, {
      scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 },
      settings: {
        useOwnMats: true,
        allowBuying: false,
        maxBuyCopper: 10000,
        listingSell: false,
        listingBuy: false,
        allowDailyCrafts: true,
      },
      rowCount: 0,
      rows: []
    })
  }

  sendJson(response, 404, { error: 'NOT_FOUND', message: url.pathname })
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
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))
  const browserCalls = []
  page.on('response', (response) => browserCalls.push(new URL(response.url()).pathname))

  try {
    // Opened by its own URL, which is also the check that the synchronization area is addressable.
    await page.goto(`${stub.origin}/#/synchronization`, {
      waitUntil: 'networkidle',
      timeout: TIMEOUT_MS
    })
    // Proof the page came from this script's server. Without it, a foreign origin answering here
    // would be driven instead — and its trigger routes could start a real synchronization.
    check(
      stub.servedCount() > 0,
      `The page at ${stub.origin} was not served by this script. Nothing was checked, and the ` +
        'browser may have been pointed at a real backend — stop whatever else is listening there.'
    )
    await page.waitForSelector('[data-test="account-status"]', { timeout: TIMEOUT_MS })
    check((await page.$('[data-test="price-cache-status"]')) !== null, 'The shared price-cache card was missing.')
    check((await page.$('[data-test="sync-trigger-PRICE_REFRESH_PROFIT"]')) === null, 'A Profit-only price refresh action remained.')
    check((await page.$('[data-test="sync-trigger-PRICE_REFRESH_DISCOVERY"]')) === null, 'A Discovery-only price refresh action remained.')
    check(
      apiRequestsTo('/api/sync').length === 0 && apiRequestsTo('/api/prices').length === 0,
      'Loading the page issued a synchronization request.'
    )
    check(
      (await page.title()) === 'System Status · GW2 Crafting Tool',
      `The document title did not name the open area: ${await page.title()}`
    )
    record('System Status opened with health cards and no price-refresh buttons')

    for (const operation of ['ACCOUNT_SYNC', 'GLOBAL_SYNC']) {
      await page.click(`[data-test="sync-trigger-${operation}"]`)
    }
    const disabled = await page.$eval('[data-test="sync-trigger-ACCOUNT_SYNC"]', (button) => button.disabled)
    check(disabled, 'The account trigger stayed enabled while its task was unfinished.')
    record('two useful admin actions submitted, each button disabled while unfinished')

    // While the account task is still unfinished: leaving the area and returning must keep it.
    await page.click('[data-test="nav-crafting"]')
    await page.waitForSelector('[data-test="profit-table"], [data-test="empty"]', {
      timeout: TIMEOUT_MS
    })
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
      trackedAfterReturn.includes('account_sync-1'),
      `The tracked task was lost by navigating: ${trackedAfterReturn}`
    )
    record('tracking survived leaving and returning', `activity indication read "${activity}"`)

    await waitForState(page, 'ACCOUNT_SYNC', 'Completed')
    record('account task tracked to completion', `${tasks.get('account_sync-1').lookups} status lookups`)

    await waitForState(page, 'GLOBAL_SYNC', 'Failed')
    const failureText = await textOf(page, '[data-test="sync-task-failure-GLOBAL_SYNC"]')
    check(failureText.includes('SYNC_FAILED'), `Failure text lacks the backend code: ${failureText}`)
    check(failureText.includes('were not rolled back'), `Failure text lacks the no-rollback wording: ${failureText}`)
    check(
      failureText.indexOf('were not rolled back') < failureText.indexOf('SYNC_FAILED'),
      `The backend code is presented ahead of the meaningful failure text: ${failureText}`
    )
    record('failed task reported from an HTTP 200 status', failureText)

    const lookupsWhenTerminal = apiRequestsTo('/api/sync/tasks/').length
    await page.waitForTimeout(6_000)
    check(
      apiRequestsTo('/api/sync/tasks/').length === lookupsWhenTerminal,
      'Polling continued after every tracked task reached a terminal or unresolvable state.'
    )
    record('polling stopped', `${lookupsWhenTerminal} status lookups in total, none afterwards`)

    const triggers = requests.filter(
      (request) =>
        request.method === 'POST' &&
        request.path.startsWith('/api/sync/')
    )
    check(triggers.length === 2, `Expected exactly two admin triggers, saw ${triggers.length}.`)
    const asText = triggers.map((trigger) => `${trigger.path} ${trigger.body}`).join(' | ')
    check(asText.includes('/api/sync/account {}'), `Account trigger body was not empty: ${asText}`)
    check(asText.includes('/api/sync/global {}'), `Global trigger body was not empty: ${asText}`)
    record('each admin action sent exactly once, with the documented body', asText)

    const foreignCalls = browserCalls.filter((path) => path !== '/' && !path.startsWith('/api/') && !path.startsWith('/assets/'))
    check(foreignCalls.length === 0, `Calls outside the backend API and page assets: ${foreignCalls.join(', ')}`)
    check(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`)
    record('no page errors and no non-backend calls')

    console.log(`\nSynchronization browser check PASSED (${steps.length} steps).`)
    console.log('Every backend answer was produced by this script; no real synchronization ran.')
  } finally {
    await browser.close()
    stub.close()
  }
}

run().catch((error) => {
  console.error(`\nSynchronization browser check FAILED after ${steps.length} step(s): ${error.message}`)
  process.exitCode = 1
})
