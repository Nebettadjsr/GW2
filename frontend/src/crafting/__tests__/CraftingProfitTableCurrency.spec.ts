import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CraftingProfitTable from '../CraftingProfitTable.vue'
import { profitableRow } from './fixtures'

describe('CraftingProfitTable currency rows', () => {
  const mountRows = (rows: unknown[]) =>
    mount(CraftingProfitTable, {
      props: { rows: rows as never, sortKey: 'profitCopper', sortDirection: 'desc', selectedRecipeId: null }
    })

  it('marks the total with a small star, no "+ Research Notes" text, and keeps normal profit colors', () => {
    const currencyCosts = {
      walletState: 'UNKNOWN',
      goldMarginIsConditional: true,
      economicsFromHypotheticalCraft: false,
      costs: [{ currencyId: 61, name: 'Research Note', perCraft: 30, total: 300 }]
    }
    const gain = mountRows([{ ...profitableRow, totalProfitCopper: 5000, profitCopper: 500, currencyCosts }])
    const star = gain.find('[data-test="currency-conditional"]')
    expect(star.text()).toBe('*')
    expect(star.attributes('title')).toContain('Research Note')
    expect(gain.text()).not.toContain('Research Note(s)')
    expect(gain.text()).not.toContain('+ Research')
    expect(gain.find('[data-test="total-profit"] .money--gain').exists()).toBe(true)

    const loss = mountRows([{ ...profitableRow, totalProfitCopper: -5000, profitCopper: -500, currencyCosts }])
    expect(loss.find('[data-test="total-profit"] .money--loss').exists()).toBe(true)
    expect(loss.find('[data-test="currency-conditional"]').exists()).toBe(true)
  })

  it('shows no star when every consumed currency was produced by priced crafting batches', () => {
    const currencyCosts = {
      walletState: 'UNKNOWN',
      goldMarginIsConditional: false,
      economicsFromHypotheticalCraft: false,
      costs: [{ currencyId: 61, name: 'Research Note', perCraft: 30, total: 300 }]
    }
    const wrapper = mountRows([{ ...profitableRow, currencyCosts }])
    expect(wrapper.find('[data-test="currency-conditional"]').exists()).toBe(false)
  })

  it('keeps item-only rows unchanged', () => {
    const wrapper = mountRows([profitableRow])
    expect(wrapper.find('[data-test="currency-conditional"]').exists()).toBe(false)
  })
})
