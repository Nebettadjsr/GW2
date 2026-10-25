import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { EffectiveSettings } from '@/api/types'
import ProfitSettingsForm from '../ProfitSettingsForm.vue'

/** The editable Profit settings: each control emits the full settings with exactly one value changed. */
const settings: EffectiveSettings = {
  useOwnMats: true,
  allowBuying: false,
  maxBuyCopper: 12 * 10000 + 34 * 100 + 56,
  listingSell: false,
  listingBuy: true,
  allowDailyCrafts: false
}

function form(props: { settings: EffectiveSettings | null; layoutPart?: 'values' | 'checks' | 'all' } = { settings }) {
  return mount(ProfitSettingsForm, { props })
}

async function type(wrapper: ReturnType<typeof form>, test: string, value: string) {
  const input = wrapper.find(`[data-test="${test}"]`)
  ;(input.element as HTMLInputElement).value = value
  await input.trigger('change')
}

function applied(wrapper: ReturnType<typeof form>): EffectiveSettings[] {
  return (wrapper.emitted('apply') ?? []).map((args) => args[0] as EffectiveSettings)
}

describe('rendering', () => {
  it('shows nothing without settings and ignores any change', () => {
    const wrapper = form({ settings: null })
    expect(wrapper.find('[data-test="settings-form"]').exists()).toBe(false)
    expect(applied(wrapper)).toEqual([])
  })

  it('shows the stored values split into gold, silver and copper and the current modes', () => {
    const wrapper = form()
    expect((wrapper.find('[data-test="setting-maxBuyGold"]').element as HTMLInputElement).value).toBe('12')
    expect((wrapper.find('[data-test="setting-maxBuySilver"]').element as HTMLInputElement).value).toBe('34')
    expect((wrapper.find('[data-test="setting-maxBuyCopper"]').element as HTMLInputElement).value).toBe('56')
    expect((wrapper.find('[data-test="setting-listingSell"]').element as HTMLSelectElement).value).toBe('instant')
    expect((wrapper.find('[data-test="setting-listingBuy"]').element as HTMLSelectElement).value).toBe('listing')
    expect((wrapper.find('[data-test="setting-useOwnMats"]').element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.find('[data-test="setting-allowBuying"]').element as HTMLInputElement).checked).toBe(false)
  })

  it('can show only the values or only the checks', () => {
    const values = form({ settings, layoutPart: 'values' })
    expect(values.find('[data-test="setting-maxBuyGold"]').exists()).toBe(true)
    expect(values.find('[data-test="setting-useOwnMats"]').exists()).toBe(false)
    const checks = form({ settings, layoutPart: 'checks' })
    expect(checks.find('[data-test="setting-maxBuyGold"]').exists()).toBe(false)
    expect(checks.find('[data-test="setting-useOwnMats"]').exists()).toBe(true)
  })
})

describe('changes', () => {
  it('emits each checkbox change with the other settings kept', async () => {
    const wrapper = form()
    await wrapper.find('[data-test="setting-useOwnMats"]').setValue(false)
    await wrapper.find('[data-test="setting-allowBuying"]').setValue(true)
    await wrapper.find('[data-test="setting-allowDailyCrafts"]').setValue(true)
    expect(applied(wrapper)).toEqual([
      { ...settings, useOwnMats: false },
      { ...settings, allowBuying: true },
      { ...settings, allowDailyCrafts: true }
    ])
  })

  it('emits listing and instant choices for sell and buy prices', async () => {
    const wrapper = form()
    await wrapper.find('[data-test="setting-listingSell"]').setValue('listing')
    await wrapper.find('[data-test="setting-listingBuy"]').setValue('instant')
    expect(applied(wrapper)).toEqual([
      { ...settings, listingSell: true },
      { ...settings, listingBuy: false }
    ])
  })

  it('recomposes the max buy price when one coin part changes', async () => {
    const wrapper = form()
    await type(wrapper, 'setting-maxBuyGold', '20')
    await type(wrapper, 'setting-maxBuySilver', '5')
    await type(wrapper, 'setting-maxBuyCopper', '7')
    expect(applied(wrapper).map((s) => s.maxBuyCopper)).toEqual([
      20 * 10000 + 34 * 100 + 56,
      12 * 10000 + 5 * 100 + 56,
      12 * 10000 + 34 * 100 + 7
    ])
  })

  it('clamps silver and copper to 0-99, gold to non-negative, and drops fractions', async () => {
    const wrapper = form()
    await type(wrapper, 'setting-maxBuySilver', '250')
    await type(wrapper, 'setting-maxBuyCopper', '-4')
    await type(wrapper, 'setting-maxBuyGold', '-3')
    await type(wrapper, 'setting-maxBuyGold', '7.9')
    expect(applied(wrapper).map((s) => s.maxBuyCopper)).toEqual([
      12 * 10000 + 99 * 100 + 56,
      12 * 10000 + 34 * 100,
      34 * 100 + 56,
      7 * 10000 + 34 * 100 + 56
    ])
  })

  it('shows zero coin parts when the max buy price is zero', () => {
    const wrapper = form({ settings: { ...settings, maxBuyCopper: 0 } })
    for (const part of ['Gold', 'Silver', 'Copper']) {
      expect((wrapper.find(`[data-test="setting-maxBuy${part}"]`).element as HTMLInputElement).value).toBe('0')
    }
  })
})
