/**
 * Real-browser check of the Crafting Profit provisional-refresh lifecycle (STORY-WEB-034, UD-015).
 *
 * `smoke:profit` covers the information hierarchy against inputs the backend reports as current.
 * This script covers the states that one cannot reach: a cached result served while a refresh runs,
 * the stale indicator and its explanation, the controls surviving an automatic replacement, a detail
 * answer computed from newer inputs than the row it was asked about, and bounded termination with a
 * retry. Each case is driven by *scripted* responses from this process, so the sequence is
 * deterministic and nothing real is synchronized.
 *
 * Runs the built frontend against a controlled API boundary (`scripts/stubOrigin.mjs`): no backend,
 * database or GW2 API is involved. It therefore evidences the lifecycle's presentation and
 * interaction — never real data and never refresh timing, which is STORY-PERF-004's subject.
 *
 * The clock is the browser's own here, so the five-minute recovery budget is deliberately *not*
 * asserted: that bound is driven by a controlled clock in
 * `src/crafting/__tests__/CraftingProfitRefreshWorkflow.spec.ts`. What this script asserts about
 * termination is the backend-reported failure path, which needs no waiting at all.
 *
 * Usage:  npm run build && npm run smoke:profit:refresh
 * Environment:
 *   GW2_PROFIT_REFRESH_SMOKE_PORT  port for the stub origin  (default 5177)
 *   GW2_BROWSER_PATH               browser executable        (default: first installed Chrome/Edge)
 *   GW2_SMOKE_TIMEOUT_MS           per-step wait             (default 30000)
 *
 * Exits 0 when every step passed, 1 otherwise.
 */
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { requireFreshBundle } from './bundleFreshness.mjs'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startStubOrigin } from './stubOrigin.mjs'

const PORT = Number(process.env.GW2_PROFIT_REFRESH_SMOKE_PORT ?? 5177)
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
const DIST_DIR = fileURLToPath(new URL('../dist/', import.meta.url))
const VIEWPORT = { width: 1440, height: 900 }

const steps = []

