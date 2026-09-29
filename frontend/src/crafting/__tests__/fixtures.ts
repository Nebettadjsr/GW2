import type { CraftingApi } from '@/api/craftingApi'
import type {
  CraftingDiscoveryRequest,
  CraftingDiscoveryResolutionRequest,
  CraftingDiscoveryResolutionResponse,
  CraftingDiscoveryResponse,
  CraftingProfitRequest,
  CraftingProfitResolutionRequest,
  CraftingProfitResolutionResponse,
  CraftingProfitResponse,
  CraftingRow,
  EffectiveDiscoveryScope,
  EffectiveDiscoverySettings,
  EffectiveScope,
  EffectiveSettings,
  ResolutionNode,
  SelectorOptions
} from '@/api/types'

/**
 * Controlled backend responses for the frontend tests.
 *
 * The numbers here are arbitrary transport values, not domain results: in particular
 * `totalProfitCopper` deliberately differs from `craftableCount * profitCopper` and
 * `totalSellValueCopper` from `craftableCount * revenueCopper`, so a test can tell a
 * displayed/sorted backend value apart from one the frontend derived. No test recomputes a domain
 * formula (TEST_STRATEGY 12).
 */
export const DEFAULT_SETTINGS: EffectiveSettings = {
  useOwnMats: true,
  allowBuying: false,
  maxBuyCopper: 10_000,
  listingSell: false,
  listingBuy: false,
  dailyBuyInsteadOfCraft: true
}

export const selectorOptions: SelectorOptions = {
  defaultScopeKind: 'ALL',
  disciplines: ['Chef', 'Huntsman', 'Armorsmith'],
  characterOptionCount: 2,
  characterOptions: [
    { characterName: 'Nbt Anch', discipline: 'Armorsmith', rating: 500, active: true },
    { characterName: 'Sat Anat', discipline: 'Chef', rating: 400, active: false }
  ]
}

/**
 * Supplied totals are not the products their parts would give: profit 900 is not 5 × 100, and sell
 * value 2222 is not 5 × 380.
 */
export const profitableRow: CraftingRow = {
  recipeId: 11,
  outputItemId: 1101,
  outputName: 'Iron Ingot',
  outputCount: 1,
  disciplines: 'Armorsmith, Weaponsmith',
  minRating: 75,
  resultAvailable: true,
  craftableCount: 5,
  buyCostCopper: 250,
  matsSellValueCopper: 120,
  revenueCopper: 380,
  profitCopper: 100,
  totalSellValueCopper: 2_222,
  totalProfitCopper: 900,
  blockedReason: 'NONE',
  outputPrice: { buyUnitCopper: 360, sellUnitCopper: 420 },
  missingToBuy: [],
  missingToBuyOne: [],
  iconUrl: null
}

/** Supplied total (600) is likewise not count (3) times per-craft profit (400). */
export const lessProfitableRow: CraftingRow = {
  recipeId: 12,
  outputItemId: 1202,
  outputName: 'Bowl of Soup',
  outputCount: 2,
  disciplines: 'Chef',
  minRating: 150,
  resultAvailable: true,
  craftableCount: 3,
  buyCostCopper: 90,
  matsSellValueCopper: 40,
  revenueCopper: 500,
  profitCopper: 400,
  totalSellValueCopper: 1_777,
  totalProfitCopper: 600,
  blockedReason: 'NONE',
  outputPrice: { buyUnitCopper: 470, sellUnitCopper: 520 },
  missingToBuy: [{ itemId: 77, itemName: 'Copper Ore', quantity: 4, price: { buyUnitCopper: 12, sellUnitCopper: 15 }, iconUrl: null }],
  missingToBuyOne: [],
  iconUrl: null
}

