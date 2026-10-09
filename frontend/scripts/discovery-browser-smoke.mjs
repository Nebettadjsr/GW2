/**
 * Real-browser check of the Crafting Discovery page: the comparison/detail split at a wide and a
 * narrow viewport, keyboard-operated recipe selection and sorting, the grouped controls, and the
 * selected recipe's fresh detail (STORY-WEB-012, `DOMAIN_SPEC.md` 2.2.2,
 * `FRONTEND_UX_GUIDELINES.md` 2, 3, 4, 5).
 *
 * Runs the built frontend against a *controlled* API boundary (`scripts/stubOrigin.mjs`): every
 * answer comes from this process, so no backend, database or GW2 API is involved and nothing can be
 * synchronized. It therefore evidences structure, layout, interaction and what the page sends —
 * never real data, and never page-load performance (`TARGET_ARCHITECTURE.md` 33).
 *
 * Since STORY-WEB-028 one candidate's answer carries a root the resolver supplied from stock, so the
 * root-sourcing sentence is rendered and read here as well as suppressed where it would only restate
 * the requested recipe.
 *
 * Which eligibility and special-state cases this run evidences is controlled-response evidence only:
 * the rating filter, the account-wide recipe-knowledge rule and normal-discovery eligibility are the
 * backend's, are not re-implemented here, and are not exercised against real data by this script.
 * `smoke:discovery:live` is the read-only comparison against an actual backend.
 *
 * Usage:  npm run build && npm run smoke:discovery
 * Environment:
 *   GW2_DISCOVERY_SMOKE_PORT  port for the stub origin  (default 5179)
 *   GW2_BROWSER_PATH          browser executable        (default: the first installed Chrome/Edge)
 *   GW2_SMOKE_TIMEOUT_MS      per-step wait             (default 30000)
 *
 * Exits 0 when every step passed, 1 otherwise.
 */
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { requireFreshBundle } from './bundleFreshness.mjs'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startStubOrigin } from './stubOrigin.mjs'

const PORT = Number(process.env.GW2_DISCOVERY_SMOKE_PORT ?? 5179)
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
const DIST_DIR = fileURLToPath(new URL('../dist/', import.meta.url))

const WIDE = { width: 1440, height: 900 }
const NARROW = { width: 360, height: 800 }

const DISCOVERY_ROUTE = '/api/crafting/discovery'
const RESOLUTION_ROUTE = '/api/crafting/discovery/resolution'

/** The value the Discovery flow fixes; it is reported, never accepted as an input. */
const DAILY_FIXED = true

/** Discovery's own echoed defaults — deliberately not Crafting Profit's. */
const DEFAULT_SETTINGS = {
  useOwnMats: true,
  allowBuying: true,
  maxBuyCopper: 200_000,
  listingSell: false,
  listingBuy: false,
  allowDailyCrafts: DAILY_FIXED
}

const CHARACTER_OPTIONS = [
  { characterName: 'Nbt Anch', discipline: 'Armorsmith', rating: 500, active: true },
  { characterName: 'Sat Anat', discipline: 'Chef', rating: 400, active: false }
]

const steps = []