function record(name, detail) {
  steps.push({ name, detail })
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

function check(condition, message) {
  if (!condition) throw new Error(message)
}

/* ------------------------------------------------------------------ scripted responses --------- */

const ACCOUNT_SOURCES = ['CHARACTERS', 'INVENTORY', 'MATERIALS', 'RECIPES']

function freshSource(source) {
  return { source, fetchedAt: '2026-10-13T08:59:00Z', state: 'FRESH' }
}

function coveredPrices() {
  return {
    requiredItems: 3_205,
    freshItems: 3_205,
    staleItems: 0,
    missingItems: 0,
    oldestFetchedAt: '2026-10-13T08:55:00Z',
    newestFetchedAt: '2026-10-13T08:59:00Z',
    covered: true
  }
}

function envelope(overrides) {
  return {
    requestId: 'smoke-request',
    calculationId: 'generation-current',
    evaluatedAt: '2026-10-13T09:00:00Z',
    provisional: false,
    resultAvailable: true,
    accountSources: ACCOUNT_SOURCES.map(freshSource),
    tradingPost: coveredPrices(),
    outstandingWork: [],
    failures: [],
    ...overrides
  }
}

/** Inputs the backend does not consider current, with one account task and a Trading Post gap. */
function provisionalEnvelope(taskId, calculationId) {
  return envelope({
    calculationId,
    provisional: true,
    accountSources: [
      { source: 'CHARACTERS', fetchedAt: '2026-10-12T08:00:00Z', state: 'STALE' },
      freshSource('INVENTORY'),
      freshSource('MATERIALS'),
      freshSource('RECIPES')
    ],
    tradingPost: {
      requiredItems: 3_205,
      freshItems: 3_000,
      staleItems: 180,
      missingItems: 25,
      oldestFetchedAt: '2026-10-12T07:00:00Z',
      newestFetchedAt: '2026-10-13T08:59:00Z',
      covered: false
    },
    outstandingWork: [
      {
        kind: 'ACCOUNT',
        sources: ['CHARACTERS'],
        taskId,
        taskState: 'RUNNING',
        taskStatusUrl: `/api/sync/tasks/${taskId}`,
        reuse: 'ADMITTED'
      },
      {
        kind: 'TRADING_POST',
        sources: [],
        taskId: 'task-prices-1',
        taskState: 'RUNNING',
        taskStatusUrl: '/api/sync/tasks/task-prices-1',
        reuse: 'ADMITTED'
      }
    ]
  })
}

/** Recovery the backend itself reports as finished unsuccessfully: nothing left to poll. */
function failedEnvelope() {
  return envelope({
    calculationId: 'generation-stale',
    provisional: true,
    accountSources: [
      { source: 'CHARACTERS', fetchedAt: '2026-10-12T08:00:00Z', state: 'STALE' },
      freshSource('INVENTORY'),
      freshSource('MATERIALS'),
      freshSource('RECIPES')
    ],
    outstandingWork: [],
    failures: [
      {
        kind: 'ACCOUNT',
        taskId: 'task-account-1',
        taskStatusUrl: '/api/sync/tasks/task-account-1',
        code: 'ACCOUNT_REFRESH_FAILED',
        message: 'The account refresh task ended without completing its sources.',
        retryable: true
      }
    ]
  })
}

/**
 * Two rows whose totals are deliberately not the product of their parts, so a page that worked a
 * money value out itself would disagree with what this process sent. The cached and refreshed
 * generations differ in every displayed figure, which is what makes a replacement observable.
 */
function row(recipeId, outputName, overrides) {
  return {
    recipeId,
    outputItemId: 1_000 + recipeId,
    outputName,
    outputCount: 1,
    disciplines: 'Armorsmith,Weaponsmith',
    minRating: 400,
    resultAvailable: true,
    craftableCount: 7,
    profitCopper: 12_345,
    // Not 7 x 12 345 and not 7 x 111 110: both totals are this process's own.
    totalProfitCopper: 148_140,
    totalSellValueCopper: 1_481_401,
    buyCostCopper: 98_765,
    matsSellValueCopper: 4_321,
    totalMatsSellValueCopper: 51_852,
    revenueCopper: 111_110,
    blockedReason: 'NONE',
    outputPrice: { buyUnitCopper: 120_000, sellUnitCopper: 130_000 },
    missingToBuy: [],
    missingToBuyOne: [],
    iconUrl: null,
    ...overrides
  }
}

const CACHED_ROWS = [
  row(1, 'Deldrimor Steel Ingot'),
  row(2, 'Spiritwood Plank', { totalProfitCopper: 22_222, totalSellValueCopper: 333_333 })
]

const REFRESHED_ROWS = [
  row(1, 'Deldrimor Steel Ingot', {
    craftableCount: 9,
    totalProfitCopper: 404_040,
    totalSellValueCopper: 909_090
  }),
  row(2, 'Spiritwood Plank', { totalProfitCopper: 22_222, totalSellValueCopper: 333_333 })
]

/** The row a detail answer carries when it was computed from inputs newer than the table's. */
const RECALCULATED_DETAIL_ROW = row(1, 'Deldrimor Steel Ingot', {
  craftableCount: 8,
  totalProfitCopper: 777_777,
  totalSellValueCopper: 888_888
})

const CACHED_PROFIT = '+14g 81s 40c'
const REFRESHED_PROFIT = '+40g 40s 40c'
const RECALCULATED_DETAIL_PROFIT = '+77g 77s 77c'

function treeFor(recipeId, itemName) {
  const node = (itemId, name, overrides) => ({
    itemId,
    itemName: name,
    requestedQuantity: 1,
    inventoryQuantity: 0,
    craftedQuantity: 0,
    boughtQuantity: 0,
    missingQuantity: 0,
    recipeId: null,
    craftCount: 0,
    producedQuantity: 0,
    characterName: null,
    methods: [],
    states: [],
    blockedReasons: [],
    cashCostCopper: 0,
    opportunityCostCopper: 0,
    effectiveCostCopper: 0,
    children: [],
    iconUrl: null,
    ...overrides
  })
  return node(1_000 + recipeId, itemName, {
    craftedQuantity: 1,
    recipeId,
    craftCount: 1,
    producedQuantity: 3,
    methods: ['CRAFT'],
    cashCostCopper: 98_765,
    effectiveCostCopper: 103_086,
    children: [node(19_700, 'Mithril Ore', { requestedQuantity: 60, boughtQuantity: 60, methods: ['BUY'] })]
  })
}

/**
 * The lifecycle the stub plays out, chosen by the running phase.
 *
 * `provisional` answers every calculation with the cached rows and an unfinished envelope, and
 * reports the advertised tasks as still running — which is what keeps the indicator up while the
 * page is inspected. `replace` lets the next poll succeed so the page reaches its final answer.
 * `failing` answers with the backend's own bounded failure item and no task to poll.
 */
let phase = 'provisional'
let taskStatusReads = 0
let calculations = 0
let detailConsistency = 'RECALCULATED_FOR_CHANGED_INPUTS'

function rowsForPhase() {
  return phase === 'replace' && calculations > 1 ? REFRESHED_ROWS : CACHED_ROWS
}

function envelopeForPhase() {
  if (phase === 'failing') return failedEnvelope()
  if (phase === 'replace' && calculations > 1) return envelope({ calculationId: 'generation-fresh' })
  return provisionalEnvelope('task-account-1', 'generation-stale')
}

function answerApi({ url, body, sendJson }) {
  if (url.pathname === '/api/crafting/selector-options') {
    return sendJson(200, {
      defaultScopeKind: 'ALL',
      disciplines: ['Armorsmith', 'Chef', 'Weaponsmith'],
      characterOptionCount: 0,
      characterOptions: []
    })
  }

  if (url.pathname === '/api/crafting/profit') {
    calculations += 1
    const request = body === '' ? {} : JSON.parse(body)
    const scope = request.scope ?? { kind: 'ALL' }
    const rows = rowsForPhase()
    return sendJson(200, {
      status: 'CALCULATED',
      freshness: envelopeForPhase(),
      scope: {
        kind: scope.kind,
        discipline: scope.discipline ?? null,
        characterName: scope.characterName ?? null,
        rating: scope.rating ?? 0
      },
      settings: request.settings ?? {
        useOwnMats: true,
        allowBuying: true,
        maxBuyCopper: 250_000,
        listingSell: false,
        listingBuy: false,
        allowDailyCrafts: true
      },
      rowCount: rows.length,
      rows
    })
  }

  if (url.pathname.startsWith('/api/sync/tasks/')) {
    taskStatusReads += 1
    const taskId = url.pathname.split('/').at(-1)
    const state = phase === 'replace' ? 'SUCCEEDED' : 'RUNNING'
    return sendJson(200, {
      taskId,
      operation: taskId.includes('prices') ? 'PRICE_REFRESH_PROFIT' : 'ACCOUNT_SYNC',
      state,
      submittedAt: '2026-10-13T09:00:00Z',
      startedAt: '2026-10-13T09:00:01Z',
      finishedAt: state === 'SUCCEEDED' ? '2026-10-13T09:00:09Z' : null,
      failure: null
    })
  }

  if (url.pathname === '/api/crafting/profit/resolution') {
    const request = JSON.parse(body)
    const known = [...CACHED_ROWS, ...REFRESHED_ROWS].find(
      (candidate) => candidate.recipeId === request.recipeId
    )
    if (known === undefined) {
      return sendJson(404, {
        error: 'RECIPE_NOT_IN_CALCULATION',
        message: `Recipe ${request.recipeId} is not in this calculation.`
      })
    }
    const scope = request.calculation?.scope ?? { kind: 'ALL' }
    const recalculated = detailConsistency === 'RECALCULATED_FOR_CHANGED_INPUTS'
    const answeredRow = recalculated ? RECALCULATED_DETAIL_ROW : known
    return sendJson(200, {
      recipeId: request.recipeId,
      status: 'CALCULATED',
      consistency: detailConsistency,
      freshness: envelope({ calculationId: recalculated ? 'generation-newer' : 'generation-stale' }),
      calculation: {
        scope: {
          kind: scope.kind,
          discipline: scope.discipline ?? null,
          characterName: scope.characterName ?? null,
          rating: scope.rating ?? 0
        },
        settings: request.calculation?.settings ?? null
      },
      calculatedAt: '2026-10-13T09:00:05Z',
      row: answeredRow,
      treeStatus: 'AVAILABLE',
      treeBasis: 'SELECTED_RESULT_OUTPUT_QUANTITY',
      tree: treeFor(request.recipeId, answeredRow.outputName)
    })
  }

  sendJson(404, { error: 'NOT_FOUND', message: url.pathname })
}

/* ------------------------------------------------------------------ page helpers --------------- */

async function textOf(page, test) {
  const found = await page.$(`[data-test="${test}"]`)
  if (found === null) return null
  return (await found.evaluate((element) => element.textContent ?? '')).replace(/\s+/g, ' ').trim()
}

async function present(page, test) {
  return (await page.$(`[data-test="${test}"]`)) !== null
}

async function listedRecipes(page) {
  return page.$$eval('[data-test="profit-row"] .recipe-name', (names) =>
    names.map((name) => (name.textContent ?? '').replace(/\s+/g, ' ').trim())
  )
}

async function tableProfits(page) {
  return page.$$eval('[data-test="total-profit"]', (cells) =>
    cells.map((cell) => (cell.textContent ?? '').replace(/\s+/g, ' ').trim())
  )
}

async function openProfit(page) {
  await page.setViewportSize(VIEWPORT)
  await page.goto(`${PAGE_ORIGIN}/#/crafting`, { waitUntil: 'networkidle', timeout: TIMEOUT_MS })
  await page.waitForSelector('[data-test="profit-table"]', { timeout: TIMEOUT_MS })
}

let PAGE_ORIGIN = ''

/** Waits until this process has answered `atLeast` status reads, naming the shortfall otherwise. */
async function waitForStatusReads(atLeast) {
  const deadline = Date.now() + TIMEOUT_MS
  while (taskStatusReads < atLeast) {
    if (Date.now() > deadline) {
      throw new Error(`Timed out waiting for ${atLeast} task-status reads; ${taskStatusReads} arrived.`)
    }
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
}

/* ------------------------------------------------------------------ the run -------------------- */

async function run() {
  const bundle = requireFreshBundle()
  const browserPath = resolveBrowserPath()
  const stub = await startStubOrigin({ port: PORT, distDir: DIST_DIR, answerApi })
  PAGE_ORIGIN = stub.origin

  console.log(`Browser : ${browserPath}`)
  console.log(`Bundle  : ${bundle}`)
  console.log(`Page    : ${stub.origin} (stub backend in this process)\n`)

  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage({ viewport: VIEWPORT })
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))

  try {
    await openProfit(page)
    check(
      stub.servedCount() > 0,
      `The page at ${stub.origin} was not served by this script — stop whatever else is listening ` +
        'on that port. Nothing was checked.'
    )
    record('page served by this script', `${stub.servedCount()} requests answered so far`)

    // 1. The cached result is on screen and usable while the refresh runs, with the indicator and
    //    the explanation UD-015 item 4 requires. No row is invented and no loading state is left up.
    check(
      JSON.stringify(await listedRecipes(page)) ===
        JSON.stringify(['Deldrimor Steel Ingot', 'Spiritwood Plank']),
      `The cached rows were not listed: ${JSON.stringify(await listedRecipes(page))}`
    )
    check(
      JSON.stringify(await tableProfits(page)) === JSON.stringify([CACHED_PROFIT, '+2g 22s 22c']),
      `The displayed totals are not the supplied ones: ${JSON.stringify(await tableProfits(page))}`
    )
    check(!(await present(page, 'loading')), 'A loading state is shown over a usable cached result.')
    check(!(await present(page, 'pending')), 'A pending state is shown although rows were supplied.')
    const chip = await textOf(page, 'stale-data-chip')
    check(
      chip !== null && chip.includes('Stale data – refreshing…'),
      `The stale-data indicator does not carry UD-015's wording: ${JSON.stringify(chip)}`
    )
    const explanation = await textOf(page, 'stale-data-explanation')
    check(
      explanation !== null &&
        explanation.includes('last synchronized successfully') &&
        explanation.includes('replaced automatically'),
      `The indicator does not explain that cached results stay up: ${JSON.stringify(explanation)}`
    )
    record(
      'cached rows render immediately with the stale-data indicator',
      `${(await listedRecipes(page)).length} rows, totals ${(await tableProfits(page)).join(' / ')}, ` +
        `chip "${chip}"`
    )

    // 2. The indicator reuses the Trading Post warning's visual form, with UD-015's green animated
    //    border in place of that one's red outline — measured from what the browser rendered.
    const chipStyle = await page.evaluate(() => {
      const chipElement = document.querySelector('[data-test="stale-data-chip"]')
      const warning = document.querySelector('[data-test="tp-price-disclaimer-trigger"]')
      if (chipElement === null || warning === null) return null
      const chipComputed = getComputedStyle(chipElement)
      const warningComputed = getComputedStyle(warning)
      return {
        borderStyle: chipComputed.borderTopStyle,
        borderWidth: Number.parseFloat(chipComputed.borderTopWidth),
        borderColor: chipComputed.borderTopColor,
        color: chipComputed.color,
        animationName: chipComputed.animationName,
        animationIterations: chipComputed.animationIterationCount,
        fontSize: Number.parseFloat(chipComputed.fontSize),
        warningColor: warningComputed.color,
        warningFontSize: Number.parseFloat(warningComputed.fontSize)
      }
    })
    check(chipStyle !== null, 'The indicator or the Trading Post warning is not on the page.')
    check(
      chipStyle.borderStyle === 'solid' && chipStyle.borderWidth >= 1,
      `The indicator has no border: ${JSON.stringify(chipStyle)}`
    )
    check(
      chipStyle.animationName !== 'none' && chipStyle.animationIterations === 'infinite',
      `The indicator has no continuously cycling effect: ${JSON.stringify(chipStyle)}`
    )
    check(
      chipStyle.color !== chipStyle.warningColor,
      `The indicator uses the Trading Post warning's own tone: ${JSON.stringify(chipStyle)}`
    )
    check(
      chipStyle.fontSize === chipStyle.warningFontSize,
      `The indicator is not the warning's compact label form: ${JSON.stringify(chipStyle)}`
    )
    record(
      'the indicator is the TP warning’s label form with a cycling border',
      `border ${chipStyle.borderWidth}px ${chipStyle.borderColor}, animation ` +
        `${chipStyle.animationName} ${chipStyle.animationIterations}, text ${chipStyle.color} ` +
        `(warning ${chipStyle.warningColor})`
    )

    // 2a. Under a reduced-motion preference the movement goes but the state does not: the label,
    //     its border and its words stay (`FRONTEND_UX_GUIDELINES.md` 123, section 8's reduced-motion
    //     check). The shell's global rule is what switches the animation off, so this reads the
    //     result rather than the rule.
    await page.emulateMedia({ reducedMotion: 'reduce' })
    const reduced = await page.evaluate(() => {
      const element = document.querySelector('[data-test="stale-data-chip"]')
      if (element === null) return null
      const style = getComputedStyle(element)
      return {
        animationDuration: style.animationDuration,
        borderWidth: Number.parseFloat(style.borderTopWidth),
        borderColor: style.borderTopColor,
        text: (element.textContent ?? '').replace(/\s+/g, ' ').trim()
      }
    })
    check(reduced !== null, 'The indicator disappeared under a reduced-motion preference.')
    check(
      Number.parseFloat(reduced.animationDuration) === 0,
      `The cycling movement still runs under reduced motion: ${JSON.stringify(reduced)}`
    )
    check(
      reduced.borderWidth >= 1 && reduced.text.includes('Stale data – refreshing…'),
      `Reduced motion removed the state along with the movement: ${JSON.stringify(reduced)}`
    )
    await page.emulateMedia({ reducedMotion: 'no-preference' })
    record(
      'reduced motion stops the movement and keeps the label and its border',
      `duration ${reduced.animationDuration}, border ${reduced.borderWidth}px ${reduced.borderColor}`
    )

    // 3. Account freshness and Trading Post coverage stay two separate, backend-supplied facts.
    const account = await textOf(page, 'stale-data-account')
    const prices = await textOf(page, 'stale-data-trading-post')
    check(
      account !== null && account.includes('3 of 4 up to date') && account.includes('CHARACTERS'),
      `Account freshness is not reported in the backend's own terms: ${JSON.stringify(account)}`
    )
    check(
      prices !== null &&
        prices.includes('3000 of 3205 required items up to date') &&
        prices.includes('expired: 180') &&
        prices.includes('no stored price: 25'),
      `Trading Post coverage is not reported separately: ${JSON.stringify(prices)}`
    )
    record('account and Trading Post freshness stay distinguishable', `${account} || ${prices}`)

    // 4. The page polled what the backend advertised, and started no synchronization of its own.
    await waitForStatusReads(1)
    const polled = [...new Set(stub.requestsTo('/api/sync/tasks/').map((request) => request.path))]
    check(
      polled.includes('/api/sync/tasks/task-account-1'),
      `The advertised task was not polled: ${JSON.stringify(polled)}`
    )
    const writes = stub.requests.filter(
      (request) => request.method === 'POST' && /^\/api\/(sync|prices)\//.test(request.path)
    )
    check(
      writes.length === 0,
      `The page started synchronization itself: ${JSON.stringify(writes.map((w) => w.path))}`
    )
    record(
      'only the advertised task locations are polled; no refresh is triggered',
      `${polled.length} location(s) polled, ${writes.length} synchronization requests`
    )

    // 5. A detail answered from newer inputs explains its own row, not the provisional one in the
    //    table, and says so. The table keeps the result it was given.
    await page.click('[data-test="profit-table"] tbody [data-test="select-row"]')
    await page.waitForSelector('[data-test="detail-name"]', { timeout: TIMEOUT_MS })
    await page.waitForSelector('[data-test="resolution-tree"]', { timeout: TIMEOUT_MS })
    const sentDetail = JSON.parse(stub.requestsTo('/api/crafting/profit/resolution').at(0).body)
    check(
      sentDetail.basedOnCalculationId === 'generation-stale',
      `The detail request did not name the displayed generation: ${JSON.stringify(sentDetail)}`
    )
    const detailProfit = await textOf(page, 'detail-total-profit')
    check(
      detailProfit === RECALCULATED_DETAIL_PROFIT,
      `The detail does not show its own answer's row: ${JSON.stringify(detailProfit)}`
    )
    check(
      await present(page, 'detail-recalculated'),
      'A newer-input detail is presented as the explanation of the older row, with no notice.'
    )
    check(
      (await tableProfits(page)).at(0) === CACHED_PROFIT,
      `The table row was replaced by the detail's answer: ${JSON.stringify(await tableProfits(page))}`
    )
    record(
      'a newer-input detail explains its own row and the table keeps its result',
      `detail ${detailProfit}, table ${(await tableProfits(page)).at(0)}, notice shown`
    )

    // 6. The replacement arrives: the controls the user set up survive it, the selected recipe keeps
    //    its detail, the detail is re-asked for the new generation, and the indicator goes away —
    //    only now, because a final calculation for the current context was rendered.
    await page.fill('[data-test="search"]', 'Deldrimor')
    await page.click('[data-test="sort-outputName"]')
    const sortedBefore = await listedRecipes(page)
    detailConsistency = 'FRESH_CALCULATION'
    phase = 'replace'
    await page.waitForFunction(
      (expected) =>
        document.querySelector('[data-test="total-profit"]')?.textContent?.replace(/\s+/g, ' ').trim() ===
        expected,
      REFRESHED_PROFIT,
      { timeout: TIMEOUT_MS }
    )
    await page.waitForSelector('[data-test="stale-data-indicator"]', {
      state: 'detached',
      timeout: TIMEOUT_MS
    })
    const searchAfter = await page.$eval('[data-test="search"]', (input) => input.value)
    check(searchAfter === 'Deldrimor', `The search was lost in the replacement: "${searchAfter}"`)
    check(
      (await page.$eval('[data-test="sort-outputName"]', (th) => th.textContent ?? '')).includes('▲'),
      'The chosen sort was lost in the replacement.'
    )
    check(
      JSON.stringify(await listedRecipes(page)) === JSON.stringify(sortedBefore),
      `The replacement changed the listed set: ${JSON.stringify(await listedRecipes(page))}`
    )
    check(
      (await textOf(page, 'detail-name')) === 'Deldrimor Steel Ingot',
      `The selection was lost in the replacement: ${await textOf(page, 'detail-name')}`
    )
    await page.waitForFunction(
      () => document.querySelector('[data-test="detail-recalculated"]') === null,
      undefined,
      { timeout: TIMEOUT_MS }
    )
    check(
      (await textOf(page, 'detail-total-profit')) === REFRESHED_PROFIT,
      `The detail did not follow the replacement row: ${await textOf(page, 'detail-total-profit')}`
    )
    const lastDetail = JSON.parse(stub.requestsTo('/api/crafting/profit/resolution').at(-1).body)
    check(
      lastDetail.basedOnCalculationId === 'generation-fresh',
      `The replacement generation got no detail of its own: ${JSON.stringify(lastDetail)}`
    )
    check(!(await present(page, 'refresh-failed')), 'A successful replacement reported a failure.')
    record(
      'the replacement keeps search, sort and selection and removes the indicator',
      `profit ${REFRESHED_PROFIT}, search "${searchAfter}", detail generation ` +
        `${lastDetail.basedOnCalculationId}, indicator gone`
    )

    // 7. Bounded failure: the backend reports recovery as finished unsuccessfully. The animation
    //    ends, the last usable rows stay, the reason is the backend's own and a retry is offered.
    phase = 'failing'
    await page.click('[data-test="refresh-data-and-results"]')
    await page.waitForSelector('[data-test="refresh-failed"]', { timeout: TIMEOUT_MS })
    check(
      !(await present(page, 'stale-data-indicator')),
      'The refreshing animation is still shown after recovery stopped.'
    )
    const failureDetail = await textOf(page, 'refresh-failure-detail')
    check(
      failureDetail !== null && failureDetail.includes('ACCOUNT_REFRESH_FAILED'),
      `The failure does not carry the backend's own reason: ${JSON.stringify(failureDetail)}`
    )
    check(
      (await listedRecipes(page)).length === 1 &&
        (await tableProfits(page)).at(0) === CACHED_PROFIT,
      `The last usable rows were not retained: ${JSON.stringify(await tableProfits(page))}`
    )
    check(await present(page, 'refresh-retry'), 'No retry is offered after recovery stopped.')
    const animating = await page.evaluate(() =>
      [...document.querySelectorAll('[data-test="refresh-status"] *')].some(
        (element) => getComputedStyle(element).animationName !== 'none'
      )
    )
    check(animating === false, 'Something in the refresh status is still animating after failure.')
    record(
      'bounded failure keeps the rows, stops the animation and offers a retry',
      `reason "${failureDetail}", ${(await listedRecipes(page)).length} row kept`
    )

    // 8. The retry starts one new bounded cycle: it asks for the calculation again — which is what
    //    makes the backend recheck freshness — and triggers no synchronization of its own.
    const calculationsBeforeRetry = stub.requestsTo('/api/crafting/profit').filter(
      (request) => request.path === '/api/crafting/profit'
    ).length
    detailConsistency = 'FRESH_CALCULATION'
    phase = 'replace'
    await page.click('[data-test="refresh-retry"]')
    await page.waitForFunction(
      (expected) =>
        document.querySelector('[data-test="total-profit"]')?.textContent?.replace(/\s+/g, ' ').trim() ===
        expected,
      REFRESHED_PROFIT,
      { timeout: TIMEOUT_MS }
    )
    const calculationsAfterRetry = stub.requests.filter(
      (request) => request.path === '/api/crafting/profit'
    ).length
    check(
      calculationsAfterRetry > calculationsBeforeRetry,
      `The retry did not ask for the calculation again (${calculationsBeforeRetry} → ${calculationsAfterRetry}).`
    )
    const writesAfterRetry = stub.requests.filter(
      (request) => request.method === 'POST' && /^\/api\/(sync|prices)\//.test(request.path)
    )
    check(
      writesAfterRetry.length === 0,
      `The retry started synchronization itself: ${JSON.stringify(writesAfterRetry.map((w) => w.path))}`
    )
    check(!(await present(page, 'refresh-failed')), 'The retry left the failure state on screen.')
    check(
      !(await present(page, 'stale-data-indicator')),
      'The retry reached a final result but kept the indicator.'
    )
    record(
      'an explicit retry re-asks the calculation and starts no refresh itself',
      `${calculationsBeforeRetry} → ${calculationsAfterRetry} calculations, ` +
        `${writesAfterRetry.length} synchronization requests`
    )

    check(pageErrors.length === 0, `The page reported script errors: ${pageErrors.join(' | ')}`)
    record('no uncaught page errors', `${pageErrors.length} reported`)

    console.log(`\n${steps.length} steps passed.`)
  } finally {
    await browser.close()
    stub.close()
  }
}

try {
  await run()
} catch (error) {
  console.error(`\nFAILED after ${steps.length} step(s): ${error?.stack ?? error}`)
  process.exitCode = 1
}