/** A blocked row: the backend reported a state and supplied no profit numbers for it. */
export const priceUnavailableRow: CraftingRow = {
  recipeId: 13,
  outputItemId: 1303,
  outputName: 'Mystic Curio',
  outputCount: 1,
  disciplines: 'Artificer',
  minRating: 400,
  resultAvailable: true,
  craftableCount: 0,
  buyCostCopper: null,
  matsSellValueCopper: null,
  revenueCopper: null,
  profitCopper: null,
  totalSellValueCopper: null,
  totalProfitCopper: null,
  blockedReason: 'PRICE_UNAVAILABLE',
  outputPrice: { buyUnitCopper: null, sellUnitCopper: null },
  missingToBuy: [{ itemId: 99, itemName: 'Charged Core', quantity: 1, price: null, iconUrl: null }],
  missingToBuyOne: [],
  iconUrl: null
}

/** A recipe the calculation produced no result for at all. */
export const noResultRow: CraftingRow = {
  recipeId: 14,
  outputItemId: 1404,
  outputName: 'Unknown Trinket',
  outputCount: 1,
  disciplines: 'Jeweler',
  minRating: 0,
  resultAvailable: false,
  craftableCount: null,
  buyCostCopper: null,
  matsSellValueCopper: null,
  revenueCopper: null,
  profitCopper: null,
  totalSellValueCopper: null,
  totalProfitCopper: null,
  blockedReason: null,
  iconUrl: null,
  outputPrice: null,
  missingToBuy: null,
  missingToBuyOne: null
}

export const allRows: CraftingRow[] = [profitableRow, lessProfitableRow, priceUnavailableRow, noResultRow]

/**
 * Rows for the cases the default four do not cover. They are deliberately *not* in `allRows`, so the
 * existing ordering and count assertions keep meaning what they meant.
 */

/** A loss, with both material lists populated and each material's quote state different. */
export const lossRow: CraftingRow = {
  recipeId: 21,
  outputItemId: 2101,
  outputName: 'Tarnished Ring',
  outputCount: 3,
  disciplines: 'Jeweler',
  minRating: 225,
  resultAvailable: true,
  craftableCount: 4,
  buyCostCopper: 8_000,
  matsSellValueCopper: 700,
  revenueCopper: 450,
  profitCopper: -250,
  totalSellValueCopper: 1_650,
  totalProfitCopper: -1_000,
  blockedReason: 'NONE',
  outputPrice: { buyUnitCopper: 400, sellUnitCopper: null },
  missingToBuy: [
    { itemId: 55, itemName: 'Silver Ore', quantity: 8, price: { buyUnitCopper: 20, sellUnitCopper: 24 }, iconUrl: null },
    { itemId: 56, itemName: null, quantity: 2, price: null, iconUrl: null }
  ],
  missingToBuyOne: [
    { itemId: 55, itemName: 'Silver Ore', quantity: 2, price: { buyUnitCopper: 20, sellUnitCopper: 24 }, iconUrl: null }
  ],
  iconUrl: null
}

/** A state code this client has no wording for; it must stay visible and must not read as success. */
export const unknownStateRow: CraftingRow = {
  ...profitableRow,
  recipeId: 22,
  outputItemId: 2202,
  outputName: 'Experimental Alloy',
  blockedReason: 'SOME_STATE_ADDED_LATER'
}

/** The backend has no name for the output item, so the item id is the identification. */
export const unnamedRow: CraftingRow = {
  ...profitableRow,
  recipeId: 23,
  outputItemId: 2303,
  outputName: null
}

/** The backend reported the one state the "not allowed" display filter matches. */
export const notAllowedRow: CraftingRow = {
  ...profitableRow,
  recipeId: 25,
  outputItemId: 2505,
  outputName: 'Forbidden Inscription',
  blockedReason: 'RECIPE_NOT_ALLOWED'
}

/** Exactly zero profit per craft: on the wrong side of the "0 or less" filter, and not a null. */
export const zeroProfitRow: CraftingRow = {
  ...profitableRow,
  recipeId: 26,
  outputItemId: 2606,
  outputName: 'Break-even Bracelet',
  profitCopper: 0,
  totalProfitCopper: 0
}

/**
 * A technical failure that nonetheless carries the not-allowed code. The contract does not produce
 * this, but a display filter must never turn "no result was calculated" into "the recipe is not
 * allowed", so the row is here to prove the filter reads `resultAvailable` as well as the code.
 */
