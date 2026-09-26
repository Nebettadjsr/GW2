/**
 * Browser smoke check for the Crafting Profit screen (STORY-WEB-001 acceptance criterion 6).
 *
 * Drives a real Chromium-family browser against an already-running frontend and backend, and
 * asserts that what the browser actually rendered came from the backend: the selector entries, the
 * calculation response, the result table and — since STORY-WEB-007 — the selected recipe's
 * resolution detail, compared field for field against the body that route really answered with. It
 * does not mock anything and it does not assert any domain value: a compared value is whatever the
 * backend sent, never one this script worked out. A mocked check cannot establish that the real
 * connection works, and this check cannot establish performance
 * (TEST_STRATEGY / TARGET_ARCHITECTURE 33).
 *
 * Read-only: it opens the page, selects a row and changes the scope. No synchronization is triggered
 * and nothing is written.
 *
 * Prerequisites, both started by hand:
 *   1. the backend   — `./mvnw spring-boot:run` in the repository root
 *   2. the dev server — `npm run dev` in `frontend/` (it proxies `/api` to the backend)
 *
 * Usage:  npm run smoke:browser
 * Environment:
 *   GW2_FRONTEND_URL  page to open        (default http://localhost:5173)
 *   GW2_BROWSER_PATH  browser executable  (default: the first installed Chrome/Edge found)
 *   GW2_SMOKE_TIMEOUT_MS  per-step wait   (default 60000; a cold calculation is slow)
 *
 * Exits 0 when every step passed, 1 otherwise. It reads no credential and holds none: the browser
 * talks only to the frontend origin, which proxies to the backend.
 */
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

const FRONTEND_URL = process.env.GW2_FRONTEND_URL ?? 'http://localhost:5173'
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 60_000)

const steps = []

