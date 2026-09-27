/**
 * Real-browser check of the Crafting Profit information hierarchy: the comparison/detail split at a
 * wide and a narrow viewport, keyboard-operated result selection, selection by recipe identity across
 * sorting, and the detail region's own states (STORY-WEB-005,
 * `FRONTEND_UX_GUIDELINES.md` 4, 3, 5, 7, 8).
 *
 * Since STORY-WEB-006 it also covers DOMAIN_SPEC 2.1.1's result-display controls — the three filters,
 * the changeable maximum and Show all — and whole-row selection by pointer, on both viewports. Since
 * STORY-WEB-011 those controls are the *Displayed results* subgroup of the calculation-controls
 * panel, all three filters open enabled, and the removed explanatory paragraphs are checked to be
 * gone without the accessible description going with them. Since STORY-DOM-021 it also covers
 * 2.1.1's "Allow non-Trading-Post materials" *calculation* rule: its grouping and enabled default,
 * that switching it submits one calculation carrying it rather than filtering rows in the page, that
 * a reload keeps it for the table and its detail, and that the restricted result is kept and
 * explained beside the material it applies to.
 *
 * Since STORY-WEB-015 it also covers 2.1.1's narrowed selected-result content: the purchase list is
 * the counted crafts' only and the one-further-craft section is gone, the page's introductory sentence
 * is gone, a status label that only repeats its own sentence is not shown while every cause stays, a
 * missing price names the item it is about at the requirement it applies to, and a budget limit states
 * the supplied purchase cost and the echoed budget without inventing the amount that went over.
 *
 * Runs the built frontend against a *controlled* API boundary (`scripts/stubOrigin.mjs`): every
 * answer comes from this process, so no backend, database or GW2 API is involved and nothing can be
 * synchronized. It therefore evidences structure, layout and interaction — never real data, and never
 * page-load performance (`TARGET_ARCHITECTURE.md` 33).
 *
 * Usage:  npm run build && npm run smoke:profit
 * Environment:
 *   GW2_PROFIT_SMOKE_PORT  port for the stub origin  (default 5176)
 *   GW2_BROWSER_PATH       browser executable        (default: the first installed Chrome/Edge)
 *   GW2_SMOKE_TIMEOUT_MS   per-step wait             (default 30000)
 *
 * Exits 0 when every step passed, 1 otherwise.
 */
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startStubOrigin } from './stubOrigin.mjs'

const PORT = Number(process.env.GW2_PROFIT_SMOKE_PORT ?? 5176)
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
const DIST_DIR = fileURLToPath(new URL('../dist/', import.meta.url))

const WIDE = { width: 1440, height: 900 }
/** Wide enough for the two-column layout, short enough that the list is the taller column. */
const WIDE_SHORT = { width: 1440, height: 420 }
const NARROW = { width: 360, height: 800 }

const steps = []

function record(name, detail) {
  steps.push({ name, detail })
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

function check(condition, message) {
  if (!condition) throw new Error(message)
}

/**
 * Rows wide enough to need the table's own scrolling, covering the cases the hierarchy has to keep
 * apart: a gain, a loss, a blocked row, a row with no result, null next to zero, and the one state
 * DOMAIN_SPEC 2.1.1's "not allowed" filter matches.
 */
function allRows() {
  const row = (recipeId, outputName, overrides) => ({
    recipeId,
    outputItemId: 1000 + recipeId,
    outputName,
    outputCount: 1,
    disciplines: 'Armorsmith,Weaponsmith',
    minRating: 400,
    resultAvailable: true,
    craftableCount: 12,
    profitCopper: 12_345,
    totalProfitCopper: 148_140,
    // Deliberately not 12 x 111_110: a browser that worked the total out would disagree with this.
    totalSellValueCopper: 1_481_401,
    buyCostCopper: 98_765,
    matsSellValueCopper: 4_321,
    revenueCopper: 111_110,
    blockedReason: 'NONE',
    outputPrice: { buyUnitCopper: 120_000, sellUnitCopper: 130_000 },
    missingToBuy: [],
    missingToBuyOne: [],
    // This check is about the information hierarchy, not about images: every row here has no
    // retained icon metadata, so the shared component shows its fallback and asks for nothing.
    // Image delivery itself is `smoke:icons` and the live integration run (STORY-WEB-010).
    iconUrl: null,
    ...overrides
  })

  return [
    row(1, 'Deldrimor Steel Ingot', {
      missingToBuy: [
        { itemId: 19_700, itemName: 'Mithril Ore', quantity: 60, price: { buyUnitCopper: 120, sellUnitCopper: 140 }, iconUrl: null },
        { itemId: 19_701, itemName: null, quantity: 5, price: null, iconUrl: null }
      ],
      missingToBuyOne: [
        { itemId: 19_700, itemName: 'Mithril Ore', quantity: 5, price: { buyUnitCopper: 120, sellUnitCopper: 140 }, iconUrl: null }
      ]
    }),
    row(2, 'Elonian Leather Square', { blockedReason: 'BUYING_DISABLED', craftableCount: 0 }),
    row(3, 'Spiritwood Plank of Considerable Length and Name', {
      blockedReason: 'PRICE_UNAVAILABLE',
      profitCopper: null,
      totalProfitCopper: null,
      totalSellValueCopper: null,
      buyCostCopper: null,
      missingToBuy: [{ itemId: 19_699, itemName: 'Charged Core', quantity: 3, price: null, iconUrl: null }]
    }),
    row(4, 'Bolt of Damask', {
      resultAvailable: false,
      craftableCount: null,
      profitCopper: null,
      totalProfitCopper: null,
      totalSellValueCopper: null,
      buyCostCopper: null,
      matsSellValueCopper: null,
      revenueCopper: null,
      blockedReason: null,
      outputPrice: null,
      missingToBuy: null,
      missingToBuyOne: null
    }),
    row(5, 'Charged Quartz Crystal', { profitCopper: -3_400, totalProfitCopper: -40_800, craftableCount: 0 }),
    row(6, 'Mystic Clover Attempt', { blockedReason: 'RECIPE_NOT_ALLOWED' }),
    row(7, 'Self-Referential Ingot', { blockedReason: 'CYCLE_DETECTED' }),
    // A limit on *further* crafting reached after crafts were already counted, with the purchase
    // those crafts needed: the budget case whose supplied cost and budget the detail has to show
    // without inventing the amount that went over (DOMAIN_SPEC 2.1.1). Its supplied total profit is
    // the same as the first row's on purpose, so it sorts after it and the default selection is
    // unchanged by its presence.
    row(9, 'Vision Crystal', {
      blockedReason: 'INSUFFICIENT_BUDGET',
      craftableCount: 4,
      buyCostCopper: 240_000,
      missingToBuy: [
        {
          itemId: 19_721,
          itemName: 'Glob of Ectoplasm',
          quantity: 20,
          price: { buyUnitCopper: 2_400, sellUnitCopper: 2_600 },
          iconUrl: null
        }
      ]
    })
  ]
}

/**
 * The rows a freshly opened screen lists: DOMAIN_SPEC 2.1.1's three filters are all on to begin
 * with, so the two zero-count rows and the not-allowed one are out while the rows whose count and
 * profit the backend did not supply stay.
 */
const DEFAULT_LISTED = [
  'Deldrimor Steel Ingot',
  'Self-Referential Ingot',
  'Vision Crystal',
  'Spiritwood',
  'Bolt of Damask'
]

/** The row blocked by the maximum buy, after four crafts had already been counted. */
const BUDGET_ROW = 'Vision Crystal'

/** The two rows whose supplied craftable count is exactly 0. */
const ZERO_COUNT_ROWS = ['Elonian Leather Square', 'Charged Quartz Crystal']

/** The row the "not allowed" filter matches, and the one the "0 or less" filter matches. */
const NOT_ALLOWED_ROW = 'Mystic Clover Attempt'
const NON_POSITIVE_PROFIT_ROW = 'Charged Quartz Crystal'

/** The narrower scope deliberately drops recipe 1, so a replacement calculation can remove it. */
function rowsForScope(scopeKind) {
  return scopeKind === 'ALL' ? allRows() : allRows().filter((row) => row.recipeId !== 1)
}

/**
 * The row a calculation with "Allow non-Trading-Post materials" switched off returns, and only then
 * (DOMAIN_SPEC 2.1.1, UD-010): the backend kept the recipe and reported its restriction, so the
 * browser has a blocked result to explain rather than a row to delete. Its name is what proves the
 * listing changed because a *new calculation* answered, not because the page filtered anything.
 */
const RESTRICTED_ROW = {
  recipeId: 8,
  outputItemId: 1008,
  outputName: 'Bound Blade',
  outputCount: 1,
  disciplines: 'Weaponsmith',
  minRating: 400,
  resultAvailable: true,
  craftableCount: 3,
  profitCopper: 2_222,
  totalProfitCopper: 6_666,
  totalSellValueCopper: 60_000,
  buyCostCopper: 1_000,
  matsSellValueCopper: 500,
  revenueCopper: 20_000,
  blockedReason: 'NON_TRADEABLE_MATERIAL',
  outputPrice: { buyUnitCopper: 19_000, sellUnitCopper: 20_000 },
  missingToBuy: [],
  missingToBuyOne: [],
  iconUrl: null
}

/** Every row a request may be answered with, for the detail route's own candidate lookup. */
function everyKnownRow() {
  return [...allRows(), RESTRICTED_ROW]
}

/**
 * The tree for {@link RESTRICTED_ROW}: the restriction sits on the ingredient it applies to, while
 * that ingredient's tradeable sibling in the same craft carries none.
 */
function restrictedResolutionTree(recipeId) {
  return treeNode(1008, 'Bound Blade', {
    requestedQuantity: 1,
    missingQuantity: 1,
    recipeId,
    methods: [],
    states: ['BLOCKED'],
    blockedReasons: ['NON_TRADEABLE_MATERIAL'],
    cashCostCopper: null,
    opportunityCostCopper: null,
    effectiveCostCopper: null,
    children: [
      treeNode(19_695, 'Account Bound Scrap', {
        requestedQuantity: 3,
        missingQuantity: 3,
        states: ['BLOCKED'],
        blockedReasons: ['NON_TRADEABLE_MATERIAL'],
        cashCostCopper: null,
        opportunityCostCopper: null,
        effectiveCostCopper: null
      }),
      treeNode(19_700, 'Mithril Ore', {
        requestedQuantity: 2,
        boughtQuantity: 2,
        methods: ['BUY'],
        cashCostCopper: 240,
        opportunityCostCopper: 0,
        effectiveCostCopper: 240
      })
    ]
  })
}

/**
 * A resolution node with this script's defaults filled in (`TARGET_ARCHITECTURE.md` 13.3).
 *
 * The values are deliberately awkward for a browser that tried to work anything out: a parent's
 * inclusive cost is not the sum of its children's, `producedQuantity` exceeds `craftedQuantity`, and
 * one item appears in two branches with different quantities.
 */
function treeNode(itemId, itemName, overrides) {
  return {
    itemId,
    itemName,
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
  }
}

/**
 * A deliberately tall tree: split sourcing, a nested craft, a repeated item in two branches, a
 * blocked requirement with two reasons, a domain-established zero on an untradable item, costs the
 * backend could not establish, and a state code this client has no wording for.
 */
function resolutionTree(recipeId) {
  return treeNode(1000 + recipeId, 'Deldrimor Steel Ingot', {
    requestedQuantity: 1,
    craftedQuantity: 1,
    recipeId,
    craftCount: 1,
    producedQuantity: 4,
    characterName: 'Nbt Anch',
    methods: ['CRAFT'],
    cashCostCopper: 98_765,
    opportunityCostCopper: 4_321,
    effectiveCostCopper: 103_086,
    children: [
      treeNode(19_700, 'Mithril Ore', {
        requestedQuantity: 60,
        inventoryQuantity: 24,
        boughtQuantity: 36,
        methods: ['INVENTORY', 'BUY'],
        cashCostCopper: 4_320,
        opportunityCostCopper: 2_880,
        effectiveCostCopper: 7_200
      }),
      treeNode(19_698, 'Lump of Mithril', {
        requestedQuantity: 2,
        craftedQuantity: 2,
        recipeId: 7_777,
        craftCount: 1,
        producedQuantity: 5,
        methods: ['CRAFT'],
        cashCostCopper: 1_500,
        opportunityCostCopper: null,
        effectiveCostCopper: null,
        children: [
          treeNode(19_700, 'Mithril Ore', {
            requestedQuantity: 10,
            inventoryQuantity: 10,
            methods: ['INVENTORY'],
            cashCostCopper: 0,
            opportunityCostCopper: 1_200,
            effectiveCostCopper: 1_200
          }),
          treeNode(19_699, 'Charged Core', {
            requestedQuantity: 3,
            missingQuantity: 3,
            states: ['BLOCKED', 'PRICE_UNAVAILABLE'],
            blockedReasons: ['PRICE_UNAVAILABLE', 'BUYING_DISABLED'],
            cashCostCopper: null,
            opportunityCostCopper: null,
            effectiveCostCopper: null
          })
        ]
      }),
      treeNode(19_697, 'Bound Crafting Token', {
        requestedQuantity: 4,
        inventoryQuantity: 4,
        methods: ['INVENTORY'],
        states: ['UNVALUED_NONTRADEABLE'],
        cashCostCopper: 0,
        opportunityCostCopper: 0,
        effectiveCostCopper: 0
      }),
      treeNode(19_696, null, {
        requestedQuantity: 1,
        missingQuantity: 1,
        methods: ['SOME_METHOD_ADDED_LATER'],
        states: ['SOME_STATE_ADDED_LATER'],
        blockedReasons: ['SOME_REASON_ADDED_LATER'],
        cashCostCopper: null,
        opportunityCostCopper: null,
        effectiveCostCopper: null
      })
    ]
  })
}

/** Every node of that tree, in document order, so a check can assert nothing was cut off. */
const TREE_ITEM_ORDER = [
  'Deldrimor Steel Ingot',
  'Mithril Ore',
  'Lump of Mithril',
  'Mithril Ore',
  'Charged Core',
  'Bound Crafting Token',
  'Item #19696'
]

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
    const request = body === '' ? {} : JSON.parse(body)
    const scope = request.scope ?? { kind: 'ALL' }
    // A different material rule is a different calculation, so this answer's rows differ too.
    const rows = request.settings?.allowNonTradeableMaterials === false
      ? [...rowsForScope(scope.kind), RESTRICTED_ROW]
      : rowsForScope(scope.kind)
    return sendJson(200, {
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
        dailyBuyInsteadOfCraft: true,
        // DOMAIN_SPEC 2.1.1 / UD-009: the option opens enabled.
        allowNonTradeableMaterials: true
      },
      rowCount: rows.length,
      rows
    })
  }
  if (url.pathname === '/api/crafting/profit/resolution') {
    const request = JSON.parse(body)
    const scope = request.calculation?.scope ?? { kind: 'ALL' }
    const row = everyKnownRow().find((candidate) => candidate.recipeId === request.recipeId)
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
        scope: {
          kind: scope.kind,
          discipline: scope.discipline ?? null,
          characterName: scope.characterName ?? null,
          rating: scope.rating ?? 0
        },
        settings: request.calculation?.settings ?? null
      },
      consistency: 'FRESH_CALCULATION',
      calculatedAt: '2026-09-25T09:00:00Z',
      row,
      treeStatus: 'AVAILABLE',
      treeBasis: 'SINGLE_OUTPUT_REQUIREMENT',
      tree: request.recipeId === RESTRICTED_ROW.recipeId
        ? restrictedResolutionTree(request.recipeId)
        : resolutionTree(request.recipeId)
    })
  }
  sendJson(404, { error: 'NOT_FOUND', message: url.pathname })
}