export const noResultCarryingNotAllowedRow: CraftingRow = {
  ...noResultRow,
  recipeId: 27,
  outputItemId: 2707,
  outputName: 'Unreported Relic',
  blockedReason: 'RECIPE_NOT_ALLOWED'
}

/** Nothing blocked the calculation and it still counted no completable craft. */
export const noneCraftableRow: CraftingRow = {
  ...profitableRow,
  recipeId: 24,
  outputItemId: 2404,
  outputName: 'Rare Reagent',
  craftableCount: 0,
  totalSellValueCopper: 0,
  blockedReason: 'NONE'
}

/**
 * The reasons DOMAIN_SPEC 2.1.1 moves out of the comparison table and into the selected result, one
 * row each. Every one keeps `profitableRow`'s positive craftable count, because the field names why
 * the *next* craft could not complete and the crafts already counted stay valid — the presentation
 * of each has to hold that distinction.
 */
export const movedReasonRows: CraftingRow[] = [
  'BUYING_DISABLED',
  'NO_RECIPE',
  'DAILY_LIMIT',
  'RECIPE_NOT_ALLOWED',
  'INSUFFICIENT_BUDGET'
].map((blockedReason, index) => ({
  ...profitableRow,
  recipeId: 30 + index,
  outputItemId: 3000 + index,
  // Deliberately code-free, so a test can assert the raw code is nowhere on screen.
  outputName: `Restricted Recipe ${index + 1}`,
  blockedReason
}))

/** Over the configured maximum buy, after three crafts had already been counted. */
export const budgetBlockedRow: CraftingRow = {
  ...profitableRow,
  recipeId: 41,
  outputItemId: 4101,
  outputName: 'Expensive Inscription',
  craftableCount: 3,
  buyCostCopper: 9_500,
  blockedReason: 'INSUFFICIENT_BUDGET'
}

/**
 * The one reason DOMAIN_SPEC 2.1.1 keeps as a row-level diagnostic, until the Product Owner asks for
 * its removal. Marked in the spec, and in `rowState.rowDiagnostic`, as temporary technical debt.
 */
export const cycleDetectedRow: CraftingRow = {
  ...profitableRow,
  recipeId: 42,
  outputItemId: 4202,
  outputName: 'Recursive Alloy',
  blockedReason: 'CYCLE_DETECTED'
}

export function profitResponse(
  rows: CraftingRow[] = allRows,
  scopeKind = 'ALL',
  settings: EffectiveSettings = DEFAULT_SETTINGS
): CraftingProfitResponse {
  return {
    scope: { kind: scopeKind, discipline: null, characterName: null, rating: 0 },
    settings,
    rowCount: rows.length,
    rows
  }
}

/**
 * The response the real backend produces for `request`: the same rows, echoing back the scope and
 * settings it calculated with after applying its own documented defaults
 * (`CURRENT_ARCHITECTURE.md` 5.5). The screen takes its control state from that echo, so a stand-in
 * that ignored the request would not answer like the contract it stands in for.
 */
export function echoedProfitResponse(
  request: CraftingProfitRequest,
  rows: CraftingRow[] = allRows
): CraftingProfitResponse {
  const scope = request.scope
  return {
    scope: {
      kind: scope?.kind ?? 'ALL',
      discipline: scope?.discipline ?? null,
      characterName: scope?.characterName ?? null,
      rating: scope?.rating ?? 0
    },
    settings: request.settings ?? DEFAULT_SETTINGS,
    rowCount: rows.length,
    rows
  }
}

/**
 * Resolution-detail fixtures (`TARGET_ARCHITECTURE.md` 13.3).
 *
 * The node values here are supplied transport facts, deliberately chosen so a browser that tried to
 * work anything out would disagree with them: inclusive costs are not the sum of their children,
 * `producedQuantity` exceeds `craftedQuantity`, and the same item appears twice in one tree.
 */
