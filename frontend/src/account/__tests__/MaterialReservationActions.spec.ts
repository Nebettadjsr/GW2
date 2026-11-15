import { shellProvide } from '@/sync/__tests__/shellProvide'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { connection } from '@/api/accountSession'
import type { BankContents, MaterialStorage } from '@/api/types'
import { PREFERENCE_KEY_PREFIX, resetMaterialProtection, useMaterialProtection } from '@/materials/materialProtectionState'
import BankScreen from '../BankScreen.vue'
import MaterialsScreen from '../MaterialsScreen.vue'
import { FakeAccountApi } from './accountFixtures'

const ACCOUNT = 'aaaaaaaa-0000-0000-0000-000000000235'
const ITEM = 19697
const T1 = [24290, 24342, 24346, 24272, 24352, 24284, 24296, 24278]
const mounted: VueWrapper[] = []
const bank = (counts = [200, 100]): BankContents => ({
  slotCount: counts.length,
  slots: counts.map((count, slot) => ({ slot, itemId: ITEM, itemName: 'Copper Ore', count, iconUrl: null, rarity: 'Basic' }))
})
const saved = () => JSON.parse(localStorage.getItem(PREFERENCE_KEY_PREFIX + ACCOUNT)!)

beforeEach(() => {
  localStorage.clear()
  resetMaterialProtection()
  connection.accountId = ACCOUNT
  window.history.replaceState(null, '', '/#/bank')
  vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('Unexpected network request'))))
})
afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  connection.accountId = null
  resetMaterialProtection()
  localStorage.clear()
  document.body.replaceChildren()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function open(screen: typeof BankScreen | typeof MaterialsScreen = BankScreen, api = new FakeAccountApi()) {
  if (screen === BankScreen) api.bankHandler = async () => bank()
  const wrapper = mount(screen, { props: { api }, attachTo: document.body, global: { provide: shellProvide() } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, api }
}
async function editor(wrapper: VueWrapper) {
  await wrapper.get('.inventory-tile').trigger('contextmenu', { button: 2, clientX: 12, clientY: 20 })
  await flushPromises()
  await wrapper.get('[data-test="menu-reserve"]').trigger('click')
  await flushPromises()
  return wrapper.get('[data-test="reserve-dialog"]')
}

describe('reservation editor and autosave', () => {
  it('shows summed duplicate Bank stock and the cached name in an accessible focused editor', async () => {
    const { wrapper } = await open()
    const dialog = await editor(wrapper)
    expect(dialog.attributes('role')).toBe('dialog')
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(dialog.attributes('aria-label')).toContain('Copper Ore')
    expect(dialog.text()).toContain('In bank: 300')
    expect(document.activeElement).toBe(dialog.get('input').element)
  })

  it('autosaves a fixed target through stock dropping, growing, and browser-state restart', async () => {
    const { wrapper, api } = await open()
    await editor(wrapper)
    await wrapper.get('[data-test="reserve-input"]').setValue('250')
    await wrapper.get('[data-test="reserve-dialog"]').trigger('submit')
    expect(wrapper.find('[data-test="reserve-dialog"]').exists()).toBe(false)
    expect(saved().quantities.bank).toEqual({ [ITEM]: 250 })
    expect(wrapper.findAll('[data-test="keep-label"]').map(label => label.text())).toEqual(['Keep 250', 'Keep 250'])
    expect(wrapper.get('input[type="checkbox"]').attributes('aria-label')).toContain('250')

    for (const count of [200, 400]) {
      api.bankHandler = async () => bank([count])
      document.dispatchEvent(new Event('visibilitychange'))
      await flushPromises()
      expect(wrapper.get('[data-test="item-count"]').text()).toBe(String(count))
      expect(wrapper.get('[data-test="keep-label"]').text()).toBe('Keep 250')
      expect(saved().quantities.bank).toEqual({ [ITEM]: 250 })
    }
    wrapper.unmount()
    resetMaterialProtection()
    const reopened = await open()
    expect(reopened.wrapper.get('[data-test="keep-label"]').text()).toBe('Keep 250')
    expect(useMaterialProtection().quantityFor('bank', ITEM)).toBe(250)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('counts a finite-only item in the store saved-selection summary', async () => {
    const { wrapper } = await open()
    useMaterialProtection().reserveQuantity(ITEM, 'bank', 250)
    await flushPromises()
    expect(wrapper.get('[data-test="selection-committed"]').text()).toContain('1 item.')
  })

  it('rejects invalid input without replacing the target, accepts more than 2500, and zero clears', async () => {
    const { wrapper } = await open()
    useMaterialProtection().reserveQuantity(ITEM, 'bank', 250)
    await editor(wrapper)
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    for (const invalid of ['-1', '1.5', 'NaN', 'Infinity', '2147483648']) {
      await wrapper.get('[data-test="reserve-input"]').setValue(invalid)
      await wrapper.get('[data-test="reserve-dialog"]').trigger('submit')
      expect(wrapper.get('[data-test="reserve-error"]').attributes('role')).toBe('alert')
      expect(wrapper.get('[data-test="reserve-input"]').attributes('aria-invalid')).toBe('true')
      expect(useMaterialProtection().quantityFor('bank', ITEM)).toBe(250)
      expect(saved().quantities.bank).toEqual({ [ITEM]: 250 })
    }
    expect(writes).not.toHaveBeenCalled()
    await wrapper.get('[data-test="reserve-input"]').setValue('5000')
    await wrapper.get('[data-test="reserve-dialog"]').trigger('submit')
    expect(saved().quantities.bank).toEqual({ [ITEM]: 5000 })
    await editor(wrapper)
    expect((wrapper.get('[data-test="reserve-input"]').element as HTMLInputElement).value).toBe('5000')
    await wrapper.get('[data-test="reserve-input"]').setValue('0')
    await wrapper.get('[data-test="reserve-dialog"]').trigger('submit')
    expect(saved().quantities.bank).toEqual({})
    expect(wrapper.find('[data-test="keep-label"]').exists()).toBe(false)
  })

  it.each(['Cancel', 'Escape', 'outside click'])('%s dismisses without saving and restores the initiating control focus', async action => {
    const { wrapper } = await open()
    useMaterialProtection().reserveQuantity(ITEM, 'bank', 250)
    const checkbox = wrapper.get('input[type="checkbox"]').element as HTMLInputElement
    checkbox.focus()
    await editor(wrapper)
    await wrapper.get('[data-test="reserve-input"]').setValue('999')
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    if (action === 'Cancel') await wrapper.get('[data-test="reserve-cancel"]').trigger('click')
    else if (action === 'Escape') await wrapper.get('[data-test="reserve-input"]').trigger('keydown', { key: 'Escape' })
    else await wrapper.get('[data-test="item-actions-backdrop"]').trigger('click')
    expect(wrapper.find('[data-test="reserve-dialog"]').exists()).toBe(false)
    expect(saved().quantities.bank).toEqual({ [ITEM]: 250 })
    expect(writes).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(checkbox)
  })

  it('wraps Tab and Shift+Tab within the modal instead of reaching background controls', async () => {
    const { wrapper } = await open()
    await editor(wrapper)
    const input = wrapper.get('[data-test="reserve-input"]')
    const cancel = wrapper.get('[data-test="reserve-cancel"]')
    ;(cancel.element as HTMLButtonElement).focus()
    await cancel.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(input.element)
    await input.trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(cancel.element)
  })

  it('autosaves category, preset, and page groups atomically based on ALL mode while preserving absent/other-store targets', async () => {
    const api = new FakeAccountApi()
    const contents: MaterialStorage = { categoryCount: 1, categories: [{
      category: 29, name: 'Crafting Materials', order: 1,
      materials: [...T1, ITEM].map((itemId, position) => ({ position, itemId, count: 300, iconUrl: null, rarity: null }))
    }] }
    api.materialsHandler = async () => contents
    const { wrapper } = await open(MaterialsScreen, api)
    const protection = useMaterialProtection()
    protection.reserveQuantity(T1[0]!, 'materials', 250)
    protection.togglePending(T1[1]!, 'materials')
    protection.reserveQuantity(9999, 'materials', 17)
    protection.reserveQuantity(T1[0]!, 'bank', 30)
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    for (const hook of ['preset-t1', 'preset-t1', 'category-toggle', 'category-toggle', 'selection-toggle', 'selection-toggle']) {
      const before = writes.mock.calls.length
      await wrapper.get(`[data-test="${hook}"]`).trigger('click')
      expect(writes.mock.calls.length).toBe(before + 1)
      expect(protection.quantityFor('materials', 9999)).toBe(17)
      expect(protection.quantityFor('bank', T1[0]!)).toBe(30)
      if (before === 0) {
        expect(protection.committedFor('materials')).toEqual([...T1].sort((a, b) => a - b))
        expect(protection.quantityFor('materials', T1[0]!)).toBe(0)
      }
    }
    expect(protection.committedFor('materials')).toEqual([])
    expect(api.materialCalls).toBe(1)
    expect(fetch).not.toHaveBeenCalled()
  })
})
