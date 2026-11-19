import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CraftingProfitTable from '../CraftingProfitTable.vue'
import { profitableRow } from './fixtures'

describe('CraftingProfitTable currency rows', () => {
  const mountRows = (rows: unknown[]) =>
    mount(CraftingProfitTable, {
      props: { rows: rows as never, sortKey: 'profitCopper', sortDirection: 'desc', selectedRecipeId: null }
    })

  it('marks unpriced currency spending and never shows an unconditional green profit', () => {
    const row = {
      ...profitableRow,
      currencyCosts: {
        walletState: 'UNKNOWN',
        goldMarginIsConditional: true,
        economicsFromHypotheticalCraft: false,
        costs: [{ currencyId: 61, name: 'Research Note', perCraft: 30, total: 300, breakEvenCopperPerUnit: null }]
      }
    }
    const wrapper = mountRows([row])
    expect(wrapper.find('[data-test="currency-conditional"]').text()).toContain('Research Note')
    expect(wrapper.find('[data-test="profit-per-craft"] .money--gain').exists()).toBe(false)
  })

  it('keeps item-only rows unchanged', () => {
    const wrapper = mountRows([profitableRow])
    expect(wrapper.find('[data-test="currency-conditional"]').exists()).toBe(false)
  })
})