export function node(overrides: Partial<ResolutionNode> = {}): ResolutionNode {
  return {
    itemId: 9001,
    itemName: 'Requirement',
    requestedQuantity: 1,
    inventoryQuantity: 0,
    craftedQuantity: 1,
    boughtQuantity: 0,
    missingQuantity: 0,
    recipeId: null,
    craftCount: 0,
    producedQuantity: 0,
    characterName: null,
    methods: ['CRAFT'],
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
 * The root the requested recipe actually produced, over two ingredients — one taken from stock, one
 * bought — plus a deeper craft. The parent's inclusive costs are **not** the children's totals.
 */
export const craftedTree: ResolutionNode = node({
  itemId: 1101,
  itemName: 'Iron Ingot',
  requestedQuantity: 1,
  craftedQuantity: 1,
  recipeId: 11,
  craftCount: 1,
  producedQuantity: 3,
  characterName: 'Nbt Anch',
  methods: ['CRAFT'],
  cashCostCopper: 777,
  opportunityCostCopper: 55,
  effectiveCostCopper: 832,
  children: [
    node({
      itemId: 77,
      itemName: 'Copper Ore',
      requestedQuantity: 6,
      inventoryQuantity: 2,
      craftedQuantity: 0,
      boughtQuantity: 4,
      methods: ['INVENTORY', 'BUY'],
      cashCostCopper: 48,
      opportunityCostCopper: 24,
      effectiveCostCopper: 72
    }),
    node({
      itemId: 78,
      itemName: 'Charged Core',
      requestedQuantity: 1,
      craftedQuantity: 1,
      recipeId: 31,
      craftCount: 1,
      producedQuantity: 5,
      methods: ['CRAFT'],
      cashCostCopper: 500,
      opportunityCostCopper: null,
      effectiveCostCopper: null,
      children: [
        node({
          itemId: 77,
          itemName: 'Copper Ore',
          requestedQuantity: 2,
          inventoryQuantity: 2,
          craftedQuantity: 0,
          methods: ['INVENTORY'],
          cashCostCopper: 0,
          opportunityCostCopper: 12,
          effectiveCostCopper: 12
        })
      ]
    })
  ]
})

/** Owned finished stock: no recipe, no craft, no invented ingredient. */
export const inventoryOnlyTree: ResolutionNode = node({
  itemId: 1101,
  itemName: 'Iron Ingot',
  requestedQuantity: 1,
  inventoryQuantity: 1,
  craftedQuantity: 0,
  recipeId: null,
  craftCount: 0,
  producedQuantity: 0,
  methods: ['INVENTORY'],
  cashCostCopper: 0,
  opportunityCostCopper: 340,
  effectiveCostCopper: 340,
  children: []
})

/** Another recipe supplied the output; its own identity, executions and children are kept. */
export const otherRecipeTree: ResolutionNode = node({
  itemId: 1101,
  itemName: 'Iron Ingot',
  requestedQuantity: 1,
  craftedQuantity: 1,
  recipeId: 4242,
  craftCount: 2,
  producedQuantity: 4,
  methods: ['CRAFT'],
  cashCostCopper: 900,
  opportunityCostCopper: 0,
  effectiveCostCopper: 900,
  children: [node({ itemId: 79, itemName: 'Mithril Ore', requestedQuantity: 3, boughtQuantity: 3, craftedQuantity: 0, methods: ['BUY'] })]
})

/** A blocked attempted craft: the attempted recipe stays, with no completed craft and a null cost. */
export const blockedTree: ResolutionNode = node({
  itemId: 1303,
  itemName: 'Mystic Curio',
  requestedQuantity: 1,
  craftedQuantity: 0,
  missingQuantity: 1,
  recipeId: 13,
  craftCount: 0,
  producedQuantity: 0,
  methods: [],
  states: ['BLOCKED'],
  blockedReasons: ['BUYING_DISABLED'],
  cashCostCopper: null,
  opportunityCostCopper: null,
  effectiveCostCopper: null,
  children: [
    node({
      itemId: 91,
      itemName: 'Pile of Dust',
      requestedQuantity: 4,
      craftedQuantity: 0,
      missingQuantity: 4,
      methods: [],
      states: ['PRICE_UNAVAILABLE', 'BLOCKED'],
      blockedReasons: ['PRICE_UNAVAILABLE', 'NO_RECIPE'],
      cashCostCopper: null,
      opportunityCostCopper: null,
      effectiveCostCopper: null
    }),
    node({
      itemId: 92,
      itemName: 'Bound Trophy',
      requestedQuantity: 2,
      inventoryQuantity: 2,
      craftedQuantity: 0,
      methods: ['INVENTORY'],
      states: ['UNVALUED_NONTRADEABLE'],
      cashCostCopper: 0,
      opportunityCostCopper: 0,
      effectiveCostCopper: 0
    }),
    node({
      itemId: 93,
      itemName: null,
      requestedQuantity: 1,
      craftedQuantity: 0,
      missingQuantity: 1,
      methods: ['SALVAGE_ADDED_LATER'],
      states: ['SOME_STATE_ADDED_LATER'],
      blockedReasons: ['SOME_REASON_ADDED_LATER'],
      cashCostCopper: null,
      opportunityCostCopper: null,
      effectiveCostCopper: null
    })
  ]
})

export function resolutionResponse(
  request: CraftingProfitResolutionRequest,
  overrides: Partial<CraftingProfitResolutionResponse> = {}
): CraftingProfitResolutionResponse {
  const scope = request.calculation.scope
  const echoedScope: EffectiveScope = {
    kind: scope?.kind ?? 'ALL',
    discipline: scope?.discipline ?? null,
    characterName: scope?.characterName ?? null,
    rating: scope?.rating ?? 0
  }
  return {
    recipeId: request.recipeId,
    calculation: { scope: echoedScope, settings: request.calculation.settings ?? DEFAULT_SETTINGS },
    consistency: 'FRESH_CALCULATION',
    calculatedAt: '2026-09-25T10:20:30Z',
    row: profitableRow,
    treeStatus: 'AVAILABLE',
    treeBasis: 'SINGLE_OUTPUT_REQUIREMENT',
    tree: craftedTree,
    ...overrides
  }
}

/* -------------------------------------------------------------- Crafting Discovery ---------------- */

/**
 * The Discovery route's own effective settings, deliberately different from `DEFAULT_SETTINGS` in
 * every field a test could confuse: buying is on, the budget is the route's own 200000, and the daily
 * value is the one Discovery fixes rather than Profit's. A screen that fell back to Profit's defaults
 * would disagree with this.
 */
export const DISCOVERY_SETTINGS: EffectiveDiscoverySettings = {
  useOwnMats: true,
  allowBuying: true,
  maxBuyCopper: 200_000,
  listingSell: false,
  listingBuy: false,
  dailyBuyInsteadOfCraft: false
}

/**
 * The candidates a Discovery calculation returns. Five recipes with five different recipe levels — 75,
 * 150, 225, 400 and 0 — so level sorting in both directions is observable, and covering a gain, a
 * larger gain, a loss, a blocked row whose economics are null, and a recipe with no calculated result.
 *
 * The loss is in the list on purpose: a negative immediate profit is a valid discovery candidate
 * (DOMAIN_SPEC 37), so any test that finds it missing has found a defect.
 */
export const discoveryRows: CraftingRow[] = [
  profitableRow,
  lessProfitableRow,
  lossRow,
  priceUnavailableRow,
  noResultRow
]

/**
 * The response the real backend produces for `request`: the same rows, echoing back the scope, the
 * inventory character and the settings it calculated with after applying its own Discovery defaults
 * (`CURRENT_ARCHITECTURE.md` 5.6). An omitted inventory character is echoed as null, which is the
 * unfiltered owned pool.
 */
export function echoedDiscoveryResponse(
  request: CraftingDiscoveryRequest,
  rows: CraftingRow[] = discoveryRows
): CraftingDiscoveryResponse {
  const scope: EffectiveDiscoveryScope = {
    discipline: request.scope.discipline,
    characterName: request.scope.characterName,
    rating: request.scope.rating
  }
  return {
    scope,
    inventoryCharacterName: request.inventoryCharacterName ?? null,
    settings:
      request.settings === undefined
        ? DISCOVERY_SETTINGS
        : { ...request.settings, dailyBuyInsteadOfCraft: DISCOVERY_SETTINGS.dailyBuyInsteadOfCraft },
    rowCount: rows.length,
    rows
  }
}

/** The Discovery fresh-detail response, echoing the identity and inputs it was given. */
export function discoveryResolutionResponse(
  request: CraftingDiscoveryResolutionRequest,
  overrides: Partial<CraftingDiscoveryResolutionResponse> = {}
): CraftingDiscoveryResolutionResponse {
  const echoed = echoedDiscoveryResponse(request.calculation)
  return {
    recipeId: request.recipeId,
    calculation: {
      scope: echoed.scope,
      inventoryCharacterName: echoed.inventoryCharacterName,
      settings: echoed.settings
    },
    consistency: 'FRESH_CALCULATION',
    calculatedAt: '2026-09-26T11:22:33Z',
    row: profitableRow,
    treeStatus: 'AVAILABLE',
    treeBasis: 'SINGLE_OUTPUT_REQUIREMENT',
    tree: craftedTree,
    ...overrides
  }
}

/** A promise a test resolves by hand, for out-of-order and in-flight checks. */
export interface Deferred<T> {
  promise: Promise<T>
  resolve(value: T): void
  reject(cause: unknown): void
}

export function deferred<T>(): Deferred<T> {
  let resolve: (value: T) => void = () => undefined
  let reject: (cause: unknown) => void = () => undefined
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

/** Records every request and answers from handlers the test supplies. */
export class FakeCraftingApi implements CraftingApi {
  readonly profitRequests: CraftingProfitRequest[] = []
  readonly resolutionRequests: CraftingProfitResolutionRequest[] = []
  readonly discoveryRequests: CraftingDiscoveryRequest[] = []
  readonly discoveryResolutionRequests: CraftingDiscoveryResolutionRequest[] = []
  selectorHandler: () => Promise<SelectorOptions> = () => Promise.resolve(selectorOptions)
  profitHandler: (request: CraftingProfitRequest, callIndex: number) => Promise<CraftingProfitResponse> = (
    request
  ) => Promise.resolve(echoedProfitResponse(request))

  /** Answers like the contract does by default: echoing the identity and inputs it was given. */
  resolutionHandler: (
    request: CraftingProfitResolutionRequest,
    callIndex: number
  ) => Promise<CraftingProfitResolutionResponse> = (request) =>
    Promise.resolve(resolutionResponse(request))

  loadSelectorOptions(): Promise<SelectorOptions> {
    return this.selectorHandler()
  }

  calculateProfit(request: CraftingProfitRequest): Promise<CraftingProfitResponse> {
    const callIndex = this.profitRequests.length
    this.profitRequests.push(request)
    return this.profitHandler(request, callIndex)
  }

  resolveProfitDetail(
    request: CraftingProfitResolutionRequest
  ): Promise<CraftingProfitResolutionResponse> {
    const callIndex = this.resolutionRequests.length
    this.resolutionRequests.push(request)
    return this.resolutionHandler(request, callIndex)
  }

  /** Answers like the Discovery contract does by default: echoing the inputs it was given. */
  discoveryHandler: (
    request: CraftingDiscoveryRequest,
    callIndex: number
  ) => Promise<CraftingDiscoveryResponse> = (request) =>
    Promise.resolve(echoedDiscoveryResponse(request))

  discoveryResolutionHandler: (
    request: CraftingDiscoveryResolutionRequest,
    callIndex: number
  ) => Promise<CraftingDiscoveryResolutionResponse> = (request) =>
    Promise.resolve(discoveryResolutionResponse(request))

  calculateDiscovery(request: CraftingDiscoveryRequest): Promise<CraftingDiscoveryResponse> {
    const callIndex = this.discoveryRequests.length
    this.discoveryRequests.push(request)
    return this.discoveryHandler(request, callIndex)
  }

  resolveDiscoveryDetail(
    request: CraftingDiscoveryResolutionRequest
  ): Promise<CraftingDiscoveryResolutionResponse> {
    const callIndex = this.discoveryResolutionRequests.length
    this.discoveryResolutionRequests.push(request)
    return this.discoveryResolutionHandler(request, callIndex)
  }
}
