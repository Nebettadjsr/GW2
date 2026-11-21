import assert from 'node:assert/strict'

export const ITEM = 19697
export const TABLE = '/api/crafting/profit'
export const DETAIL = '/api/crafting/profit/resolution'
export const DEFAULTS = { useOwnMats: true, allowBuying: false, maxBuyCopper: 10000,
  listingSell: false, listingBuy: false, allowDailyCrafts: false }
const FRESH = { status: 'FRESH', completedAt: '2099-01-01T00:00:00Z' }

// These are explicit backend answers, not a second implementation of reservation arithmetic.
const RESULTS = {
  ordinary: { count: 300, profit: 54321, sell: 98765 },
  keep100: { count: 200, profit: 45678, sell: 87654 },
  keep250: { count: 50, profit: 12345, sell: 56789 },
  all: { count: 0, profit: 0, sell: 0 }
}

export function bankSnapshot(stock) {
  const counts = { 200: [200], 300: [200, 100], 400: [200, 200], 1000: [250, 250, 250, 250] }[stock]
  assert.ok(counts, `Unexpected controlled Bank stock ${stock}`)
  const slots = Array.from({ length: 5 }, (_, slot) => ({ slot, itemId: null, itemName: null, count: null, iconUrl: null, rarity: null, binding: null, boundTo: null }))
  counts.forEach((count, slot) => { slots[slot] = { slot, itemId: ITEM, itemName: 'Copper Ore', count, iconUrl: null, rarity: 'Basic', binding: null, boundTo: null } })
  slots[4] = { slot: 4, itemId: 900001, itemName: null, count: 2, iconUrl: null, rarity: null, protectionSelectable: false, binding: null, boundTo: null }
  return { slotCount: 5, slots, freshness: FRESH }
}

export const materials = { categoryCount: 1, freshness: FRESH, categories: [{
  category: 5, name: 'Controlled Materials', order: 1,
  materials: [
    { position: 0, itemId: ITEM, itemName: 'Copper Ore', count: 0, iconUrl: null, rarity: 'Basic' },
    { position: 1, itemId: 19721, itemName: 'Glob of Ectoplasm', count: 250, iconUrl: null, rarity: 'Rare' },
    { position: 2, itemId: 19698, itemName: null, count: 1, iconUrl: null, rarity: null }
  ]
}] }

export function freshness(id) {
  return { requestId: id, calculationId: id, evaluatedAt: '2026-11-15T20:00:00Z', provisional: false, resultAvailable: true,
    accountSources: ['CHARACTERS', 'INVENTORY', 'MATERIALS', 'RECIPES'].map(source => ({ source, fetchedAt: '2099-01-01T00:00:00Z', state: 'FRESH' })),
    tradingPost: { requiredItems: 1, freshItems: 1, staleItems: 0, missingItems: 0,
      oldestFetchedAt: '2099-01-01T00:00:00Z', newestFetchedAt: '2099-01-01T00:00:00Z', covered: true }, outstandingWork: [], failures: [] }
}

function rowFor(settings, stock) {
  if (settings.materialProtectionEnabled === true) assert.deepEqual(settings.protectedItemIds, [], 'New source requests must not use the legacy global list')
  const all = settings.protectedBankItemIds?.includes(ITEM)
  const target = settings.protectedBankQuantities?.[ITEM]
  if (target !== undefined) assert.ok([50, 100, 250, 300, 550].includes(target), `Unexpected fixture target ${target}`)
  // Separate literal answer for a recipe consuming ten units: 550 kept leaves a supplied 45 crafts.
  // The controlled browser verifies display/association, not backend pool subtraction or its 250 cap.
  const result = stock === 1000
    ? (assert.equal(target, 550), { count: 45, profit: 11223, sell: 44556 })
    : RESULTS[settings.materialProtectionEnabled !== true ? 'ordinary' : all ? 'all' : target === 250 ? 'keep250' : target === 100 ? 'keep100' : 'ordinary']
  return { recipeId: 11, outputItemId: 1101, outputName: 'Iron Ingot', outputCount: 1, disciplines: 'Armorsmith', minRating: 75,
    resultAvailable: true, craftableCount: result.count, buyCostCopper: 0, matsSellValueCopper: 120, totalMatsSellValueCopper: 600,
    revenueCopper: 380, profitCopper: 100, totalSellValueCopper: result.sell, totalProfitCopper: result.profit,
    blockedReason: result.count === 0 ? 'MISSING_INGREDIENTS' : 'NONE', outputPrice: { buyUnitCopper: 360, sellUnitCopper: 420 },
    missingToBuy: [], missingToBuyOne: [], iconUrl: null }
}

export function scopeOf(scope = { kind: 'ALL' }) {
  return { kind: scope.kind, discipline: scope.discipline ?? null, characterName: scope.characterName ?? null, rating: scope.rating ?? 0 }
}

export function tableResponse(request, id, stock = 300) {
  const settings = { ...DEFAULTS, ...request.settings }
  return { status: 'CALCULATED', scope: scopeOf(request.scope), settings, rowCount: 1, rows: [rowFor(settings, stock)], freshness: freshness(id) }
}

export function node(overrides) {
  return { itemId: 1101, itemName: 'Iron Ingot', requestedQuantity: 1, inventoryQuantity: 0, craftedQuantity: 0,
    boughtQuantity: 0, missingQuantity: 0, recipeId: null, craftCount: 0, producedQuantity: 0, characterName: null,
    methods: [], states: [], blockedReasons: [], cashCostCopper: 0, opportunityCostCopper: 0, effectiveCostCopper: 0,
    effectiveUnitCostCopper: 0, effectiveUnitCostApproximate: false, children: [], iconUrl: null, ...overrides }
}

export function detailResponse(request, stock = 300) {
  assert.equal(request.recipeId, 11)
  const settings = { ...DEFAULTS, ...request.calculation.settings }
  const row = rowFor(settings, stock)
  const quantity = row.craftableCount === 0 ? 1 : row.craftableCount
  const unit = { 300: 33, 200: 49, 50: 198, 45: 219, 0: 9876 }[row.craftableCount]
  const tree = node({ requestedQuantity: quantity, craftedQuantity: row.craftableCount,
    recipeId: 11, craftCount: row.craftableCount, producedQuantity: row.craftableCount, methods: ['CRAFT'],
    opportunityCostCopper: 9876, effectiveCostCopper: 9876, effectiveUnitCostCopper: unit,
    effectiveUnitCostApproximate: row.craftableCount !== 0,
    children: [node({ itemId: ITEM, itemName: 'Copper Ore', requestedQuantity: stock === 1000 ? 450 : quantity,
      inventoryQuantity: stock === 1000 ? 450 : row.craftableCount, missingQuantity: row.craftableCount === 0 ? 1 : 0,
      methods: row.craftableCount === 0 ? [] : ['INVENTORY'] })] })
  return { recipeId: 11, status: 'CALCULATED', calculation: { scope: scopeOf(request.calculation.scope), settings },
    consistency: 'FRESH_CALCULATION', calculatedAt: '2026-11-15T20:00:00Z', row, treeStatus: 'AVAILABLE',
    treeBasis: row.craftableCount === 0 ? 'FIRST_BLOCKED_ATTEMPT' : 'SELECTED_RESULT_OUTPUT_QUANTITY', tree }
}
