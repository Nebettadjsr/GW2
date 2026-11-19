import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { CraftingRow } from '@/api/types'
import CraftingProfitTable from '../CraftingProfitTable.vue'
import CraftingResolution from '../CraftingResolution.vue'
import SelectedResultDetail from '../SelectedResultDetail.vue'
import { DEFAULT_SETTINGS, node, profitableRow, resolutionResponse } from './fixtures'

const yieldBase = {
  source: 'MYSTIC_FORGE', estimated: true, expectedOutputPerCraft: null, minOutput: null, maxOutput: null,
  expectedSellableUnitsTotal: null, guaranteedSellableUnitsTotal: null, reusedSeedUnits: 0
}
const conservativeParent: CraftingRow = { ...profitableRow, expectedYield: { ...yieldBase, source: null } }
const variableRoot: CraftingRow = {
  ...profitableRow,
  expectedYield: { ...yieldBase, expectedOutputPerCraft: '18.92', minOutput: 10, maxOutput: 40 }
}
const limited = (craftableCount: number, profitCopper: number | null): CraftingRow => ({
  ...profitableRow, blockedReason: 'WORK_LIMIT', craftableCount, profitCopper, totalProfitCopper: profitCopper
})

function table(rows: CraftingRow[]) {
  return mount(CraftingProfitTable, {
    props: { rows, selectedRecipeId: null, sortKey: 'totalProfitCopper', sortDirection: 'desc' } as never
  })
}
function detail(row: CraftingRow, resolutionDetail: unknown = null) {
  return mount(SelectedResultDetail, {
    props: { row, hiddenReason: null, settings: DEFAULT_SETTINGS, placeholder: 'x', resolutionPhase: resolutionDetail ? 'ready' : 'idle',
      resolutionDetail, resolutionFailure: null, resolutionRecipeId: row.recipeId } as never
  })
}

describe('estimated hint wording', () => {
  it('conservativeParentSaysMinimumYieldPlanAndOnlyVariableRootSaysMean', () => {
    const t = table([conservativeParent])
    expect(t.find('[data-test="estimated-hint"]').attributes('title')).toBe('Conservative minimum-yield input plan.')
    expect(detail(conservativeParent).find('[data-test="detail-yield"]').text()).toContain('Conservative minimum-yield input plan')
    expect(detail(conservativeParent).find('[data-test="detail-yield"]').text()).not.toContain('average')
    expect(table([variableRoot]).find('[data-test="estimated-hint"]').attributes('title')).toContain('expected mean')
  })
})

describe('work-limited rows', () => {
  it('acceptedPrefixIsAtLeastNAndNotAProvenMaximum', () => {
    const row = limited(3, 1200)
    expect(table([row]).find('[data-test="incomplete-hint"]').text()).toBe('Incomplete')
    const note = detail(row).find('[data-test="detail-incomplete"]').text()
    expect(note).toContain('at least 3 crafts')
    expect(note).toContain('not a proven maximum')
    expect(note).not.toContain('WORK_LIMIT')
  })

  it('zeroAcceptedKeepsUnknownProfitAndTheActualQuote', () => {
    const row = limited(0, null)
    const note = detail(row).find('[data-test="detail-incomplete"]').text()
    expect(note).toContain('profit is unknown')
    expect(note.length).toBeLessThan(160)
    expect(table([row]).find('[data-test="profit-per-craft"]').text()).not.toMatch(/^0/)
  })

  it('ordinaryRowsHaveNoIncompleteHint', () => {
    expect(table([profitableRow]).find('[data-test="incomplete-hint"]').exists()).toBe(false)
  })
})

describe('vendor shopping and source chips', () => {
  const vendorStep = {
    source: 'VENDOR', expectedOutputPerCraft: null, minOutput: null, maxOutput: null, batches: 2, grossProduced: 20,
    reusedSeed: 0, guaranteedProducedTotal: 20, expectedProducedTotal: null, usedQuantity: 10, surplusQuantity: 10
  }
  it('vendorBoughtItemIsNotShownAsUnknownTradingPostPurchase', () => {
    const row: CraftingRow = {
      ...profitableRow,
      missingToBuy: [{ itemId: 19976, itemName: 'Stone', quantity: 20, price: null, purchaseUnitPriceCopper: null, totalPurchaseCostCopper: null, iconUrl: null }]
    }
    const tree = node({ itemId: 1, children: [node({ itemId: 19976, forgeStep: vendorStep, craftCount: 2, craftedQuantity: 10, children: [] })] })
    const text = detail(row, resolutionResponse({ recipeId: row.recipeId, calculation: {} }, { tree })).find('[data-test="missing-item"]').text()
    expect(text).toContain('vendor with currency')
    expect(text).not.toContain('Unavailable')
    const plain = detail(row).find('[data-test="missing-item"]').text()
    expect(plain).toContain('Unavailable')
  })

  it('vendorSurplusAndReusedStockAreNotLabelledCrafted', () => {
    const render = (n: ReturnType<typeof node>) => mount(CraftingResolution, {
      props: { phase: 'ready', detail: resolutionResponse({ recipeId: 1, calculation: {} }, { tree: n }), failure: null, requestedRecipeId: 1 }
    }).find('[data-test="node-sourcing"]').text()
    expect(render(node({ forgeStep: vendorStep, craftCount: 0, craftedQuantity: 10, children: [] }))).toContain('From earlier batch')
    expect(render(node({ forgeStep: vendorStep, craftCount: 2, craftedQuantity: 10, children: [] }))).not.toContain('Crafted')
    expect(render(node({ craftCount: 0, producedQuantity: 0, craftedQuantity: 4, children: [] }))).toContain('From earlier batch')
    expect(render(node({ craftCount: 2, producedQuantity: 4, craftedQuantity: 4, children: [] }))).toContain('Crafted')
  })
})