function record(name, detail) {
  steps.push({ name, detail })
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

function check(condition, message) {
  if (!condition) throw new Error(message)
}

/**
 * Candidates covering what the list has to keep apart: a gain, a loss, a blocked row whose economics
 * are null, and a recipe with no calculated result. Five different recipe levels, so level sorting is
 * observable in both directions.
 *
 * Every total is deliberately *not* the product its parts would give, so a page that derived one
 * would disagree with this. No icon metadata is supplied: the shared component then renders its
 * inline fallback and asks for nothing — image delivery itself is `smoke:icons`.
 */
function candidateRows() {
  const row = (recipeId, outputName, minRating, overrides) => ({
    recipeId,
    outputItemId: 1000 + recipeId,
    outputName,
    outputCount: 2,
    disciplines: 'Armorsmith,Weaponsmith',
    minRating,
    resultAvailable: true,
    craftableCount: 7,
    buyCostCopper: 98_765,
    matsSellValueCopper: 4_321,
    revenueCopper: 111_110,
    profitCopper: 12_345,
    // Not 7 × 111 110 and not 7 × 12 345: these are the backend's own totals.
    totalSellValueCopper: 1_481_401,
    totalProfitCopper: 148_140,
    blockedReason: 'NONE',
    outputPrice: { buyUnitCopper: 120_000, sellUnitCopper: 130_000 },
    missingToBuy: [
      { itemId: 19_700, itemName: 'Mithril Ore', quantity: 60, price: { buyUnitCopper: 120, sellUnitCopper: 140 }, iconUrl: null },
      { itemId: 19_701, itemName: null, quantity: 5, price: null, iconUrl: null }
    ],
    missingToBuyOne: [
      { itemId: 19_700, itemName: 'Mithril Ore', quantity: 5, price: { buyUnitCopper: 120, sellUnitCopper: 140 }, iconUrl: null }
    ],
    iconUrl: null,
    ...overrides
  })

  return [
    row(1, 'Deldrimor Steel Ingot of Considerable Length and Name', 400),
    // A loss is a legitimate discovery candidate (DOMAIN_SPEC 37) and must stay listed.
    row(2, 'Loss-making Discovery', 225, {
      profitCopper: -25_000,
      totalProfitCopper: -175_000,
      totalSellValueCopper: 900_001
    }),
    row(3, 'Break-even Discovery', 150, { profitCopper: 0, totalProfitCopper: 0 }),
    row(4, 'Blocked Discovery', 75, {
      blockedReason: 'PRICE_UNAVAILABLE',
      craftableCount: 0,
      buyCostCopper: null,
      matsSellValueCopper: null,
      revenueCopper: null,
      profitCopper: null,
      totalSellValueCopper: null,
      totalProfitCopper: null,
      outputPrice: { buyUnitCopper: null, sellUnitCopper: null },
      missingToBuyOne: []
    }),
    row(5, 'Uncalculated Discovery', 0, {
      resultAvailable: false,
      craftableCount: null,
      buyCostCopper: null,
      matsSellValueCopper: null,
      revenueCopper: null,
      profitCopper: null,
      totalSellValueCopper: null,
      totalProfitCopper: null,
      blockedReason: null,
      outputPrice: null,
      missingToBuy: null,
      missingToBuyOne: null
    })
  ]
}

const ROWS = candidateRows()

/**
 * The one candidate this script answers with a root the resolver did not craft at all: its output came
 * from stock, so no recipe was selected for it and `CraftingResolution.vue` renders the root-sourcing
 * sentence for that case (STORY-WEB-024 F004). Every other candidate's root carries the recipe that
 * was asked about, which is the suppression case step 8 reads.
 */
const STOCK_ROOT = { recipeId: 3, row: 'Break-even Discovery' }

/** The tree for a selected recipe: owned stock, a purchase and a deeper craft, in that order. */
function resolutionTree(recipeId) {
  const node = (overrides) => ({
    itemId: 9001,
    itemName: 'Requirement',
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

  const outputName =
    ROWS.find((row) => row.recipeId === recipeId)?.outputName ?? `Item #${1000 + recipeId}`

  if (recipeId === STOCK_ROOT.recipeId) {
    return node({
      itemId: 1000 + recipeId,
      itemName: outputName,
      requestedQuantity: 2,
      inventoryQuantity: 2,
      methods: ['INVENTORY'],
      // No recipe was selected for this root, so it has no ingredients of its own either.
      cashCostCopper: 0,
      opportunityCostCopper: 6_543,
      effectiveCostCopper: 6_543
    })
  }

  return node({
    itemId: 1000 + recipeId,
    itemName: outputName,
    craftedQuantity: 1,
    recipeId,
    craftCount: 1,
    producedQuantity: 2,
    characterName: 'Nbt Anch',
    methods: ['CRAFT'],
    // Not the children's costs added up: the backend's own inclusive figure.
    cashCostCopper: 777_777,
    opportunityCostCopper: 5_555,
    effectiveCostCopper: 783_332,
    children: [
      node({
        itemId: 19_700,
        itemName: 'Mithril Ore',
        requestedQuantity: 6,
        inventoryQuantity: 2,
        boughtQuantity: 4,
        methods: ['INVENTORY', 'BUY'],
        cashCostCopper: 480,
        opportunityCostCopper: 240,
        effectiveCostCopper: 720
      }),
      node({
        itemId: 19_701,
        itemName: null,
        requestedQuantity: 3,
        missingQuantity: 3,
        methods: [],
        states: ['PRICE_UNAVAILABLE', 'BLOCKED'],
        blockedReasons: ['PRICE_UNAVAILABLE'],
        cashCostCopper: null,
        opportunityCostCopper: null,
        effectiveCostCopper: null
      })
    ]
  })
}

function answerApi({ url, body, sendJson }) {
  if (url.pathname === '/api/crafting/selector-options') {
    return sendJson(200, {
      defaultScopeKind: 'ALL',
      disciplines: ['Armorsmith', 'Chef'],
      characterOptionCount: CHARACTER_OPTIONS.length,
      characterOptions: CHARACTER_OPTIONS
    })
  }

  if (url.pathname === DISCOVERY_ROUTE) {
    const request = body === '' ? {} : JSON.parse(body)
    const scope = request.scope
    // The route requires a complete individual scope; anything else is this script's own failure.
    if (scope === undefined || scope.characterName === undefined || scope.rating === undefined) {
      return sendJson(400, { error: 'INVALID_SCOPE', message: 'Discovery requires a full scope.' })
    }
    return sendJson(200, {
      scope: { discipline: scope.discipline, characterName: scope.characterName, rating: scope.rating },
      inventoryCharacterName: request.inventoryCharacterName ?? null,
      settings:
        request.settings === undefined
          ? DEFAULT_SETTINGS
          : { ...request.settings, allowDailyCrafts: DAILY_FIXED },
      rowCount: ROWS.length,
      rows: ROWS
    })
  }

  if (url.pathname === RESOLUTION_ROUTE) {
    const request = JSON.parse(body)
    const calculation = request.calculation ?? {}
    const scope = calculation.scope ?? {}
    const row = ROWS.find((candidate) => candidate.recipeId === request.recipeId)
    if (row === undefined) {
      return sendJson(404, {
        error: 'RECIPE_NOT_IN_CALCULATION',
        message: `Recipe ${request.recipeId} is not in this calculation.`
      })
    }
    return sendJson(200, {
      recipeId: request.recipeId,
      // Echoed exactly as asked, which is what the browser matches the answer against.
      calculation: {
        scope: { discipline: scope.discipline, characterName: scope.characterName, rating: scope.rating },
        inventoryCharacterName: calculation.inventoryCharacterName ?? null,
        settings: { ...calculation.settings, allowDailyCrafts: DAILY_FIXED }
      },
      consistency: 'FRESH_CALCULATION',
      calculatedAt: '2026-09-27T09:00:00Z',
      // A fresh calculation may legitimately disagree with the table's own numbers.
      row: { ...row, totalProfitCopper: 424_242 },
      treeStatus: 'AVAILABLE',
      treeBasis: 'SINGLE_OUTPUT_REQUIREMENT',
      tree: resolutionTree(request.recipeId)
    })
  }

  sendJson(404, { error: 'NOT_FOUND', message: url.pathname })
}

async function openDiscovery(page, origin, viewport) {
  await page.setViewportSize(viewport)
  await page.goto(`${origin}/#/discovery`, { waitUntil: 'networkidle', timeout: TIMEOUT_MS })
  await page.reload({ waitUntil: 'networkidle', timeout: TIMEOUT_MS })
  await page.waitForSelector('[data-test="discovery-table"]', { timeout: TIMEOUT_MS })
}

async function textOf(page, selector) {
  return (await page.$eval(selector, (element) => element.textContent)).replace(/\s+/g, ' ').trim()
}

async function textsOf(page, selector) {
  return page.$$eval(selector, (elements) =>
    elements.map((element) => element.textContent.replace(/\s+/g, ' ').trim())
  )
}

async function boxOf(page, selector) {
  return page.$eval(selector, (element) => {
    const rect = element.getBoundingClientRect()
    return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: rect.width }
  })
}

/** Horizontal page overflow, and the elements actually reaching past the viewport when there is any. */
async function pageOverflow(page) {
  return page.evaluate(() => {
    const root = document.documentElement
    const past = Array.from(document.querySelectorAll('body *'))
      .filter((element) => element.getBoundingClientRect().right > root.clientWidth + 1)
      .slice(0, 5)
      .map((element) => `${element.tagName.toLowerCase()}[${element.getAttribute('data-test') ?? ''}]`)
    return { scrollWidth: root.scrollWidth, clientWidth: root.clientWidth, past }
  })
}

async function focusState(page) {
  return page.evaluate(() => {
    const active = document.activeElement
    if (active === null || active === document.body) return null
    const style = getComputedStyle(active)
    return {
      test: active.getAttribute('data-test'),
      outlineStyle: style.outlineStyle,
      outlineWidth: Number.parseFloat(style.outlineWidth) || 0
    }
  })
}

async function run() {
  const bundle = requireFreshBundle()

  const browserPath = resolveBrowserPath()
  const stub = await startStubOrigin({ port: PORT, distDir: DIST_DIR, answerApi })
  console.log(`Browser : ${browserPath}`)
  console.log(`Bundle  : ${bundle}`)
  console.log(`Page    : ${stub.origin} (stub backend in this process)\n`)

  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage({ viewport: WIDE })
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))
  const browserRequests = []
  page.on('request', (request) => browserRequests.push(request.url()))

  try {
    await openDiscovery(page, stub.origin, WIDE)
    check(
      stub.servedCount() > 0,
      `The page at ${stub.origin} was not served by this script — stop whatever else is listening ` +
        'on that port. Nothing was checked.'
    )
    record('page served by this script', `${stub.servedCount()} requests answered so far`)

    // 1. A first-class, addressable destination with its own title and heading.
    check(
      new URL(page.url()).hash === '#/discovery',
      `Discovery is not addressable at its own URL: ${page.url()}`
    )
    check(
      (await page.title()) === 'Crafting Discovery · GW2 Crafting Tool',
      `The document title does not name the page: ${await page.title()}`
    )
    check(
      (await textOf(page, '[data-page-heading]')) === 'Crafting Discovery',
      `The page heading is not the destination's own name: ${await textOf(page, '[data-page-heading]')}`
    )
    const navCurrent = await page.$eval('[aria-current="page"]', (element) =>
      element.getAttribute('data-test')
    )
    check(navCurrent === 'nav-discovery', `The navigation does not mark Discovery current: ${navCurrent}`)
    record('addressable destination with its own title and heading', '#/discovery')

    // 2. Wide: the comparison list and the detail region sit side by side, both bounded.
    const wideResults = await boxOf(page, '[data-test="discovery-results-region"]')
    const wideDetail = await boxOf(page, '[data-test="discovery-detail"]')
    check(
      wideDetail.left >= wideResults.right - 1,
      `At ${WIDE.width}px the detail did not sit beside the list ` +
        `(list right ${wideResults.right}, detail left ${wideDetail.left}).`
    )
    check(
      wideDetail.width >= 300 && wideDetail.width <= 460,
      `The detail region is not bounded at ${WIDE.width}px: ${wideDetail.width}px.`
    )
    const wideOverflow = await pageOverflow(page)
    check(
      wideOverflow.scrollWidth <= wideOverflow.clientWidth + 1,
      `The page scrolls horizontally at ${WIDE.width}px (${wideOverflow.scrollWidth} > ` +
        `${wideOverflow.clientWidth}), past: ${wideOverflow.past.join(', ')}`
    )
    record(
      `list and detail side by side at ${WIDE.width}×${WIDE.height}`,
      `list ${Math.round(wideResults.width)}px, detail ${Math.round(wideDetail.width)}px, no page overflow`
    )

    // 3. The inputs that drive a calculation and the inputs that only change what is displayed are two
    // separate groups inside the one labelled controls panel. The screen names that panel with
    // `aria-label` and marks each group with its own hook rather than with a `fieldset`/`legend` pair,
    // so what this step compares is membership: a control that moved between the two still fails here.
    const grouping = await page.evaluate(() => {
      const panel = document.querySelector('.discovery-controls')
      const calculation = document.querySelector('[data-test="discovery-calculation-controls"]')
      const display = document.querySelector('[data-test="discovery-display-controls"]')
      const holds = (group, selector) => group !== null && group.querySelector(selector) !== null
      return {
        panelLabel: panel?.getAttribute('aria-label') ?? null,
        bothInPanel:
          panel !== null &&
          calculation !== null &&
          display !== null &&
          panel.contains(calculation) &&
          panel.contains(display) &&
          !calculation.contains(display) &&
          !display.contains(calculation),
        scopeInCalculation: holds(calculation, '[data-test="discovery-scope-selector"]'),
        scopeInDisplay: holds(display, '[data-test="discovery-scope-selector"]'),
        searchInDisplay: holds(display, '[data-test="discovery-search"]'),
        searchInCalculation: holds(calculation, '[data-test="discovery-search"]')
      }
    })
    check(
      grouping.panelLabel === 'Discovery controls' && grouping.bothInPanel,
      `The calculation and display controls are not two sibling groups in one labelled panel: ${JSON.stringify(grouping)}`
    )
    check(
      grouping.scopeInCalculation && !grouping.scopeInDisplay,
      `The scope selector is not in the calculation group: ${JSON.stringify(grouping)}`
    )
    check(
      grouping.searchInDisplay && !grouping.searchInCalculation,
      `The search is not in the display-only group: ${JSON.stringify(grouping)}`
    )
    // No profit filter: hiding candidates by profit is a Profit control, not Discovery eligibility.
    check(
      (await page.$('[data-test="filter-non-positive-profit"]')) === null,
      "Discovery offers Crafting Profit's non-positive-profit filter as if it were an eligibility rule."
    )
    record(
      `calculation and display controls are separate groups in the "${grouping.panelLabel}" panel`,
      'scope selector in the calculation group, search in the display group, no non-positive-profit filter'
    )

    // 4. Individual character disciplines only, each carrying the rating the backend reported.
    const scopeOptions = await textsOf(page, '[data-test="discovery-scope-selector"] option')
    check(
      scopeOptions.join(' | ') === 'Armorsmith lvl 500 — Nbt Anch | Chef lvl 400 — Sat Anat',
      `The scope options are not the selector's own character disciplines: ${scopeOptions.join(' | ')}`
    )
    check(
      !scopeOptions.includes('All'),
      'The scope selector offers an All entry, which Discovery has no reading for.'
    )
    // The selected character is both the eligibility scope and the material source, so there is one
    // selector and no separate inventory choice to disagree with it (`useCraftingDiscovery.ts`,
    // `CraftingDiscoveryScreen.spec.ts` "uses one selected character as both the discovery scope and
    // material source"). Step 12 checks the requests this leaves on the wire.
    const selectorCount = await page.$$eval(
      '[data-test="discovery-scope-selector"], [data-test="discovery-inventory-selector"]',
      (elements) => elements.map((element) => element.getAttribute('data-test'))
    )
    check(
      selectorCount.join(' | ') === 'discovery-scope-selector',
      `Discovery offers a scope and material source other than the one selected character: ${selectorCount.join(' | ')}`
    )
    record(
      'one character selector supplying both scope and materials, with the supplied ratings',
      scopeOptions.join(' | ')
    )

    // 5. Every candidate the backend returned is listed, losses included, in level order.
    const levels = await textsOf(page, '[data-test="discovery-level"]')
    check(
      levels.join(',') === '400,225,150,75,0',
      `The list does not open on highest recipe level first: ${levels.join(',')}`
    )
    // The row's own supplied total, like the sell value beside it and the detail's profit figure —
    // never the per-craft `profitCopper` the same fixture sets to a different value, so a column
    // reading that field instead would print `+1g 23s 45c` here and fail.
    const profits = await textsOf(page, '[data-test="discovery-profit"]')
    check(
      profits.join(' | ') === '+14g 81s 40c | -17g 50s 0c | 0c | — | —',
      `The supplied profits are not displayed with their signs, zeros and nulls: ${profits.join(' | ')}`
    )
    // The backend's own total, not a product of a count and a revenue.
    const sellValues = await textsOf(page, '[data-test="discovery-sell-value"]')
    check(
      sellValues[0] === '148g 14s 1c',
      `The output sell value is not the supplied total: ${sellValues[0]}`
    )
    record('every returned candidate listed with its supplied economics', `${levels.length} rows, loss and zero kept`)

    // 6. Selection is operable from the keyboard alone, with focus visible on the control used.
    check(
      (await textOf(page, '[data-test="discovery-detail-placeholder"]')).includes('Choose a recipe'),
      'With nothing selected the detail region did not say what to do.'
    )
    await page.focus('[data-test="discovery-table"] tbody [data-test="discovery-select-row"]')
    const focusedControl = await focusState(page)
    check(
      focusedControl?.test === 'discovery-select-row',
      `Tabbing into the table did not reach a row control: ${JSON.stringify(focusedControl)}`
    )
    check(
      focusedControl.outlineStyle !== 'none' && focusedControl.outlineWidth >= 1,
      `Keyboard focus was not visible on the row control: ${JSON.stringify(focusedControl)}`
    )
    const firstRowName = await textOf(page, '[data-test="discovery-row"] .recipe-name')
    const detailAnswered = page.waitForResponse(
      (response) => new URL(response.url()).pathname === RESOLUTION_ROUTE,
      { timeout: TIMEOUT_MS }
    )
    await page.keyboard.press('Enter')
    await detailAnswered
    await page.waitForSelector('[data-test="resolution-tree"]', { timeout: TIMEOUT_MS })
    check(
      (await textOf(page, '[data-test="discovery-detail-name"]')) === firstRowName,
      `Enter opened "${await textOf(page, '[data-test="discovery-detail-name"]')}" rather than "${firstRowName}".`
    )
    const stillFocused = await focusState(page)
    check(
      stillFocused?.test === 'discovery-select-row',
      'Selecting a recipe moved focus away from the control used.'
    )
    record('recipe selection is keyboard-operable', `Enter selected "${firstRowName}", focus kept and visible`)

    // 7. The selection is marked for sight and for assistive technology.
    const selection = await page.evaluate(() => {
      const row = document.querySelector('[data-test="discovery-row"][aria-current="true"]')
      if (row === null) return null
      return {
        count: document.querySelectorAll('[data-test="discovery-row"][aria-current="true"]').length,
        marked: row.className.includes('selected'),
        announced: row.textContent.includes('Selected')
      }
    })
    check(selection !== null, 'The selected row is not marked for assistive technology.')
    check(
      selection.count === 1 && selection.marked && selection.announced,
      `The selection is not marked exactly once and visibly: ${JSON.stringify(selection)}`
    )
    record('selected recipe marked visibly and for assistive technology')

    // 8. The table row's own total is what the detail shows, and the fresh calculation's
    //    disagreeing figures are not printed beside it (`DOMAIN_SPEC.md` 2.1.1). The fresh answer
    //    is still requested and still supplies the tree below.
    const tableTotal = await textOf(page, '[data-test="discovery-detail-profit"]')
    check(
      tableTotal === '+14g 81s 40c',
      `The fresh calculation overwrote the table row's own total: ${tableTotal}`
    )
    const detailText = await textOf(page, '[data-test="discovery-detail"]')
    check(
      (await page.$('[data-test="resolution-row"]')) === null &&
        (await page.$('[data-test="resolution-total-profit"]')) === null &&
        !detailText.includes('+42g 42s 42c') &&
        !detailText.includes('This recipe in that fresh calculation'),
      'The removed fresh-row summary is still rendered in the Discovery detail.'
    )
    // The compact presentation `DOMAIN_SPEC.md` 2.1.1 asks of Crafting Resolution: no basis
    // paragraph, no closing tree note, and no sentence restating what the requested recipe already
    // says. STORY-WEB-028 removed the basis and tree-note branches outright — no page selected them —
    // so those two are a guard against reintroduction, while the sourcing sentence is genuinely
    // suppressed here because this tree's root recipe *is* the recipe that was asked about. Step 9b
    // selects the candidate whose root has no recipe at all and reads what that case does say.
    const removedProse = await page.evaluate(() =>
      ['resolution-basis', 'resolution-tree-note', 'resolution-root-sourcing'].filter(
        (test) => document.querySelector(`[data-test="${test}"]`) !== null
      )
    )
    check(
      removedProse.length === 0,
      `The tree is not rendered in its compact form: ${removedProse.join(', ')} still on screen.`
    )
    check(
      !detailText.includes('is the recipe selected for this requirement') &&
        !detailText.includes('one output batch'),
      'The detail still carries the prose the compact resolution removed.'
    )
    // DOMAIN_SPEC 25: the fee is named on the profit figure and nowhere else, so the gross values
    // beside it are not described as net of anything. The note now sits on the Profit term of the
    // calculation list rather than in a note of its own, so the claim is checked where it is rendered:
    // one note in the whole detail, carried by the profit term.
    const feeNotes = await page.evaluate(() => {
      const calculation = document.querySelector('[data-test="discovery-detail-calculation"]')
      const profitTerm = [...(calculation?.querySelectorAll('dt') ?? [])].find((term) =>
        term.querySelector('.value-note') !== null
      )
      const text = (element) => (element?.textContent ?? '').replace(/\s+/g, ' ').trim()
      return {
        all: [...document.querySelectorAll('[data-test="discovery-detail"] .value-note')].map(text),
        onTerm: text(profitTerm)
      }
    })
    check(
      feeNotes.all.length === 1 && feeNotes.all[0] === 'after 15% TP fees',
      `The after-fees note is missing, reworded, or repeated on a gross value: ${JSON.stringify(feeNotes)}`
    )
    check(
      feeNotes.onTerm.startsWith('Profit'),
      `The after-fees note does not belong to the profit figure: ${JSON.stringify(feeNotes)}`
    )
    record(
      'the fresh tree is shown beside the table row without repeating its figures',
      'compact tree with no basis, tree-note or root-sourcing prose; one fee note on the profit ' +
        'term; no fresh-row summary'
    )

    // 9. The supplied requirements are rendered in order, through the shared tree.
    const nodeNames = await textsOf(page, '[data-test="node-name"]')
    check(
      nodeNames.join(' | ') ===
        'Deldrimor Steel Ingot of Considerable Length and Name | Mithril Ore | Item #19701',
      `The supplied requirements are not rendered in their own order: ${nodeNames.join(' | ')}`
    )
    // The compact presentation carries one effective value per requirement rather than the three
    // separate cost figures (`ResolutionTreeNode.vue` has no other mode). The root's is the backend's
    // own inclusive 783_332 copper, not the children's added up.
    const rootCost = await textOf(page, '[data-path="0"] [data-test="node-value"]')
    check(
      rootCost === 'Value: 78g 33s 32c',
      `The root's inclusive cost is not the supplied figure: ${rootCost}`
    )
    const unpricedCost = await textOf(page, '[data-path="0.1"] [data-test="node-value"]')
    check(
      unpricedCost === 'Value: —',
      `A cost the backend could not establish became a number: ${unpricedCost}`
    )
    // Discovery shares the tree component, so it inherits the collapsed presentation: every
    // requirement is rendered, and none of the groups starts open.
    const discoveryOpenGroups = await page.$$eval('[data-test="node-children"]', (groups) =>
      groups.filter((group) => group.open).length
    )
    check(
      discoveryOpenGroups === 0,
      `Discovery's ingredient groups did not start collapsed: ${discoveryOpenGroups} open.`
    )
    record(
      'requirements rendered in order with supplied costs, groups collapsed',
      `${nodeNames.length} nodes, ${discoveryOpenGroups} groups open`
    )

    // 9b. A root the resolver supplied from stock had no recipe selected for it, so the view says
    //     that in `resolutionPresentation.ts`'s `describeRootSourcing` words instead of letting the
    //     requested recipe read as the one that produced it. Step 8's root *is* the requested recipe,
    //     which is why that one says nothing (STORY-WEB-024 F004).
    const listedNames = await textsOf(page, '[data-test="discovery-row"] .recipe-name')
    const stockIndex = listedNames.indexOf(STOCK_ROOT.row)
    check(stockIndex >= 0, `"${STOCK_ROOT.row}" is not listed: ${listedNames.join(', ')}`)
    await (await page.$$('[data-test="discovery-select-row"]'))[stockIndex].click()
    await page.waitForFunction(
      (name) =>
        document.querySelector('[data-test="discovery-detail-name"]')?.textContent?.trim() === name,
      STOCK_ROOT.row,
      { timeout: TIMEOUT_MS }
    )
    await page.waitForSelector('[data-test="resolution-root-sourcing"]', { timeout: TIMEOUT_MS })
    const stockRoot = await page.evaluate(() => {
      const text = (selector) => {
        const element = document.querySelector(selector)
        return element === null ? null : element.textContent.replace(/\s+/g, ' ').trim()
      }
      return {
        sourcing: text('[data-test="resolution-root-sourcing"]'),
        methods: text('[data-path="0"] [data-test="node-sourcing"]'),
        value: text('[data-path="0"] [data-test="node-value"]'),
        nodes: document.querySelectorAll('[data-test="tree-node"]').length
      }
    })
    check(
      stockRoot.sourcing ===
        'No recipe was selected for this requirement, so the output shown here was not crafted by ' +
          `the requested recipe ${STOCK_ROOT.recipeId}.`,
      `A root with no selected recipe is not described as such: ${JSON.stringify(stockRoot)}`
    )
    // And the requirement itself is the stock the answer named, with its own supplied value: the
    // sentence describes the tree on screen rather than standing on its own.
    check(
      stockRoot.methods === 'From stock ×2' && stockRoot.value === 'Value: 65s 43c' && stockRoot.nodes === 1,
      `The root this sentence is about is not the supplied one: ${JSON.stringify(stockRoot)}`
    )
    record(
      'a root supplied from stock is not called an execution of the requested recipe',
      `"${stockRoot.sourcing}" over ${stockRoot.nodes} requirement at ${stockRoot.value}`
    )

    // 10. Sorting and searching are view state: neither reaches the backend, and neither drops the
    //     valid detail on screen.
    const beforeViewChanges = stub.requests.filter((request) => request.path.startsWith('/api/')).length
    await page.focus('[data-test="discovery-sort-minRating"]')
    await page.keyboard.press('Enter')
    const ascending = await textsOf(page, '[data-test="discovery-level"]')
    check(
      ascending.join(',') === '0,75,150,225,400',
      `Level sorting did not reverse from the keyboard: ${ascending.join(',')}`
    )
    const ariaSort = await page.$eval(
      '[data-test="discovery-table"] thead th:nth-child(2)',
      (element) => element.getAttribute('aria-sort')
    )
    check(ariaSort === 'ascending', `The sorted column does not report its direction: ${ariaSort}`)
    await page.fill('[data-test="discovery-search"]', 'Loss-making')
    await page.waitForFunction(
      () => document.querySelectorAll('[data-test="discovery-row"]').length === 1,
      undefined,
      { timeout: TIMEOUT_MS }
    )
    const afterViewChanges = stub.requests.filter((request) => request.path.startsWith('/api/')).length
    check(
      afterViewChanges === beforeViewChanges,
      `Sorting or searching issued ${afterViewChanges - beforeViewChanges} request(s); both are view state.`
    )
    // The selected recipe's row is hidden by the search, and its detail stays reachable and says so.
    check(
      (await page.$('[data-test="discovery-detail-hidden"]')) !== null,
      'The selected recipe\'s detail did not say the search is holding its row back.'
    )
    check(
      (await page.$('[data-test="resolution-tree"]')) !== null,
      'Searching dropped a still-valid fresh detail.'
    )
    record('sorting and searching send nothing and keep the detail', `${afterViewChanges} API calls so far`)

    // 11. Narrow: the detail moves below the list, and nothing overflows sideways.
    await page.fill('[data-test="discovery-search"]', '')
    await openDiscovery(page, stub.origin, NARROW)
    const narrowResults = await boxOf(page, '[data-test="discovery-results-region"]')
    const narrowDetail = await boxOf(page, '[data-test="discovery-detail"]')
    check(
      narrowDetail.top >= narrowResults.top,
      `At ${NARROW.width}px the detail did not move below the list ` +
        `(list top ${narrowResults.top}, detail top ${narrowDetail.top}).`
    )
    const narrowOverflow = await pageOverflow(page)
    check(
      narrowOverflow.scrollWidth <= narrowOverflow.clientWidth + 1,
      `The page scrolls horizontally at ${NARROW.width}px (${narrowOverflow.scrollWidth} > ` +
        `${narrowOverflow.clientWidth}), past: ${narrowOverflow.past.join(', ')}`
    )
    // The table itself is the scroll container, and it is reachable by keyboard.
    const tableRegion = await page.$eval('[data-test="discovery-results-region"] .table-region', (element) => ({
      scrollable: element.scrollWidth > element.clientWidth,
      tabIndex: element.tabIndex
    }))
    check(
      tableRegion.tabIndex >= 0,
      `The scrollable table region cannot be focused for keyboard scrolling: ${JSON.stringify(tableRegion)}`
    )
    record(
      `detail below the list at ${NARROW.width}×${NARROW.height}`,
      `no page overflow, table region focusable (scrollable: ${tableRegion.scrollable})`
    )

    // 12. What the page asked for over the whole run, and what it never asked for.
    const apiCalls = stub.requests.filter((request) => request.path.startsWith('/api/'))
    const tableCalls = apiCalls.filter((request) => request.path === DISCOVERY_ROUTE)
    const detailCalls = apiCalls.filter((request) => request.path === RESOLUTION_ROUTE)
    check(
      tableCalls.every((request) => request.method === 'POST'),
      'The Discovery table was not requested over POST.'
    )
    for (const request of tableCalls) {
      const sent = JSON.parse(request.body)
      check(
        sent.scope?.characterName === 'Nbt Anch' && sent.scope?.rating === 500,
        `A calculation was sent without the selector's own character and rating: ${request.body}`
      )
      check(
        sent.settings === undefined || sent.settings.allowDailyCrafts === undefined,
        `A calculation sent the fixed daily setting the route does not accept: ${request.body}`
      )
    }
    for (const request of detailCalls) {
      const sent = JSON.parse(request.body)
      // The one selected character is the material source, so the detail asks under the scope it was
      // calculated with and sends no second inventory character beside it
      // (`discoveryResolutionAssociation.spec.ts`).
      check(
        sent.calculation?.scope?.characterName === 'Nbt Anch' &&
          sent.calculation.scope.rating === 500,
        `A detail request did not carry the calculation's own scope: ${request.body}`
      )
      check(
        !Object.hasOwn(sent.calculation, 'inventoryCharacterName'),
        `A detail request sent a separate inventory character: ${request.body}`
      )
    }
    const unexpected = apiCalls.filter(
      (request) =>
        ![DISCOVERY_ROUTE, RESOLUTION_ROUTE, '/api/crafting/selector-options'].includes(request.path)
    )
    check(
      unexpected.length === 0,
      `The page called routes Discovery has no business calling: ${JSON.stringify(unexpected.map((r) => r.path))}`
    )
    const pageOrigin = new URL(stub.origin).origin
    const offOrigin = browserRequests.filter((url) => !url.startsWith(pageOrigin))
    check(
      offOrigin.length === 0,
      `The browser left this origin: ${JSON.stringify(offOrigin.slice(0, 5))}`
    )
    const upstream = browserRequests.filter((url) => /guildwars2\.com/i.test(url))
    check(upstream.length === 0, `The browser requested ArenaNet: ${JSON.stringify(upstream)}`)
    check(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`)
    record(
      'only Discovery\'s own routes, no Profit-only settings, no off-origin request',
      `${tableCalls.length} table, ${detailCalls.length} detail, ${browserRequests.length} browser requests`
    )

    console.log(`\nDiscovery browser smoke PASSED (${steps.length} steps).`)
    console.log(
      'Controlled responses only: rating, account-wide knowledge and normal-discovery eligibility ' +
        'stay the backend\'s and were not exercised against real data here.'
    )
  } finally {
    await browser.close()
    await stub.close()
  }
}

run().catch((error) => {
  console.error(`\nDiscovery browser smoke FAILED after ${steps.length} step(s): ${error.message}`)
  process.exitCode = 1
})