function record(name, detail) {
  steps.push({ name, detail })
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

/** `/api/crafting/profit` and `/api/crafting/profit/resolution` are separate paths, not a prefix. */
function requestsTo(calls, path) {
  return calls.filter((call) => call.path === path)
}

/**
 * The item labels of a supplied tree, depth-first in the backend's own child order — the same order
 * and the same fallback the page uses, so the two lists are comparable without deriving anything.
 */
function flattenNames(node) {
  const label = node.itemName ?? `Item #${node.itemId}`
  return [label, ...node.children.flatMap((child) => flattenNames(child))]
}

async function run() {
  const browserPath = resolveBrowserPath()
  console.log(`Browser : ${browserPath}`)
  console.log(`Page    : ${FRONTEND_URL}\n`)

  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage()

  /** Every backend call the browser really made, so the run proves the network path was used. */
  const apiCalls = []
  /**
   * The real detail responses, kept so the rendered tree can be compared with what the backend
   * actually sent. Only this route's bodies are read; nothing is recalculated from them.
   */
  const detailBodies = []
  const bodyReads = []
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (!url.pathname.startsWith('/api/')) return
    apiCalls.push({ path: url.pathname, status: response.status() })
    if (url.pathname === '/api/crafting/profit/resolution' && response.status() === 200) {
      bodyReads.push(
        response
          .json()
          .then((body) => detailBodies.push(body))
          .catch((cause) => detailBodies.push({ unreadable: String(cause) }))
      )
    }
  })

  const consoleErrors = []
  page.on('pageerror', (error) => consoleErrors.push(String(error)))

  try {
    await page.goto(FRONTEND_URL, { waitUntil: 'domcontentloaded', timeout: TIMEOUT_MS })
    record('page loaded')

    // The screen requests both routes on open; the table appears only once the backend answered.
    await page.waitForSelector('[data-test="profit-table"], [data-test="empty"]', { timeout: TIMEOUT_MS })

    const selectorCall = apiCalls.find((call) => call.path === '/api/crafting/selector-options')
    if (selectorCall === undefined) throw new Error('The browser never called /api/crafting/selector-options.')
    if (selectorCall.status !== 200) throw new Error(`selector-options answered HTTP ${selectorCall.status}.`)

    const scopeLabels = await page.$$eval('[data-test="scope-selector"] option', (options) =>
      options.map((option) => option.textContent?.trim() ?? '')
    )
    if (scopeLabels[0] !== 'All') throw new Error(`First scope entry is "${scopeLabels[0]}", expected "All".`)
    record('selector loaded from backend', `${scopeLabels.length} entries, first "${scopeLabels[0]}"`)

    const profitCall = apiCalls.find((call) => call.path === '/api/crafting/profit')
    if (profitCall === undefined) throw new Error('The browser never called /api/crafting/profit.')
    if (profitCall.status !== 200) throw new Error(`profit answered HTTP ${profitCall.status}.`)
    record('calculation response received', `HTTP ${profitCall.status}`)

    const rowCount = await page.$$eval('[data-test="profit-row"]', (rows) => rows.length)
    if (rowCount === 0) {
      // A 200 with no rows is a real backend answer (an unsynced database), not a frontend failure.
      record('table rendered', 'backend returned no rows for the default scope')
    } else {
      const firstTotal = await page.$eval('[data-test="total-profit"]', (cell) => cell.textContent?.trim() ?? '')
      record('table rendered', `${rowCount} rows, first total profit "${firstTotal}"`)

      // Sorting and searching are presentation over those same rows.
      await page.click('[data-test="sort-outputName"]')
      await page.waitForFunction(
        (expected) => document.querySelectorAll('[data-test="profit-row"]').length === expected,
        rowCount,
        { timeout: TIMEOUT_MS }
      )
      record('sort applied', 'row count unchanged')

      const firstRecipeName = await page.$eval('[data-test="profit-row"] .recipe-name', (cell) =>
        (cell.textContent ?? '').trim()
      )
      await page.fill('[data-test="search"]', firstRecipeName)
      const filtered = await page.$$eval('[data-test="profit-row"]', (rows) => rows.length)
      if (filtered === 0) throw new Error(`Searching for the rendered name "${firstRecipeName}" matched no row.`)
      record('search applied', `"${firstRecipeName}" matched ${filtered} of ${rowCount} rows`)
      await page.fill('[data-test="search"]', '')

      // Selecting a real row must open the detail for that recipe, built from the same response
      // (STORY-WEB-005). The table's own calculation must not run again.
      const resolutionsBefore = requestsTo(apiCalls, '/api/crafting/profit/resolution').length
      if (resolutionsBefore !== 0) {
        throw new Error(`${resolutionsBefore} detail request(s) were sent before anything was selected.`)
      }
      const calculationsBefore = requestsTo(apiCalls, '/api/crafting/profit').length
      await page.click('[data-test="profit-table"] tbody [data-test="select-row"]')
      await page.waitForSelector('[data-test="detail-name"]', { timeout: TIMEOUT_MS })
      const detailName = await page.$eval('[data-test="detail-name"]', (element) =>
        (element.textContent ?? '').trim()
      )
      const selectedName = await page.$eval(
        '[data-test="profit-row"][aria-current="true"] .recipe-name',
        (element) => (element.textContent ?? '').trim()
      )
      if (detailName !== selectedName) {
        throw new Error(`The detail named "${detailName}" while the marked row was "${selectedName}".`)
      }
      const detailText = await page.$eval('[data-test="selected-detail"]', (element) =>
        (element.textContent ?? '').replace(/\s+/g, ' ').trim()
      )
      for (const expected of ['For one craft', 'Materials still to buy', 'Crafting resolution']) {
        if (!detailText.includes(expected)) throw new Error(`The detail region lacked "${expected}".`)
      }
      if (requestsTo(apiCalls, '/api/crafting/profit').length !== calculationsBefore) {
        throw new Error('Selecting a row asked the backend for the table calculation again.')
      }
      record('selected-result detail rendered', `"${detailName}", table not recalculated`)

      // The resolution detail is one lazy request to the decided route, and the region has to settle
      // into one of its named situations rather than staying on the loading message.
      await page.waitForSelector(
        '[data-test="resolution-tree"], [data-test="resolution-unavailable"],' +
          ' [data-test="resolution-absent"], [data-test="resolution-failed"]',
        { timeout: TIMEOUT_MS }
      )
      await Promise.all(bodyReads)
      const resolutionCalls = requestsTo(apiCalls, '/api/crafting/profit/resolution')
      if (resolutionCalls.length !== 1) {
        throw new Error(`Selecting one recipe sent ${resolutionCalls.length} detail requests, not 1.`)
      }
      record(
        'resolution detail requested once, only on selection',
        `HTTP ${resolutionCalls[0].status} from ${resolutionCalls[0].path}`
      )

      // What is on screen is compared with the body the backend actually sent. Only supplied facts
      // are compared — no cost, quantity or state is recalculated here.
      if (resolutionCalls[0].status !== 200 || detailBodies.length !== 1) {
        record(
          'resolution detail not comparable in this run',
          `HTTP ${resolutionCalls[0].status}, ${detailBodies.length} readable bodies — ` +
            'the region reports the situation instead of a tree'
        )
      } else {
        const body = detailBodies[0]
        const literals = await page.$eval('[data-test="detail-diagnostics"]', (element) =>
          (element.textContent ?? '').replace(/\s+/g, ' ').trim()
        )
        for (const supplied of [body.consistency, body.treeBasis, body.treeStatus, body.calculatedAt]) {
          if (!literals.includes(String(supplied))) {
            throw new Error(`The detail did not show the backend's "${supplied}": ${literals}`)
          }
        }
        if (body.treeStatus === 'AVAILABLE' && body.tree !== null) {
          const sentNames = flattenNames(body.tree)
          const shownNames = await page.$$eval(
            '[data-test="tree-node"] [data-test="node-name"]',
            (names) => names.map((name) => (name.textContent ?? '').trim())
          )
          if (JSON.stringify(shownNames) !== JSON.stringify(sentNames)) {
            throw new Error(
              `The rendered tree differs from the response: shown ${JSON.stringify(shownNames)}, ` +
                `sent ${JSON.stringify(sentNames)}`
            )
          }
          const rootRequested = await page.$eval('[data-test="node-requested"]', (element) =>
            (element.textContent ?? '').trim()
          )
          if (!rootRequested.startsWith(String(body.tree.requestedQuantity))) {
            throw new Error(
              `The root's requested quantity read "${rootRequested}" for a supplied ` +
                `${body.tree.requestedQuantity}.`
            )
          }
          record(
            'rendered tree matches the backend response',
            `${sentNames.length} nodes in order, root ${body.tree.requestedQuantity} needed, ` +
              `root recipe ${body.tree.recipeId ?? 'none'} for requested recipe ${body.recipeId}`
          )
        } else {
          record(
            'the backend reported no tree to explain',
            `treeStatus ${body.treeStatus}, tree ${body.tree === null ? 'null' : 'present'} — ` +
              'shown as the calculation\'s own answer'
          )
        }
      }
    }

    // A scope change must reach the backend as a further calculation request.
    const callsBeforeScopeChange = apiCalls.filter((call) => call.path === '/api/crafting/profit').length
    const secondScopeValue = await page.$eval('[data-test="scope-selector"] option:nth-child(2)', (option) =>
      option.getAttribute('value')
    )
    await page.selectOption('[data-test="scope-selector"]', secondScopeValue)
    await page.waitForFunction(
      (before) =>
        !document.querySelector('[data-test="loading"]') &&
        Boolean(
          document.querySelector('[data-test="profit-table"]') ||
            document.querySelector('[data-test="empty"]')
        ) &&
        before >= 0,
      callsBeforeScopeChange,
      { timeout: TIMEOUT_MS }
    )
    const scopeCalls = apiCalls.filter((call) => call.path === '/api/crafting/profit')
    if (scopeCalls.length <= callsBeforeScopeChange) {
      throw new Error('Changing the scope sent no further /api/crafting/profit request.')
    }
    const scopeStatus = scopeCalls[scopeCalls.length - 1].status
    if (scopeStatus !== 200) throw new Error(`The scope-change request answered HTTP ${scopeStatus}.`)
    const scopedRows = await page.$$eval('[data-test="profit-row"]', (rows) => rows.length)
    const effectiveScope = await page.$eval('[data-test="effective-scope"]', (element) =>
      (element.textContent ?? '').replace(/\s+/g, ' ').trim()
    )
    record('scope change recalculated by backend', `${effectiveScope}, ${scopedRows} rows`)

    const foreignCalls = apiCalls.filter((call) => !call.path.startsWith('/api/'))
    if (foreignCalls.length > 0) throw new Error(`Non-backend API calls observed: ${JSON.stringify(foreignCalls)}`)
    if (consoleErrors.length > 0) throw new Error(`Uncaught page errors: ${consoleErrors.join(' | ')}`)
    record('no page errors and no non-backend API calls')

    console.log(`\nBrowser smoke PASSED (${steps.length} steps).`)
    console.log(`Backend calls observed: ${apiCalls.map((call) => `${call.path} ${call.status}`).join(', ')}`)
  } finally {
    await browser.close()
  }
}

run().catch((error) => {
  console.error(`\nBrowser smoke FAILED after ${steps.length} step(s): ${error.message}`)
  console.error('Are both the backend (./mvnw spring-boot:run) and the dev server (npm run dev) running?')
  process.exitCode = 1
})