async function openProfit(page, origin, viewport) {
  await page.setViewportSize(viewport)
  await page.goto(`${origin}/#/crafting`, { waitUntil: 'networkidle', timeout: TIMEOUT_MS })
  await page.reload({ waitUntil: 'networkidle', timeout: TIMEOUT_MS })
  await page.waitForSelector('[data-test="profit-table"]', { timeout: TIMEOUT_MS })
}

async function boxOf(page, selector) {
  return page.$eval(selector, (element) => {
    const rect = element.getBoundingClientRect()
    return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: rect.width }
  })
}

/**
 * Horizontal page overflow, and — when there is any — the elements actually reaching past the
 * viewport, so a failure names what overflowed instead of only that something did.
 */
async function pageOverflow(page) {
  return page.evaluate(() => {
    const clientWidth = document.documentElement.clientWidth
    const offenders = []
    if (document.documentElement.scrollWidth > clientWidth + 1) {
      for (const element of document.body.querySelectorAll('*')) {
        const box = element.getBoundingClientRect()
        if (box.right <= clientWidth + 1 || box.width === 0) continue
        offenders.push(
          `${element.tagName.toLowerCase()}` +
            `${element.className === '' ? '' : `.${String(element.className).split(/\s+/).join('.')}`}` +
            ` right=${Math.round(box.right)} w=${Math.round(box.width)}`
        )
      }
    }
    return { scrollWidth: document.documentElement.scrollWidth, clientWidth, offenders }
  })
}

async function textOf(page, selector) {
  return (await page.$eval(selector, (element) => element.textContent ?? '')).replace(/\s+/g, ' ').trim()
}

/**
 * Requests to exactly one path. `requestsTo` matches by prefix, which would count the detail route
 * as a table calculation — two different contracts that these checks have to keep apart.
 */
function requestsToPath(stub, path) {
  return stub.requests.filter((request) => request.path === path)
}

