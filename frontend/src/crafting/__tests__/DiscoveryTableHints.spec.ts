import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { CraftingRow } from '@/api/types'
import DiscoveryTable from '../DiscoveryTable.vue'
import {
  cycleDetectedRow,
  noResultRow,
  priceUnavailableRow,
  profitableRow,
  unknownStateRow
} from './fixtures'

function mountTable(rows: CraftingRow[]) {
  return mount(DiscoveryTable, {
    props: { rows, sortKey: 'outputName', sortDirection: 'asc', selectedRecipeId: null }
  })
}

describe('DiscoveryTable row hints', () => {
  it('showsNoRowStateLabelForAnyReasonIncludingAnUnknownCodeAndAMissingResult', () => {
    const rows: CraftingRow[] = [
      priceUnavailableRow,
      cycleDetectedRow,
      noResultRow,
      unknownStateRow,
      { ...profitableRow, recipeId: 901, blockedReason: null },
      { ...profitableRow, recipeId: 902, blockedReason: 'WALLET_UNAVAILABLE' },
      { ...profitableRow, recipeId: 903, blockedReason: 'INSUFFICIENT_CURRENCY' }
    ]
    const wrapper = mountTable(rows)

    expect(wrapper.findAll('[data-test="discovery-row"]')).toHaveLength(rows.length)
    expect(wrapper.find('[data-test="discovery-row-diagnostic"]').exists()).toBe(false)
    expect(wrapper.find('.row-diagnostic').exists()).toBe(false)
    const text = wrapper.text()
    for (const label of [
      'No result', 'State not reported', 'Recipe loop', 'Price missing', 'Wallet unknown',
      'Not enough currency', 'Unrecognized state'
    ]) {
      expect(text).not.toContain(label)
    }
    // The missing result shows the explicit "not supplied" marker, never zero.
    const noResultCells = wrapper.findAll('[data-test="discovery-row"]').at(2)?.findAll('td').map((c) => c.text()) ?? []
    expect(noResultCells).toEqual(['0', '—', '—', '—'])
  })

  it('keepsTheIncompleteAndEstimatedHintsLikeCraftingProfit', () => {
    const workLimited = { ...profitableRow, recipeId: 801, blockedReason: 'WORK_LIMIT' }
    const estimated: CraftingRow = {
      ...profitableRow,
      recipeId: 802,
      expectedYield: {
        source: null, estimated: true, expectedOutputPerCraft: '2.5', minOutput: 1, maxOutput: 4,
        expectedSellableUnitsTotal: null, guaranteedSellableUnitsTotal: null, reusedSeedUnits: 0
      }
    }
    const wrapper = mountTable([workLimited, estimated, profitableRow])
    const rows = wrapper.findAll('[data-test="discovery-row"]')

    expect(rows[0]?.find('[data-test="discovery-incomplete-hint"]').text()).toBe('Incomplete')
    expect(rows[0]?.find('[data-test="discovery-estimated-hint"]').exists()).toBe(false)
    expect(rows[1]?.find('[data-test="discovery-estimated-hint"]').text()).toBe('≈ est.')
    expect(rows[2]?.find('[data-test="discovery-incomplete-hint"]').exists()).toBe(false)
    expect(rows[2]?.find('[data-test="discovery-estimated-hint"]').exists()).toBe(false)
  })
})