/** Waits until this process has answered `atLeast` requests to `path`, or fails naming the shortfall. */
async function waitForRequestCount(stub, path, atLeast) {
  const deadline = Date.now() + TIMEOUT_MS
  while (requestsToPath(stub, path).length < atLeast) {
    if (Date.now() > deadline) {
      throw new Error(
        `Timed out waiting for ${atLeast} requests to ${path}; ` +
          `${requestsToPath(stub, path).length} arrived.`
      )
    }
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
}

async function listedRecipes(page) {
  return page.$$eval('[data-test="profit-row"] .recipe-name', (names) =>
    names.map((name) => (name.textContent ?? '').replace(/\s+/g, ' ').trim())
  )
}

async function displayControlState(page) {
  return page.evaluate(() => {
    const checked = (test) => document.querySelector(`[data-test="${test}"]`)?.checked ?? null
    const controls = document.querySelector('[data-test="display-controls"]')
    return {
      inControlsPanel:
        document.querySelector('[data-test="calculation-controls"]')?.closest('.panel')?.contains(controls) ??
        false,
      inResultsRegion: document.querySelector('[data-test="results-region"]')?.contains(controls) ?? false,
      legend: controls?.querySelector('legend')?.textContent?.trim() ?? null,
      calculationLegend:
        document.querySelector('[data-test="calculation-controls"] legend')?.textContent?.trim() ?? null,
      holdsSearch: controls?.contains(document.querySelector('[data-test="search"]')) ?? false,
      zeroCraftable: checked('filter-zero-craftable'),
      notAllowed: checked('filter-not-allowed'),
      nonPositiveProfit: checked('filter-non-positive-profit'),
      showAll: checked('show-all'),
      maximum: document.querySelector('[data-test="max-displayed"]')?.value ?? null,
      maximumDisabled: document.querySelector('[data-test="max-displayed"]')?.disabled ?? null
    }
  })
}

/** Switches DOMAIN_SPEC 2.1.1's three filters off, so a check about something else sees every row. */
async function showEveryRow(page) {
  await page.setChecked('[data-test="filter-zero-craftable"]', false)
  await page.setChecked('[data-test="filter-not-allowed"]', false)
  await page.setChecked('[data-test="filter-non-positive-profit"]', false)
}

/** Types a maximum and commits it the way a person does: entry, then leaving the control. */
async function setMaximum(page, value) {
  await page.fill('[data-test="max-displayed"]', value)
  await page.dispatchEvent('[data-test="max-displayed"]', 'change')
}

/**
 * WCAG contrast of the treatments this story added, measured from what the browser actually
 * rendered rather than from token values. The chip and node-state treatments are scoped component
 * styles, so a detached probe element would not match them — each sample is a real element on screen.
 */
async function measureTreeContrast(page, named) {
  return page.evaluate((selectors) => {
    const channel = (value) => {
      const srgb = value / 255
      return srgb <= 0.03928 ? srgb / 12.92 : ((srgb + 0.055) / 1.055) ** 2.4
    }
    const luminance = ([r, g, b]) => 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    const parse = (color) => (color.match(/\d+(\.\d+)?/g) ?? ['0', '0', '0']).map(Number)
    const opaqueBackgroundOf = (element) => {
      let node = element
      while (node !== null) {
        const background = getComputedStyle(node).backgroundColor
        if (background !== 'rgba(0, 0, 0, 0)' && background !== 'transparent') return background
        node = node.parentElement
      }
      return getComputedStyle(document.body).backgroundColor
    }
    return selectors.flatMap(([name, selector]) => {
      const element = document.querySelector(selector)
      if (element === null) return []
      const style = getComputedStyle(element)
      const foreground = parse(style.color)
      const background = parse(opaqueBackgroundOf(element))
      const light = Math.max(luminance(foreground), luminance(background))
      const dark = Math.min(luminance(foreground), luminance(background))
      return [
        {
          name,
          color: style.color,
          fontSize: Number.parseFloat(style.fontSize),
          ratio: (light + 0.05) / (dark + 0.05)
        }
      ]
    })
  }, named)
}

async function focusState(page) {
  return page.evaluate(() => {
    const active = document.activeElement
    if (active === null) return null
    const style = getComputedStyle(active)
    return {
      test: active.getAttribute('data-test'),
      text: (active.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 48),
      outlineStyle: style.outlineStyle,
      outlineWidth: Number.parseFloat(style.outlineWidth)
    }
  })
}

async function run() {
  check(existsSync(`${DIST_DIR}index.html`), 'frontend/dist is missing — run `npm run build` first.')

  const browserPath = resolveBrowserPath()
  const stub = await startStubOrigin({ port: PORT, distDir: DIST_DIR, answerApi })
  console.log(`Browser : ${browserPath}`)
  console.log(`Page    : ${stub.origin} (stub backend in this process)\n`)

  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage({ viewport: WIDE })
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))

  try {
    await openProfit(page, stub.origin, WIDE)
    check(
      stub.servedCount() > 0,
      `The page at ${stub.origin} was not served by this script — stop whatever else is listening ` +
        'on that port. Nothing was checked.'
    )
    record('page served by this script', `${stub.servedCount()} requests answered so far`)

    // 1. Wide: the comparison region and the detail region sit side by side, both bounded.
    const wideResults = await boxOf(page, '[data-test="results-region"]')
    const wideDetail = await boxOf(page, '[data-test="selected-detail"]')
    check(
      wideDetail.left >= wideResults.right - 1,
      `At ${WIDE.width}px the detail did not sit beside the results ` +
        `(results right ${wideResults.right}, detail left ${wideDetail.left}).`
    )
    check(
      wideDetail.width >= 300 && wideDetail.width <= 460,
      `The detail region is not bounded at ${WIDE.width}px: ${wideDetail.width}px.`
    )
    const wideOverflow = await pageOverflow(page)
    check(
      wideOverflow.scrollWidth <= wideOverflow.clientWidth + 1,
      `The page scrolls horizontally at ${WIDE.width}px (${wideOverflow.scrollWidth} > ${wideOverflow.clientWidth}).`
    )
    record(
      `results and detail side by side at ${WIDE.width}×${WIDE.height}`,
      `results ${Math.round(wideResults.width)}px, detail ${Math.round(wideDetail.width)}px, no page overflow`
    )

    // 2. The detail says what it is for before anything is selected.
    check(
      (await textOf(page, '[data-test="detail-placeholder"]')).includes('Choose a recipe'),
      'With nothing selected the detail region did not say what to do.'
    )
    record('detail region states its empty case')

    // 3. Selection is operable from the keyboard alone, with focus visible on the control used.
    await page.focus('[data-test="profit-table"] tbody [data-test="select-row"]')
    const focusedControl = await focusState(page)
    check(
      focusedControl?.test === 'select-row',
      `Tabbing into the table did not reach a row control: ${JSON.stringify(focusedControl)}`
    )
    check(
      focusedControl.outlineStyle !== 'none' && focusedControl.outlineWidth >= 1,
      `Keyboard focus was not visible on the row control: ${JSON.stringify(focusedControl)}`
    )
    const firstRowName = await textOf(page, '[data-test="profit-row"] .recipe-name')
    await page.keyboard.press('Enter')
    await page.waitForSelector('[data-test="detail-name"]', { timeout: TIMEOUT_MS })
    check(
      (await textOf(page, '[data-test="detail-name"]')) === firstRowName,
      `Enter opened "${await textOf(page, '[data-test="detail-name"]')}" rather than "${firstRowName}".`
    )
    const stillFocused = await focusState(page)
    check(stillFocused?.test === 'select-row', 'Selecting a row moved focus away from the control used.')
    record('result selection is keyboard-operable', `Enter selected "${firstRowName}", focus kept and visible`)

    // 4. Selection is marked for sight and for assistive technology, and follows the recipe, not the row.
    const selection = await page.evaluate(() => {
      const row = document.querySelector('[data-test="profit-row"][aria-current="true"]')
      if (row === null) return null
      const style = getComputedStyle(row)
      return {
        current: row.getAttribute('aria-current'),
        background: style.backgroundColor,
        marked: row.querySelector('.recipe-marker')?.textContent?.trim() ?? '',
        hiddenLabel: row.querySelector('.visually-hidden')?.textContent?.trim() ?? '',
        count: document.querySelectorAll('[data-test="profit-row"][aria-current="true"]').length
      }
    })
    check(selection !== null && selection.count === 1, `Expected exactly one marked row: ${JSON.stringify(selection)}`)
    check(
      selection.marked !== '' && selection.hiddenLabel === 'Selected',
      `Selection is not carried by anything but color: ${JSON.stringify(selection)}`
    )
    record('selection is marked by a glyph, a surface and aria-current', `hidden label "${selection.hiddenLabel}"`)

    await page.click('[data-test="sort-outputName"]')
    await page.waitForFunction(
      (expected) =>
        document.querySelector('[data-test="profit-row"] .recipe-name')?.textContent?.trim() !== expected,
      firstRowName,
      { timeout: TIMEOUT_MS }
    )
    check(
      (await textOf(page, '[data-test="detail-name"]')) === firstRowName,
      'Sorting moved the detail onto a different recipe.'
    )
    const stillMarked = await page.$eval(
      '[data-test="profit-row"][aria-current="true"] .recipe-name',
      (element) => element.textContent?.trim() ?? ''
    )
    check(stillMarked === firstRowName, `After sorting the marked row was "${stillMarked}".`)
    record('selection follows the recipe, not the row position', `"${firstRowName}" still selected after re-sorting`)

    // 5. The detail shows the supplementary values under the basis the contract gives them.
    const detailText = await textOf(page, '[data-test="selected-detail"]')
    for (const expected of [
      'For one craft',
      'For all 12 crafts counted',
      'Output revenue',
      'Cost of materials to buy',
      'Output quantity',
      'Trading Post price / item',
      'Instant buy',
      'Crafting resolution',
      'Materials still to buy',
      'Mithril Ore',
      'Item #19701'
    ]) {
      check(detailText.includes(expected), `The detail region did not contain "${expected}".`)
    }
    check(
      !detailText.includes('shopping list total'),
      'The detail region claimed a total it must not calculate.'
    )
    record('detail separates summary, resolution and materials', '11 expected labels present')

    // 5a. The purchases are the ones for the crafts already counted, and there is no second list
    // (DOMAIN_SPEC 2.1.1). This row supplies both bases — 60 Mithril Ore for the 12 crafts counted
    // and 5 for one further craft — so a section that showed the wrong one would be visible here.
    const purchases = await page.evaluate(() => {
      const line = (element) => (element.textContent ?? '').replace(/\s+/g, ' ').trim()
      return {
        counted: [...document.querySelectorAll('[data-test="missing-item"]')].map(line),
        furtherSection: document.querySelector('[data-test="missing-one"]') !== null,
        furtherItems: document.querySelectorAll('[data-test="missing-one-item"]').length,
        furtherEmptyState: document.querySelector('[data-test="missing-one-none"]') !== null
      }
    })
    check(
      purchases.counted.length === 2 &&
        purchases.counted[0].includes('Mithril Ore') &&
        purchases.counted[0].includes('×60') &&
        purchases.counted[0].includes('Instant buy 1s 20c'),
      `The counted-craft purchase list is not what the backend supplied: ${JSON.stringify(purchases.counted)}`
    )
    check(
      // No name for this material, so its id identifies it and its absent quote says so.
      purchases.counted[1].includes('Item #19701') && purchases.counted[1].includes('No price supplied'),
      `A material with no supplied name or price was not reported as such: ${purchases.counted[1]}`
    )
    check(
      !purchases.furtherSection && purchases.furtherItems === 0 && !purchases.furtherEmptyState,
      `The removed one-further-craft purchase section is still rendered: ${JSON.stringify(purchases)}`
    )
    check(
      !detailText.includes('For one further craft'),
      'The one-further-craft basis heading is still on screen.'
    )
    record(
      'purchases are the counted crafts’ only, with quantities and supplied quotes',
      purchases.counted.join(' | ').slice(0, 96)
    )

    // 5a2. DOMAIN_SPEC 2.1.1 removes the page's introductory sentence; the heading stays.
    const introduction = await page.evaluate(() => ({
      intro: document.querySelector('[data-test="page-intro"]') !== null,
      heading: document.querySelector('[data-page-heading]')?.textContent?.trim() ?? null,
      body: (document.body.textContent ?? '').replace(/\s+/g, ' ')
    }))
    check(
      !introduction.intro &&
        !introduction.body.includes('Crafting opportunities the backend calculated for the selected scope'),
      'The introductory sentence DOMAIN_SPEC 2.1.1 removes is still on the page.'
    )
    check(introduction.heading === 'Crafting Profit', `The page heading read "${introduction.heading}".`)
    record('no introductory sentence under the heading', `heading "${introduction.heading}"`)

    // 5b. Buy cost is emphasized as a cost, and the quote is identified as a single-item price.
    const costPresentation = await page.evaluate(() => {
      const cost = document.querySelector('[data-test="detail-buy-cost"]')
      const label = [...document.querySelectorAll('[data-test="detail-totals"] dt')]
        .map((term) => term.textContent?.trim() ?? '')
        .find((text) => text.toLowerCase().includes('cost'))
      const costStyle = getComputedStyle(cost)
      const plain = getComputedStyle(document.querySelector('[data-test="detail-per-craft"] dd'))
      return {
        label: label ?? null,
        color: costStyle.color,
        weight: Number.parseInt(costStyle.fontWeight, 10),
        size: Number.parseFloat(costStyle.fontSize),
        plainColor: plain.color,
        plainSize: Number.parseFloat(plain.fontSize),
        danger: getComputedStyle(document.documentElement).getPropertyValue('--color-danger').trim()
      }
    })
    check(
      costPresentation.label !== null && costPresentation.label.toLowerCase().startsWith('cost'),
      `Buy cost is not labelled with cost wording: ${JSON.stringify(costPresentation)}`
    )
    check(
      costPresentation.color !== costPresentation.plainColor,
      `Buy cost does not use the cost treatment: ${JSON.stringify(costPresentation)}`
    )
    check(
      costPresentation.size > costPresentation.plainSize,
      `Buy cost is not emphasized over ordinary values: ${JSON.stringify(costPresentation)}`
    )
    // The concise label carries the basis now; the paragraph that used to explain it is gone, and
    // the output quantity stays with the crafting values (DOMAIN_SPEC 2.1.1).
    check(
      (await textOf(page, '[data-test="detail-quote-heading"]')) === 'Trading Post price / item',
      'The Trading Post quote does not carry the concise per-item label.'
    )
    check(
      (await page.$('[data-test="detail-quote-basis"]')) === null,
      'The removed unit-price explanatory paragraph is still on screen.'
    )
    check(
      (await textOf(page, '[data-test="detail-per-craft"]')).includes('Output quantity'),
      'The output quantity was removed along with the unit-price prose.'
    )
    record(
      'buy cost reads as a cost and the quote as a single-item price',
      `"${costPresentation.label}" in ${costPresentation.color} at ${costPresentation.size}px ` +
        `(${costPresentation.plainSize}px elsewhere)`
    )

    // 5c. The resolution region holds the backend's tree, whole and in order.
    const resolutionRequests = stub.requests.filter(
      (request) => request.path === '/api/crafting/profit/resolution'
    )
    check(
      resolutionRequests.length === 1,
      `Selecting one recipe made ${resolutionRequests.length} detail requests, not 1.`
    )
    const sentDetail = JSON.parse(resolutionRequests[0].body)
    check(
      sentDetail.recipeId === 1 &&
        sentDetail.calculation.scope.kind === 'ALL' &&
        sentDetail.calculation.settings.maxBuyCopper === 250_000,
      `The detail request did not carry the table's own effective inputs: ${resolutionRequests[0].body}`
    )
    check(
      sentDetail.row === undefined && sentDetail.prices === undefined,
      `The detail request carried more than the contract allows: ${resolutionRequests[0].body}`
    )
    const treeItems = await page.$$eval('[data-test="tree-node"] [data-test="node-name"]', (names) =>
      names.map((name) => name.textContent?.trim() ?? '')
    )
    check(
      JSON.stringify(treeItems) === JSON.stringify(TREE_ITEM_ORDER),
      `The tree was not rendered whole and in order: ${treeItems.join(', ')}`
    )
    const resolutionText = await textOf(page, '[data-test="selected-detail"]')
    for (const expected of [
      'The requested recipe 1 is the recipe selected',
      'one output batch',
      'Price missing',
      'Not tradable, valued at zero',
      'SOME_STATE_ADDED_LATER (not recognized)',
      'already includes everything below'
    ]) {
      check(resolutionText.includes(expected), `The resolution region did not contain "${expected}".`)
    }
    record(
      'the backend tree is rendered whole, in order, with its own states',
      `${treeItems.length} nodes, one detail request carrying scope and settings only`
    )

    // 5c2. A missing price names the item it is about, at the requirement it applies to
    // (DOMAIN_SPEC 2.1.1), while the requirement above it — which has a price — carries neither the
    // state nor the reason.
    const missingPrice = await page.evaluate(() => {
      const own = (node, test) =>
        [...node.querySelectorAll(`:scope > [data-test="${test}"]`)].map(
          (element) => (element.textContent ?? '').replace(/\s+/g, ' ').trim()
        )
      const nodes = [...document.querySelectorAll('[data-test="tree-node"]')]
      const byName = (name) =>
        nodes.find(
          (node) => (node.querySelector('[data-test="node-name"]')?.textContent ?? '').trim() === name
        )
      const facts = (name) => {
        const node = byName(name)
        if (node === undefined) return null
        return {
          states: own(node, 'node-state-explanation'),
          reasons: own(node, 'node-blocked-explanation')
        }
      }
      return { affected: facts('Charged Core'), parent: facts('Lump of Mithril') }
    })
    check(
      missingPrice.affected !== null &&
        missingPrice.affected.states.some((sentence) =>
          sentence.includes('No purchase price is available for Charged Core')
        ) &&
        missingPrice.affected.reasons.some((sentence) =>
          sentence.includes('no price is available for Charged Core')
        ),
      `The missing price does not name the item it is about: ${JSON.stringify(missingPrice.affected)}`
    )
    check(
      missingPrice.parent !== null &&
        missingPrice.parent.states.length === 0 &&
        missingPrice.parent.reasons.length === 0,
      `A requirement with a price was marked as missing one: ${JSON.stringify(missingPrice.parent)}`
    )
    record(
      'the item with no price is named beside its own requirement',
      missingPrice.affected.reasons.join(' ').slice(0, 96)
    )

    // 5c3. The selected detail does not repeat a label its own sentence already carries (2.1.1).
    // This row is unblocked, so "Not blocked" is exactly the label that must be gone while the
    // sentence stays. The retained tree chips are a node's own codes, not this row's status.
    const unblockedStatus = await page.evaluate(() => ({
      badge: document.querySelector('[data-test="detail-status"]') !== null,
      explanation:
        document.querySelector('[data-test="detail-status-explanation"]')?.textContent?.trim() ?? null,
      statusText: (document.querySelector('[data-test="selected-detail"]')?.textContent ?? '')
        .replace(/\s+/g, ' ')
    }))
    check(
      !unblockedStatus.badge && !unblockedStatus.statusText.includes('Not blocked'),
      `The redundant "Not blocked" label is still in the detail: ${JSON.stringify(unblockedStatus)}`
    )
    check(
      unblockedStatus.explanation === 'Nothing blocked the calculation for this recipe.',
      `The state sentence went with the label: ${unblockedStatus.explanation}`
    )
    record('no redundant status label on an unblocked result', unblockedStatus.explanation)

    // 5d. Child groups expand and collapse from the keyboard alone.
    const groupSummary = '[data-test="node-children"] > summary'
    await page.focus(groupSummary)
    const focusedSummary = await focusState(page)
    check(
      focusedSummary !== null && focusedSummary.text.includes('ingredient requirement'),
      `Focus did not reach a child group: ${JSON.stringify(focusedSummary)}`
    )
    const openBefore = await page.$$eval('[data-test="node-children"]', (groups) =>
      groups.filter((group) => group.open).length
    )
    await page.keyboard.press('Enter')
    const openAfter = await page.$$eval('[data-test="node-children"]', (groups) =>
      groups.filter((group) => group.open).length
    )
    check(openAfter < openBefore, `Enter did not collapse a child group (${openBefore} → ${openAfter}).`)
    await page.keyboard.press('Enter')
    check(
      (await page.$$eval('[data-test="node-children"]', (groups) => groups.filter((g) => g.open).length)) ===
        openBefore,
      'Enter did not expand the child group again.'
    )
    record(
      'child groups open by default and toggle from the keyboard',
      `${openBefore} groups open initially, ${openAfter} after Enter`
    )

    // 5e. Wide: the detail panel carries the sticky treatment, and a detail taller than the viewport
    // is still reachable to its last line rather than having its foot pinned off-screen.
    const sticky = await page.evaluate(() => {
      const aside = document.querySelector('[data-test="selected-detail"]')
      const style = getComputedStyle(aside)
      return {
        position: style.position,
        overflowY: style.overflowY,
        tabIndex: aside.tabIndex,
        scrollHeight: aside.scrollHeight,
        clientHeight: aside.clientHeight,
        viewport: window.innerHeight
      }
    })
    check(sticky.position === 'sticky', `The detail panel is not sticky at ${WIDE.width}px: ${sticky.position}`)
    check(
      sticky.scrollHeight > sticky.clientHeight,
      `The detail is not taller than its box, so scrolling proves nothing: ${JSON.stringify(sticky)}`
    )
    check(sticky.tabIndex >= 0, 'The scrollable detail panel cannot be focused for keyboard scrolling.')
    const afterScroll = await page.evaluate(() => {
      const aside = document.querySelector('[data-test="selected-detail"]')
      window.scrollTo(0, document.documentElement.scrollHeight)
      aside.scrollTop = aside.scrollHeight
      const last = aside.querySelector('[data-test="detail-diagnostics"]')
      const asideBox = aside.getBoundingClientRect()
      const lastBox = last.getBoundingClientRect()
      return {
        asideTop: asideBox.top,
        asideBottom: asideBox.bottom,
        lastTop: lastBox.top,
        lastBottom: lastBox.bottom,
        viewport: window.innerHeight
      }
    })
    check(
      afterScroll.lastBottom <= afterScroll.viewport + 1 && afterScroll.lastTop >= -1,
      `The foot of the detail could not be reached: ${JSON.stringify(afterScroll)}`
    )
    await page.evaluate(() => window.scrollTo(0, 0))
    record(
      'the whole detail stays reachable when it is taller than its box',
      `${sticky.scrollHeight}px of detail in a ${sticky.clientHeight}px box, ` +
        `foot reached at y=${Math.round(afterScroll.lastTop)}`
    )

    // 5f. That the panel really sticks is only observable where the list beside it is the taller
    // column — otherwise the grid row is the panel's own height and there is nothing to travel
    // through. A short wide viewport is that case, and is a real one: the breakpoint is a width.
    await page.setViewportSize(WIDE_SHORT)
    const pinned = await page.evaluate(() => {
      const split = document.querySelector('.results-split')
      const main = document.querySelector('[data-test="results-region"]')
      const aside = document.querySelector('[data-test="selected-detail"]')
      const flowTop = aside.getBoundingClientRect().top
      const splitTop = split.getBoundingClientRect().top + window.scrollY
      window.scrollTo(0, splitTop + 120)
      const asideBox = aside.getBoundingClientRect()
      return {
        stickyOffset: Number.parseFloat(getComputedStyle(aside).top),
        flowTop,
        pinnedTop: asideBox.top,
        asideHeight: asideBox.height,
        mainHeight: main.getBoundingClientRect().height,
        splitBottom: split.getBoundingClientRect().bottom,
        viewport: window.innerHeight
      }
    })
    check(
      pinned.mainHeight > pinned.asideHeight,
      `The list is not the taller column, so sticking cannot be observed: ${JSON.stringify(pinned)}`
    )
    check(
      Math.abs(pinned.pinnedTop - pinned.stickyOffset) <= 1,
      `The panel did not stay pinned while the page scrolled past it: ${JSON.stringify(pinned)}`
    )
    check(
      pinned.flowTop > pinned.pinnedTop,
      `The panel never left its flow position, so nothing was scrolled: ${JSON.stringify(pinned)}`
    )
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.setViewportSize(WIDE)
    record(
      `the detail stays pinned beside a longer list at ${WIDE_SHORT.width}×${WIDE_SHORT.height}`,
      `top ${Math.round(pinned.pinnedTop)}px after scrolling 120px past a ` +
        `${Math.round(pinned.mainHeight)}px list, panel ${Math.round(pinned.asideHeight)}px`
    )

    // 5g. The tree's own treatments are readable. `smoke:layout` measures the shared ones, but the
    // chips and node states are scoped component styles it never has a rendered instance of.
    const treeContrast = await measureTreeContrast(page, [
      ['method chip', '[data-test="node-method"].chip--method'],
      ['unrecognized code chip', '[data-test="node-method"].chip--unknown'],
      ['node state', '[data-test="node-state"].status--caution'],
      ['unrecognized node state', '[data-test="node-state"].status--unknown'],
      ['node cost basis note', '.node__basis'],
      ['node explanation', '.node__note']
    ])
    check(treeContrast.length >= 5, `Too few tree samples measured: ${JSON.stringify(treeContrast)}`)
    const dimSamples = treeContrast.filter((sample) => sample.ratio < 4.5)
    check(
      dimSamples.length === 0,
      `Insufficient contrast in the tree: ${dimSamples
        .map((sample) => `${sample.name} ${sample.ratio.toFixed(2)}:1`)
        .join(', ')}`
    )
    record(
      `${treeContrast.length} tree text/background pairs meet WCAG AA`,
      `lowest ${treeContrast
        .reduce((worst, sample) => (sample.ratio < worst.ratio ? sample : worst))
        .name} at ${Math.min(...treeContrast.map((sample) => sample.ratio)).toFixed(2)}:1`
    )

    // 6. The display controls are the Displayed results subgroup of the controls panel, in
    // DOMAIN_SPEC 2.1.1's initial state, and the removed paragraphs are not on screen.
    const calculationsBeforeDisplayControls = requestsToPath(stub, '/api/crafting/profit').length
    const detailsBeforeDisplayControls = requestsToPath(stub, '/api/crafting/profit/resolution').length
    const defaults = await displayControlState(page)
    check(
      defaults.inControlsPanel && !defaults.inResultsRegion,
      `The display controls are not a subgroup of the calculation-controls panel: ${JSON.stringify(defaults)}`
    )
    check(
      defaults.legend === 'Displayed results' && defaults.calculationLegend === 'Calculation',
      `The two subgroups are not labelled as specified: ${JSON.stringify(defaults)}`
    )
    check(defaults.holdsSearch, 'The search over the listed rows is not in the Displayed results group.')
    check(
      defaults.zeroCraftable === true &&
        defaults.notAllowed === true &&
        defaults.nonPositiveProfit === true &&
        defaults.showAll === false &&
        defaults.maximum === '250',
      `The display controls did not open in their specified state: ${JSON.stringify(defaults)}`
    )
    const removedProse = await page.evaluate(() => ({
      groupNote: document.querySelector('[data-test="display-controls"]').textContent.includes(
        'never recalculate anything'
      ),
      tableNote: document.querySelector('[data-test="table-note"]') !== null,
      limitNote: document.querySelector('[data-test="limit-note"]') !== null,
      // Removed from view, not from the accessible tree: the table keeps its own description.
      caption: (document.querySelector('[data-test="profit-table"] caption')?.textContent ?? '').includes(
        'button that opens its details'
      )
    }))
    check(
      !removedProse.groupNote && !removedProse.tableNote && !removedProse.limitNote,
      `A paragraph DOMAIN_SPEC 2.1.1 removes is still on screen: ${JSON.stringify(removedProse)}`
    )
    check(removedProse.caption, 'The table lost its accessible description along with the visible prose.')
    const listedAtStart = await listedRecipes(page)
    for (const hidden of [...ZERO_COUNT_ROWS, NOT_ALLOWED_ROW]) {
      check(
        !listedAtStart.some((name) => name.startsWith(hidden)),
        `"${hidden}" is hidden by a filter that opens enabled and was still listed: ${listedAtStart.join(', ')}`
      )
    }
    check(
      listedAtStart.some((name) => name.startsWith('Bolt of Damask')),
      'The row with no supplied craftable count was hidden as if its count were 0.'
    )
    check(
      listedAtStart.some((name) => name.startsWith('Spiritwood')),
      'A row whose profit the backend did not supply was hidden as if it were 0 or less.'
    )
    check(
      listedAtStart.length === DEFAULT_LISTED.length,
      `Expected ${DEFAULT_LISTED.length} rows listed by default, got ${listedAtStart.join(', ')}`
    )
    record(
      'Displayed results subgroup opens with all three filters on and no explanatory prose',
      `${listedAtStart.length} of ${allRows().length} rows listed, maximum 250`
    )

    // 7. Each filter is reversible on its own, and changes nothing but what is listed.
    await page.setChecked('[data-test="filter-not-allowed"]', false)
    check(
      (await listedRecipes(page)).some((name) => name.startsWith(NOT_ALLOWED_ROW)),
      'Switching the "not allowed" filter off did not bring its row back.'
    )
    check(
      !(await listedRecipes(page)).some((name) => name.startsWith(NON_POSITIVE_PROFIT_ROW)),
      'Switching one filter off also released a row a different filter holds.'
    )
    await page.setChecked('[data-test="filter-zero-craftable"]', false)
    await page.setChecked('[data-test="filter-non-positive-profit"]', false)
    const allFiltersOff = await listedRecipes(page)
    check(
      allFiltersOff.length === allRows().length,
      `Switching all three filters off listed ${allFiltersOff.length} of ${allRows().length} rows.`
    )
    await page.setChecked('[data-test="filter-not-allowed"]', true)
    const afterNotAllowedAgain = await listedRecipes(page)
    check(
      !afterNotAllowedAgain.some((name) => name.startsWith(NOT_ALLOWED_ROW)) &&
        afterNotAllowedAgain.some((name) => name.startsWith(NON_POSITIVE_PROFIT_ROW)),
      `Switching one filter back on removed more than it names: ${afterNotAllowedAgain.join(', ')}`
    )
    // Reached by Tab from the search box rather than focused by script, so `:focus-visible` is
    // decided the way it is for a person using the keyboard.
    await page.focus('[data-test="search"]')
    let focusedFilter = null
    const walked = []
    for (let step = 0; step < 12 && focusedFilter === null; step += 1) {
      await page.keyboard.press('Tab')
      const current = await focusState(page)
      walked.push(current?.test ?? '(unnamed)')
      if (current?.test === 'filter-zero-craftable') focusedFilter = current
    }
    check(focusedFilter !== null, `Tab never reached the display filters: ${walked.join(' → ')}`)
    check(
      focusedFilter.outlineStyle !== 'none' && focusedFilter.outlineWidth >= 1,
      `A display filter has no visible keyboard focus: ${JSON.stringify(focusedFilter)}`
    )
    await page.setChecked('[data-test="filter-non-positive-profit"]', true)
    record(
      'each filter reverses on its own, and Tab reaches them',
      `${afterNotAllowedAgain.length} rows with only "not allowed" back on; ${walked.join(' → ')}`
    )

    // 7b. Every combination that empties the list still explains itself and stays undoable.
    await page.fill('[data-test="search"]', 'no recipe is called this')
    await page.waitForSelector('[data-test="no-matches"]', { timeout: TIMEOUT_MS })
    const emptyText = await textOf(page, '[data-test="no-matches"]')
    check(
      emptyText.includes(`returned ${allRows().length} recipes`) && emptyText.includes('still holds all of them'),
      `The empty list did not say the result set was intact: "${emptyText}"`
    )
    const restrictions = await textOf(page, '[data-test="active-restrictions"]')
    for (const part of ['search', 'not allowed', 'profit per craft']) {
      check(restrictions.includes(part), `The empty list did not name "${part}": "${restrictions}"`)
    }
    check(
      !restrictions.includes('craftable count'),
      `A filter that is switched off was named as applied: "${restrictions}"`
    )
    check(
      (await page.$('[data-test="display-controls"]')) !== null,
      'The display controls disappeared with the rows, leaving nothing to undo.'
    )
    await page.fill('[data-test="search"]', '')
    record('zero matches names every restriction in force', restrictions.slice(0, 96))

    // 8. The maximum limits the matching set, and Show all reveals the rest of *that* set.
    await page.setChecked('[data-test="filter-zero-craftable"]', true)
    await setMaximum(page, '1')
    check((await listedRecipes(page)).length === 1, 'A maximum of 1 did not reduce the list to one row.')
    const summary = await textOf(page, '[data-test="summary"]')
    check(
      summary.includes(`Showing 1 of ${DEFAULT_LISTED.length}`),
      `The compact count did not report the withheld rows: "${summary}"`
    )
    const hiddenSelection = await textOf(page, '[data-test="detail-hidden"]')
    check(
      hiddenSelection.includes('not in the displayed list') && hiddenSelection.includes('Show all'),
      `The selected recipe outside the list was not identified as such: "${hiddenSelection}"`
    )
    check(
      (await textOf(page, '[data-test="detail-name"]')) === firstRowName,
      'A display maximum moved the detail onto a different recipe.'
    )

    await page.setChecked('[data-test="show-all"]', true)
    const shownAll = await listedRecipes(page)
    check(
      shownAll.length === DEFAULT_LISTED.length,
      `Show all listed ${shownAll.length} rows instead of the ${DEFAULT_LISTED.length} matching ones.`
    )
    for (const hidden of [...ZERO_COUNT_ROWS, NOT_ALLOWED_ROW]) {
      check(
        !shownAll.some((name) => name.startsWith(hidden)),
        `Show all revealed "${hidden}", which a filter that is still on excludes.`
      )
    }
    check(
      (await page.$('[data-test="detail-hidden"]')) === null,
      'The selected recipe was listed again but the detail still called it hidden.'
    )
    const suspended = await displayControlState(page)
    check(
      suspended.maximumDisabled === true && suspended.maximum === '1',
      `Show all did not suspend the maximum while keeping it: ${JSON.stringify(suspended)}`
    )
    record(
      'Show all reveals the matching set, not an unfiltered one',
      `1 → ${shownAll.length} rows, maximum suspended but kept at ${suspended.maximum}`
    )

    // 9. None of that asked the backend for anything.
    check(
      requestsToPath(stub, '/api/crafting/profit').length === calculationsBeforeDisplayControls &&
        requestsToPath(stub, '/api/crafting/profit/resolution').length === detailsBeforeDisplayControls,
      'A display control submitted a calculation or a detail request.'
    )
    record(
      'display controls submitted no calculation and no detail request',
      `${calculationsBeforeDisplayControls} calculations and ${detailsBeforeDisplayControls} ` +
        'details before and after'
    )

    // 10. A click on a row's own background — not on its control — selects that recipe.
    // Deliberately a row that is *not* already selected, so the detail has to actually move.
    const listedNow = await listedRecipes(page)
    const targetIndex = listedNow.findIndex((name) => name !== firstRowName)
    check(targetIndex >= 0, `No unselected row to click: ${listedNow.join(', ')}`)
    const targetName = listedNow[targetIndex]
    const valueCells = await page.$$('[data-test="profit-row"] [data-test="total-profit"]')
    await valueCells[targetIndex].click()
    await page.waitForFunction(
      (expected) => document.querySelector('[data-test="detail-name"]')?.textContent?.trim() === expected,
      targetName,
      { timeout: TIMEOUT_MS }
    )
    const markedAfterRowClick = await page.$$eval(
      '[data-test="profit-row"][aria-current="true"] .recipe-name',
      (names) => names.map((name) => name.textContent?.trim() ?? '')
    )
    check(
      markedAfterRowClick.length === 1 && markedAfterRowClick[0] === targetName,
      `Clicking a row's background marked ${JSON.stringify(markedAfterRowClick)} rather than "${targetName}".`
    )
    record(
      'a click anywhere on a row selects its recipe',
      `"${targetName}" selected from a value cell, replacing "${firstRowName}"`
    )

    // Back to the state the remaining steps were written against, selection included.
    await page.setChecked('[data-test="show-all"]', false)
    await setMaximum(page, '250')
    const restoreIndex = (await listedRecipes(page)).indexOf(firstRowName)
    check(restoreIndex >= 0, `"${firstRowName}" is no longer listed, so the next steps cannot run.`)
    await (await page.$$('[data-test="select-row"]'))[restoreIndex].click()
    check(
      (await textOf(page, '[data-test="detail-name"]')) === firstRowName,
      'Could not restore the original selection before the replacement-calculation step.'
    )

    // 11. A replacement calculation that no longer contains the selected recipe clears the detail.
    await page.selectOption('[data-test="scope-selector"]', 'DISCIPLINE|Chef')
    await page.waitForSelector('[data-test="detail-placeholder"]', { timeout: TIMEOUT_MS })
    check(
      (await page.$('[data-test="profit-row"][aria-current="true"]')) === null,
      'A row stayed marked after the calculation that contained it was replaced.'
    )
    check(
      (await page.$eval('[data-test="search"]', (input) => input.value)) === '',
      'The search box lost its own state during the scope change.'
    )
    record('a replacement calculation clears a removed selection', 'detail returned to its empty case')

    // 12. Narrow: the detail reflows below the results, and only the table scrolls sideways.
    await openProfit(page, stub.origin, NARROW)
    const narrowResults = await boxOf(page, '[data-test="results-region"]')
    const narrowDetail = await boxOf(page, '[data-test="selected-detail"]')
    check(
      narrowDetail.top >= narrowResults.bottom - 1,
      `At ${NARROW.width}px the detail did not move below the results ` +
        `(results bottom ${narrowResults.bottom}, detail top ${narrowDetail.top}).`
    )
    const narrowOverflow = await pageOverflow(page)
    check(
      narrowOverflow.scrollWidth <= narrowOverflow.clientWidth + 1,
      `The page scrolls horizontally at ${NARROW.width}px ` +
        `(${narrowOverflow.scrollWidth} > ${narrowOverflow.clientWidth}).`
    )
    const region = await page.$eval('.table-region', (element) => ({
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth
    }))
    check(
      region.scrollWidth > region.clientWidth,
      'The reduced table did not overflow its region at 360px, so local scrolling proved nothing.'
    )
    record(
      `detail reflows below the results at ${NARROW.width}×${NARROW.height}`,
      `region ${region.clientWidth}px holds ${region.scrollWidth}px of columns, page does not scroll sideways`
    )

    // 13. The narrow layout still selects — by row background too — and its controls stay usable.
    // A fresh load, so this is the default scope again with the display controls back at their
    // defaults: switching all three off must bring every returned row back.
    const narrowDefaults = await displayControlState(page)
    check(
      narrowDefaults.zeroCraftable === true &&
        narrowDefaults.notAllowed === true &&
        narrowDefaults.nonPositiveProfit === true &&
        narrowDefaults.maximum === '250',
      `The display controls did not reopen in their specified state at ${NARROW.width}px: ` +
        JSON.stringify(narrowDefaults)
    )
    check(
      narrowDefaults.inControlsPanel,
      `The Displayed results subgroup left the controls panel at ${NARROW.width}px.`
    )
    await showEveryRow(page)
    const expectedRows = allRows().length
    const narrowRows = await page.$$eval('[data-test="profit-row"]', (rows) => rows.length)
    check(
      narrowRows === expectedRows,
      `The narrow layout showed ${narrowRows} of the ${expectedRows} rows this scope returns.`
    )
    const narrowFirstName = (await listedRecipes(page))[0]
    await page.click('[data-test="profit-row"]:nth-of-type(1) [data-test="craftable-count"]')
    await page.waitForSelector('[data-test="detail-name"]', { timeout: TIMEOUT_MS })
    check(
      (await textOf(page, '[data-test="detail-name"]')) === narrowFirstName,
      `A narrow row-background click opened "${await textOf(page, '[data-test="detail-name"]')}".`
    )
    const narrowAfterSelect = await pageOverflow(page)
    check(
      narrowAfterSelect.scrollWidth <= narrowAfterSelect.clientWidth + 1,
      `Opening a detail made the narrow page scroll horizontally: ${JSON.stringify(narrowAfterSelect)}`
    )
    const narrowControls = await boxOf(page, '[data-test="display-controls"]')
    check(
      narrowControls.width > 0 && narrowControls.right <= NARROW.width + 1,
      `The display controls were clipped at ${NARROW.width}px: ${JSON.stringify(narrowControls)}`
    )
    record(
      'narrow layout keeps every row, usable controls and row selection',
      `${narrowRows} rows, controls ${Math.round(narrowControls.width)}px, detail opened by row click`
    )

    // 14. Zoom: doubling the text size still reflows both regions.
    await page.setViewportSize(WIDE)
    await openProfit(page, stub.origin, WIDE)
    await page.evaluate(() => {
      document.documentElement.style.fontSize = '200%'
    })
    const zoomed = await pageOverflow(page)
    check(
      zoomed.scrollWidth <= zoomed.clientWidth + 1,
      `At 200% text size the page scrolls horizontally (${zoomed.scrollWidth} > ${zoomed.clientWidth}).`
    )
    const zoomedDetail = await boxOf(page, '[data-test="selected-detail"]')
    check(zoomedDetail.width > 0, 'The detail region disappeared at 200% text size.')
    await page.evaluate(() => {
      document.documentElement.style.fontSize = ''
    })
    record('reflows at 200% text size', `${zoomed.scrollWidth}px content in ${zoomed.clientWidth}px`)

    // 15. DOMAIN_SPEC 2.1.1's required columns, at both viewports: the supplied totals rendered as
    // supplied, no general State column, and the one retained row-level diagnostic.
    for (const viewport of [WIDE, NARROW]) {
      await openProfit(page, stub.origin, viewport)
      await showEveryRow(page)

      const headers = await page.$$eval('[data-test="profit-table"] thead th', (cells) =>
        cells.map((cell) => (cell.textContent ?? '').replace(/\s+/g, ' ').trim())
      )
      const required = ['Recipe', 'Craftable', 'Own materials', 'Profit', 'Total sell value', 'Total profit']
      for (const heading of required) {
        check(
          headers.some((header) => header.startsWith(heading)),
          `At ${viewport.width}px the "${heading}" column is missing: ${headers.join(' | ')}`
        )
      }
      check(
        !headers.some((header) => header.startsWith('State')),
        `At ${viewport.width}px the removed State column is still there: ${headers.join(' | ')}`
      )

      // 1_481_401 copper is the supplied total; 12 x 111_110 would render as 133g 33s 20c.
      const sellValues = await page.$$eval('[data-test="total-sell-value"]', (cells) =>
        cells.map((cell) => (cell.textContent ?? '').replace(/\s+/g, ' ').trim())
      )
      check(
        sellValues[0] === '148g 14s 1c',
        `At ${viewport.width}px the first total sell value read "${sellValues[0]}", not the supplied one.`
      )
      check(
        sellValues.includes('—'),
        `At ${viewport.width}px a row with no supplied total sell value did not stay blank: ${sellValues.join(', ')}`
      )

      const diagnostics = await page.$$eval('[data-test="row-diagnostic"]', (chips) =>
        chips.map((chip) => (chip.textContent ?? '').replace(/\s+/g, ' ').trim())
      )
      check(
        diagnostics.includes('Recipe loop'),
        `At ${viewport.width}px the retained cycle diagnostic is gone: ${diagnostics.join(', ')}`
      )
      const tableText = await textOf(page, '[data-test="profit-table"]')
      for (const moved of ['BUYING_DISABLED', 'RECIPE_NOT_ALLOWED', 'CYCLE_DETECTED', 'Buying is off']) {
        check(
          !tableText.includes(moved),
          `At ${viewport.width}px the table still labels rows with "${moved}".`
        )
      }
      record(
        `required economic columns and no State column at ${viewport.width}px`,
        `${headers.length} columns, diagnostics: ${diagnostics.join(', ') || 'none'}`
      )
    }

    // 15b. A limit on further crafting, at both widths: the crafts already counted stay valid, the
    // supplied cost and the echoed budget are both stated, the purchase for those counted crafts is
    // listed, and the label that only repeats the sentence is gone (DOMAIN_SPEC 2.1.1).
    for (const viewport of [WIDE, NARROW]) {
      await openProfit(page, stub.origin, viewport)
      const listed = await listedRecipes(page)
      const budgetIndex = listed.indexOf(BUDGET_ROW)
      check(budgetIndex >= 0, `"${BUDGET_ROW}" is not listed by default: ${listed.join(', ')}`)
      await (await page.$$('[data-test="select-row"]'))[budgetIndex].click()
      await page.waitForFunction(
        (name) => document.querySelector('[data-test="detail-name"]')?.textContent?.trim() === name,
        BUDGET_ROW,
        { timeout: TIMEOUT_MS }
      )

      const budget = await page.evaluate(() => {
        const text = (test) =>
          document.querySelector(`[data-test="${test}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? null
        return {
          badge: document.querySelector('[data-test="detail-status"]') !== null,
          explanation: text('detail-status-explanation'),
          context: text('detail-budget-context'),
          affected: text('detail-affected-item'),
          buyCost: text('detail-buy-cost'),
          purchases: [...document.querySelectorAll('[data-test="missing-item"]')].map((item) =>
            (item.textContent ?? '').replace(/\s+/g, ' ').trim()
          ),
          basis: (document.querySelector('[data-test="selected-detail"]')?.textContent ?? '').replace(
            /\s+/g,
            ' '
          )
        }
      })
      check(
        !budget.badge && !budget.basis.includes('Over the buy limit'),
        `At ${viewport.width}px the redundant "Over the buy limit" label is still shown: ${JSON.stringify(budget)}`
      )
      check(
        budget.explanation.includes('Further crafting is blocked') &&
          budget.explanation.includes('4 crafts already counted stay valid'),
        `At ${viewport.width}px the limit was not told apart from the counted crafts: ${budget.explanation}`
      )
      // Both amounts are the backend's: 250000 copper of budget echoed with the calculation, and
      // 240000 copper of purchase supplied for the crafts counted.
      check(
        budget.context === "The calculation's maximum buy setting is 25g 0s 0c.",
        `At ${viewport.width}px the echoed maximum buy was not stated: ${budget.context}`
      )
      check(
        budget.buyCost === '24g 0s 0c' && budget.basis.includes('For all 4 crafts counted'),
        `At ${viewport.width}px the counted-craft purchase cost is wrong: ${budget.buyCost}`
      )
      check(
        budget.purchases.length === 1 &&
          budget.purchases[0].includes('Glob of Ectoplasm') &&
          budget.purchases[0].includes('×20'),
        `At ${viewport.width}px the affected purchase was not listed: ${JSON.stringify(budget.purchases)}`
      )
      // The purchase that went over the limit is not in the row contract, and is not invented.
      check(
        budget.affected !== null &&
          budget.affected.includes('does not name the further purchase that went over the limit'),
        `At ${viewport.width}px the detail did not say what it has no fact for: ${budget.affected}`
      )
      const budgetOverflow = await pageOverflow(page)
      check(
        budgetOverflow.scrollWidth <= budgetOverflow.clientWidth + 1,
        `At ${viewport.width}px the budget detail made the page scroll sideways ` +
          `(${budgetOverflow.scrollWidth} > ${budgetOverflow.clientWidth}).`
      )
      record(
        `budget limit explained from supplied facts at ${viewport.width}px`,
        `${budget.context} ${budget.purchases[0].slice(0, 48)}`
      )
    }

    // 15c. The same removal for a row blocked because buying is off, reached from the keyboard alone.
    await openProfit(page, stub.origin, WIDE)
    await showEveryRow(page)
    const buyingOffIndex = (await listedRecipes(page)).indexOf('Elonian Leather Square')
    check(buyingOffIndex >= 0, 'The row blocked because buying is off was not listed.')
    const rowControls = await page.$$('[data-test="select-row"]')
    await rowControls[buyingOffIndex].focus()
    await page.keyboard.press('Enter')
    await page.waitForFunction(
      () =>
        document.querySelector('[data-test="detail-name"]')?.textContent?.trim() ===
        'Elonian Leather Square',
      undefined,
      { timeout: TIMEOUT_MS }
    )
    const buyingOff = await page.evaluate(() => ({
      badge: document.querySelector('[data-test="detail-status"]') !== null,
      explanation:
        document.querySelector('[data-test="detail-status-explanation"]')?.textContent?.replace(/\s+/g, ' ').trim() ??
        null,
      detail: (document.querySelector('[data-test="selected-detail"]')?.textContent ?? '').replace(/\s+/g, ' '),
      focused: document.activeElement?.getAttribute('data-test') ?? null
    }))
    check(
      !buyingOff.badge && !buyingOff.detail.includes('Buying is off'),
      `The redundant "Buying is off" label is still in the detail: ${JSON.stringify(buyingOff)}`
    )
    check(
      buyingOff.explanation.includes('buying is switched off'),
      `The cause went with the label: ${buyingOff.explanation}`
    )
    check(buyingOff.focused === 'select-row', 'Selecting by keyboard moved focus off the row control.')
    record('no redundant "Buying is off" label, cause kept', buyingOff.explanation.slice(0, 96))

    // 16. DOMAIN_SPEC 2.1.1's "Allow non-Trading-Post materials" calculation rule (UD-009/UD-010):
    // grouped with the calculation, open enabled, recalculated in the backend, preserved on reload,
    // and explained from the backend's own facts — never a display filter.
    await openProfit(page, stub.origin, WIDE)
    await page.click('[data-test="settings-disclosure"] summary')
    const materialRule = await page.evaluate(() => {
      const control = document.querySelector('[data-test="setting-allowNonTradeableMaterials"]')
      return {
        present: control !== null,
        checked: control?.checked ?? null,
        inCalculationGroup:
          document.querySelector('[data-test="calculation-controls"]')?.contains(control) ?? false,
        inDisplayGroup:
          document.querySelector('[data-test="display-controls"]')?.contains(control) ?? false,
        describedBy: control?.getAttribute('aria-describedby'),
        help:
          document
            .querySelector('[data-test="setting-allowNonTradeableMaterials-help"]')
            ?.textContent?.replace(/\s+/g, ' ')
            .trim() ?? null
      }
    })
    check(materialRule.present, 'The "Allow non-Trading-Post materials" control is missing.')
    check(
      materialRule.inCalculationGroup && !materialRule.inDisplayGroup,
      `The material rule is not grouped with the calculation: ${JSON.stringify(materialRule)}`
    )
    check(materialRule.checked === true, 'The material rule did not open enabled.')
    check(
      materialRule.help !== null && materialRule.describedBy === 'allow-non-tp-help',
      `The material rule has no help text bound to it: ${JSON.stringify(materialRule)}`
    )
    for (const phrase of ['own them or can craft them', 'unavailable', 'consume no such material']) {
      check(
        materialRule.help.includes(phrase),
        `The material rule's help does not state "${phrase}": ${materialRule.help}`
      )
    }
    check(
      (await textOf(page, '[data-test="effective-settings"]')).includes(
        'non-Trading-Post materials allowed'
      ),
      'The effective-settings summary does not report the material rule in force.'
    )
    record(
      'the material rule is a calculation control, enabled by default',
      `help: ${materialRule.help.slice(0, 72)}…`
    )

    // Switching it off is a *calculation*: exactly one new request carrying the rule, and the list
    // changes because that answer's rows differ — the page deleted nothing.
    const listedUnderTheRule = await listedRecipes(page)
    const calculationsBeforeRule = requestsToPath(stub, '/api/crafting/profit').length
    const filtersBeforeRule = await displayControlState(page)
    await page.setChecked('[data-test="setting-allowNonTradeableMaterials"]', false)
    await page.waitForFunction(
      () =>
        [...document.querySelectorAll('[data-test="profit-row"] .recipe-name')].some(
          (name) => (name.textContent ?? '').trim() === 'Bound Blade'
        ),
      undefined,
      { timeout: TIMEOUT_MS }
    )
    const ruleCalculations = requestsToPath(stub, '/api/crafting/profit').slice(calculationsBeforeRule)
    check(
      ruleCalculations.length === 1,
      `Switching the material rule submitted ${ruleCalculations.length} calculations, not 1.`
    )
    check(
      JSON.parse(ruleCalculations[0].body).settings?.allowNonTradeableMaterials === false,
      `The calculation did not carry the rule: ${ruleCalculations[0].body}`
    )
    const filtersAfterRule = await displayControlState(page)
    check(
      JSON.stringify(filtersBeforeRule) === JSON.stringify(filtersAfterRule),
      'Switching the material rule changed a display control.'
    )
    const listedWithoutTheRule = await listedRecipes(page)
    check(
      listedWithoutTheRule.length === listedUnderTheRule.length + 1 &&
        listedWithoutTheRule.includes('Bound Blade'),
      `The new calculation's rows are not what is listed: ${listedWithoutTheRule.join(', ')}`
    )
    check(
      (await textOf(page, '[data-test="effective-settings"]')).includes(
        'non-Trading-Post materials excluded'
      ),
      'The effective-settings summary still reports the material rule as allowed.'
    )
    record(
      'switching the rule recalculates in the backend and hides nothing locally',
      `1 calculation carrying the rule, ${listedUnderTheRule.length} → ${listedWithoutTheRule.length} rows`
    )

    // The restricted result is kept and explained — in the detail and beside the material, not as a
    // row label and not as a restored State column.
    const restrictedIndex = listedWithoutTheRule.indexOf('Bound Blade')
    await (await page.$$('[data-test="select-row"]'))[restrictedIndex].click()
    await page.waitForFunction(
      () => document.querySelector('[data-test="detail-name"]')?.textContent?.trim() === 'Bound Blade',
      undefined,
      { timeout: TIMEOUT_MS }
    )
    await page.waitForSelector('[data-test="resolution-tree"]', { timeout: TIMEOUT_MS })
    const restrictedExplanation = await textOf(page, '[data-test="detail-status-explanation"]')
    check(
      restrictedExplanation.includes('cannot be traded on the Trading Post') &&
        restrictedExplanation.includes('switched off'),
      `The detail does not explain the material restriction: ${restrictedExplanation}`
    )
    const restrictedNode = await page.evaluate(() => {
      const nodes = [...document.querySelectorAll('[data-test="tree-node"]')]
      const own = (node) => node.querySelector('[data-test="node-name"]')?.textContent?.trim() ?? ''
      const restricted = nodes.find((node) => own(node).includes('Account Bound Scrap'))
      const sibling = nodes.find((node) => own(node).includes('Mithril Ore'))
      const reasonOf = (node) =>
        node?.querySelector('[data-test="node-blocked-reason"]')?.textContent?.trim() ?? null
      return {
        restricted: reasonOf(restricted),
        restrictedNote:
          restricted?.querySelector('[data-test="node-blocked-explanation"]')?.textContent?.trim() ??
          null,
        sibling: reasonOf(sibling)
      }
    })
    check(
      restrictedNode.restricted === 'Non-Trading-Post material',
      `The restriction is not reported at the material it applies to: ${JSON.stringify(restrictedNode)}`
    )
    check(
      restrictedNode.sibling === null,
      'A tradeable ingredient of the same craft was marked restricted too.'
    )
    const tableTextUnderTheRule = await textOf(page, '[data-test="profit-table"]')
    check(
      !tableTextUnderTheRule.includes('NON_TRADEABLE_MATERIAL') &&
        !tableTextUnderTheRule.includes('Non-Trading-Post material'),
      'The comparison table labels the restriction as an ordinary row state.'
    )
    const headersUnderTheRule = await page.$$eval('[data-test="profit-table"] thead th', (cells) =>
      cells.map((cell) => (cell.textContent ?? '').replace(/\s+/g, ' ').trim())
    )
    check(
      !headersUnderTheRule.some((header) => header.startsWith('State')),
      `The restriction brought a State column back: ${headersUnderTheRule.join(' | ')}`
    )
    record(
      'the restricted result is kept and explained from backend facts',
      `detail: ${restrictedExplanation.slice(0, 72)}…`
    )

    // Reload results keeps the rule, and a detail asked afterwards carries it too.
    const calculationsBeforeReload = requestsToPath(stub, '/api/crafting/profit').length
    const detailsBeforeReload = requestsToPath(stub, '/api/crafting/profit/resolution').length
    await page.click('[data-test="reload"]')
    await waitForRequestCount(stub, '/api/crafting/profit', calculationsBeforeReload + 1)
    await waitForRequestCount(stub, '/api/crafting/profit/resolution', detailsBeforeReload + 1)
    const afterReload = requestsToPath(stub, '/api/crafting/profit').at(-1)
    check(
      JSON.parse(afterReload.body).settings?.allowNonTradeableMaterials === false,
      `A reload dropped the material rule: ${afterReload.body}`
    )
    const stillChecked = await page.$eval(
      '[data-test="setting-allowNonTradeableMaterials"]',
      (control) => control.checked
    )
    check(stillChecked === false, 'The reloaded page reset the material rule to its default.')
    const detailAfterReload = requestsToPath(stub, '/api/crafting/profit/resolution').at(-1)
    check(
      JSON.parse(detailAfterReload.body).calculation?.settings?.allowNonTradeableMaterials === false,
      `A detail was asked for under a different material rule than the table's: ${detailAfterReload.body}`
    )
    record(
      'the material rule survives a reload, for the table and its detail',
      `${requestsToPath(stub, '/api/crafting/profit').length} calculations so far`
    )

    // 17. Nothing here submitted a synchronization, and nothing failed in the page.
    check(
      stub.requestsTo('/api/sync').length === 0 && stub.requestsTo('/api/prices').length === 0,
      `The Profit screen submitted a synchronization: ${JSON.stringify(stub.requestsTo('/api'))}`
    )
    check(
      stub.requestsTo('/api/crafting/profit').every((request) => request.method === 'POST'),
      'A crafting calculation or detail was requested with an unexpected method.'
    )
    check(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`)
    record(
      'read-only, no synchronization, no page error',
      `${requestsToPath(stub, '/api/crafting/profit').length} calculations, ` +
        `${requestsToPath(stub, '/api/crafting/profit/resolution').length} detail requests, ` +
        `${stub.requestsTo('/api/crafting/selector-options').length} selector reads`
    )

    console.log(`\nCrafting Profit hierarchy browser check PASSED (${steps.length} steps).`)
    console.log('Every backend answer was produced by this script; no real data was read or written.')
  } finally {
    await browser.close()
    stub.close()
  }
}

run().catch((error) => {
  console.error(`\nCrafting Profit hierarchy browser check FAILED after ${steps.length} step(s): ${error.message}`)
  process.exitCode = 1
})
