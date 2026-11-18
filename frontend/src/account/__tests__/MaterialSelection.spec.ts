import { shellProvide } from '@/sync/__tests__/shellProvide'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { connection } from '@/api/accountSession'
import { resetMaterialProtection, useMaterialProtection } from '@/materials/materialProtectionState'
import BankScreen from '../BankScreen.vue'
import MaterialsScreen from '../MaterialsScreen.vue'
import InventoryTile from '../InventoryTile.vue'
import { PREFERENCE_KEY_PREFIX } from '@/materials/materialProtectionState'
import { FakeAccountApi, deferred, materialStorage } from './accountFixtures'

const A = 'aaaaaaaa-0000-0000-0000-000000000001'
const B = 'bbbbbbbb-0000-0000-0000-000000000002'
const stubs = () => ({ global: { provide: shellProvide() } })

async function open(screen: typeof BankScreen | typeof MaterialsScreen, api: FakeAccountApi) {
  const w = mount(screen, { props: { api }, ...stubs() })
  await flushPromises()
  return w
}
const boxes = (w: ReturnType<typeof mount>) => w.findAll('[data-test="protect-checkbox"]')
const box = (w: ReturnType<typeof mount>, i: number) => boxes(w)[i]!

beforeEach(() => {
  localStorage.clear()
  resetMaterialProtection()
  connection.accountId = A
})
afterEach(() => {
  connection.accountId = null
  resetMaterialProtection()
  localStorage.clear()
})

describe('InventoryTile selection control', () => {
  it('hasNoCheckboxUnlessSelectable_andNamesTheItem', async () => {
    const plain = mount(InventoryTile, { props: { itemId: 5, count: 1, iconUrl: null, title: 't' } })
    expect(plain.find('input').exists()).toBe(false)
    const w = mount(InventoryTile, { props: { itemId: 5, count: 1, iconUrl: null, title: 'Item #5', selected: false } })
    const cb = w.find('input[type="checkbox"]')
    expect(cb.attributes('aria-label')).toBe('Protect Item #5')
    await cb.setValue(true)
    expect(w.emitted('toggle')).toEqual([[5]])
  })
})

describe('Bank and Materials selection', () => {
  it('bankOccupiedSlotsHaveCheckboxes_emptySlotsDoNot', async () => {
    const w = await open(BankScreen, new FakeAccountApi())
    expect(boxes(w)).toHaveLength(3)
    expect(w.findAll('[data-test="bank-empty-slot"]')).toHaveLength(2)
  })

  it('togglingAutosavesEveryDuplicateInOnlyTheEditedStore', async () => {
    const api = new FakeAccountApi()
    const mats = await open(MaterialsScreen, api)
    const checkboxes = boxes(mats)
    expect(checkboxes).toHaveLength(4) // 12134 twice, 19697 (unowned), 19698
    await checkboxes[0]!.setValue(true)
    expect(checkboxes.map((c) => (c.element as HTMLInputElement).checked)).toEqual([true, false, false, true])
    const state = useMaterialProtection().state
    expect(state.committed).toEqual([12134])
    expect(JSON.parse(localStorage.getItem(PREFERENCE_KEY_PREFIX + A)!).selections)
      .toEqual({ bank: [], materials: [12134] })
    mats.unmount()

    const bank = await open(BankScreen, api)
    // A saved Materials selection does not select the same item in Bank.
    const bankBoxes = boxes(bank).map((c) => (c.element as HTMLInputElement).checked)
    expect(bankBoxes).toEqual([false, false, false]) // 19697, 24295, 12134
    expect(bank.find('[data-test="selection-state"]').exists()).toBe(false)
    await box(bank, 2).setValue(true)
    expect(useMaterialProtection().quantityFor('bank', 12134)).toBe(250)
    expect(useMaterialProtection().committedFor('materials')).toEqual([12134])
    await box(bank, 2).setValue(false)
    expect(useMaterialProtection().quantityFor('bank', 12134)).toBe(0)
    expect(useMaterialProtection().committedFor('bank')).toEqual([])
    expect(useMaterialProtection().committedFor('materials')).toEqual([12134])
    expect(state.committed).toEqual([12134])
    expect(JSON.parse(localStorage.getItem(PREFERENCE_KEY_PREFIX + A)!).selections)
      .toEqual({ bank: [], materials: [12134] })
  })

  it('savesBeforeNavigation_andClearingTheLastSelectionDisablesProtection', async () => {
    const api = new FakeAccountApi()
    const state = useMaterialProtection().state
    const w = await open(BankScreen, api)
    expect(w.find('[data-test="selection-confirm"]').exists()).toBe(false)
    await box(w, 0).setValue(true)
    expect(useMaterialProtection().quantityFor('bank', 19697)).toBe(42)
    expect(JSON.parse(localStorage.getItem(PREFERENCE_KEY_PREFIX + A)!).quantities.bank).toEqual({ 19697: 42 })
    useMaterialProtection().setActivation(true)
    w.unmount()
    expect(useMaterialProtection().quantityFor('bank', 19697)).toBe(42)
    const other = await open(MaterialsScreen, api)
    expect(other.find('[data-test="selection-confirm"]').exists()).toBe(false)
    expect(useMaterialProtection().committedFor('materials')).toEqual([])
    expect(useMaterialProtection().quantityFor('bank', 19697)).toBe(42)
    other.unmount()
    const m = await open(BankScreen, api)
    expect(m.find('[data-test="selection-committed"]').text()).toContain('1 item.')
    await boxes(m).find((c) => (c.element as HTMLInputElement).checked)!.setValue(false)
    expect(useMaterialProtection().quantityFor('bank', 19697)).toBe(0)
    expect(state.activation).toBe(false)
    expect(JSON.parse(localStorage.getItem(PREFERENCE_KEY_PREFIX + A)!).selections.bank).toEqual([])
    await box(m, 0).setValue(true)
    expect(state.activation).toBe(false)
  })

  it('hasNoGlobalSaveDiscardOrPendingControls_andKeepsTheSavedPickOnReopen', async () => {
    const w = await open(MaterialsScreen, new FakeAccountApi())
    await box(w, 1).setValue(true)
    for (const hook of ['selection-confirm', 'selection-discard', 'selection-state']) {
      expect(w.find(`[data-test="${hook}"]`).exists()).toBe(false)
    }
    expect(useMaterialProtection().committedFor('materials')).toEqual([19697])
    w.unmount()
    const reopened = await open(MaterialsScreen, new FakeAccountApi())
    expect((box(reopened, 1).element as HTMLInputElement).checked).toBe(true)
    expect(JSON.parse(localStorage.getItem(PREFERENCE_KEY_PREFIX + A)!).selections.materials).toEqual([19697])
  })

  it('selectionFollowsItemIds_whenReplacementInventoryChangesQuantitiesPositionsAndDuplicates', async () => {
    const api = new FakeAccountApi()
    const w = await open(MaterialsScreen, api)
    const state = useMaterialProtection().state
    await box(w, 0).setValue(true) // 12134
    await box(w, 2).setValue(true) // 19698
    await box(w, 1).setValue(true) // 19697 also saves immediately
    expect(state.committed).toEqual([12134, 19697, 19698])

    // Replacement: reordered positions, new counts, 12134 now three times, 19698 moved, a new item 555.
    const replacement = {
      categoryCount: 1,
      categories: [{
        category: 30, name: 'Zephyrite Supplies', order: 6,
        materials: [
          { position: 0, itemId: 555, count: 9, iconUrl: null, rarity: null },
          { position: 1, itemId: 19698, count: 1, iconUrl: null, rarity: null },
          { position: 2, itemId: 12134, count: 40, iconUrl: null, rarity: null },
          { position: 3, itemId: 12134, count: 2, iconUrl: null, rarity: null },
          { position: 4, itemId: 12134, count: 7, iconUrl: null, rarity: null },
          { position: 5, itemId: 19697, count: 0, iconUrl: null, rarity: null }
        ]
      }]
    }
    api.materialsHandler = () => Promise.resolve(replacement as typeof materialStorage)
    // The visible view re-reads on its own; the selection is keyed by item id.
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(api.materialCalls).toBe(2)
    expect(w.findAll('[data-test="material-stack"]').map((s) => s.text())).toEqual(
      expect.arrayContaining([expect.stringContaining('40')]))
    expect(boxes(w).map((c) => (c.element as HTMLInputElement).checked)).toEqual([false, true, true, true, true, true])
    expect(boxes(w).map((c) => c.attributes('aria-label'))).toEqual(
      ['Protect Item #555', 'Protect Item #19698', 'Protect Item #12134', 'Protect Item #12134', 'Protect Item #12134', 'Protect Item #19697'])
    expect(state.committed).toEqual([12134, 19697, 19698])
    expect(w.find('[data-test="selection-state"]').exists()).toBe(false)
    expect(JSON.parse(localStorage.getItem(PREFERENCE_KEY_PREFIX + A)!).selections.materials)
      .toEqual([12134, 19697, 19698])

    // A further automatic reread also leaves both sets untouched.
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(api.materialCalls).toBe(3)
    expect(state.committed).toEqual([12134, 19697, 19698])
  })

  it('bankRefreshWithMovedStacks_keepsTheFixedAmountAndRedistributesItToCurrentSlots', async () => {
    const api = new FakeAccountApi()
    const w = await open(BankScreen, api)
    await box(w, 2).setValue(true) // 12134
    api.bankHandler = () => Promise.resolve({
      slotCount: 3,
      slots: [
        { slot: 0, itemId: 12134, count: 5, iconUrl: null, rarity: null },
        { slot: 1, itemId: null, count: null, iconUrl: null, rarity: null },
        { slot: 2, itemId: 12134, count: 1, iconUrl: null, rarity: null }
      ]
    } as never)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(useMaterialProtection().quantityFor('bank', 12134)).toBe(250)
    // The saved amount exceeds current stock; reserve both current stacks without clamping 250 to 6.
    expect(boxes(w).map((c) => (c.element as HTMLInputElement).checked)).toEqual([true, true])
    expect(JSON.parse(localStorage.getItem(PREFERENCE_KEY_PREFIX + A)!).quantities.bank).toEqual({ 12134: 250 })
  })

  it('showsSavedSelectionWording_andConcisePurposeHint', async () => {
    const w = await open(MaterialsScreen, new FakeAccountApi())
    const text = w.find('[data-test="selection-bar"]').text()
    expect(text).toContain('Protected: 0 items.')
    expect(text).toContain('saved automatically')
    expect(text).not.toContain('Committed')
    expect(w.find('[data-test="selection-activation-note"]').text())
      .toContain('Crafting Profit calculations')
    expect(w.find('[data-test="selection-storage-notice"]').exists()).toBe(false)
  })

  it('surfacesStorageProblem_whenBrowserStorageThrows_andKeepsMemoryCommitUsable', async () => {
    const real = Storage.prototype.setItem
    Storage.prototype.setItem = () => { throw new DOMException('full', 'QuotaExceededError') }
    try {
      const w = await open(MaterialsScreen, new FakeAccountApi())
      await box(w, 2).setValue(true)
      const notice = w.find('[data-test="selection-storage-notice"]')
      expect(notice.text()).toContain('will not be kept after you restart')
      expect(notice.attributes('role')).toBe('status')
      expect(useMaterialProtection().state.committed).toEqual([19698])
      expect(w.find('[data-test="selection-committed"]').text()).toContain('1 item.')
      expect(localStorage.getItem(PREFERENCE_KEY_PREFIX + A)).toBeNull()
    } finally {
      Storage.prototype.setItem = real
    }
  })

  it('surfacesStorageProblem_whenReadFails', async () => {
    const real = Storage.prototype.getItem
    Storage.prototype.getItem = () => { throw new Error('denied') }
    try {
      resetMaterialProtection()
      const w = await open(BankScreen, new FakeAccountApi())
      expect(w.find('[data-test="selection-storage-notice"]').text()).toContain('cannot save or restore')
    } finally {
      Storage.prototype.getItem = real
    }
  })

  it('accountSwitchRemovesPriorSelectionImmediately_staleResponseCannotRestoreIt', async () => {
    const api = new FakeAccountApi()
    const first = await open(MaterialsScreen, api)
    await box(first, 2).setValue(true)
    first.unmount()

    const late = deferred<typeof materialStorage>()
    const slow = new FakeAccountApi()
    slow.loadMaterials = () => late.promise as never
    const w = mount(MaterialsScreen, { props: { api: slow }, ...stubs() })
    await flushPromises()
    connection.accountId = B
    await flushPromises()
    const state = useMaterialProtection().state
    expect(state.committed).toEqual([])
    expect(w.find('[data-test="selection-bar"]').exists()).toBe(true)
    late.resolve(materialStorage)
    await flushPromises()
    expect(state.committed).toEqual([])
    expect(boxes(w).every((c) => !(c.element as HTMLInputElement).checked)).toBe(true)
    connection.accountId = null
    await flushPromises()
    expect(w.find('[data-test="selection-bar"]').exists()).toBe(false)
    expect(boxes(w)).toHaveLength(0)
  })
})

describe('Selection presets', () => {
  it('presetsAutosaveTheirExactGroup_manualAdjustable_withoutFetching', async () => {
    const api = new FakeAccountApi()
    const t1 = [24290, 24342, 24346, 24272, 24352, 24284, 24296, 24278]
    const t6 = [24295, 24358, 24351, 24277, 24357, 24289, 24300, 24283]
    const stack = (itemId: number, position: number) => ({ position, itemId, count: 1, iconUrl: null, rarity: null })
    // Presets are offered only inside the categories that own them (29 tiered, 37 extras).
    api.materialsHandler = () => Promise.resolve({
      categoryCount: 2,
      categories: [
        { category: 29, name: 'Crafting Materials', order: 1, materials: [...t1, ...t6].map(stack) },
        { category: 37, name: 'Common Crafting Materials', order: 2, materials: [19697, 19675, 19976, 19721, 19925].map(stack) }
      ]
    } as unknown as typeof materialStorage)
    const w = await open(MaterialsScreen, api)
    const state = useMaterialProtection().state
    const fetches = vi.spyOn(globalThis, 'fetch')
    const manual = boxes(w).findIndex((c) => c.attributes('aria-label') === 'Protect Item #19697')
    await box(w, manual).setValue(true)
    await w.find('[data-test="preset-extra"]').trigger('click')
    expect(state.committed).toEqual([19675, 19697, 19721, 19925, 19976])
    // A preset is a toggle: a second click removes only its own group and keeps the manual pick.
    await w.find('[data-test="preset-extra"]').trigger('click')
    expect(state.committed).toEqual([19697])
    await w.find('[data-test="preset-extra"]').trigger('click')
    await w.find('[data-test="preset-t1"]').trigger('click')
    expect(state.committed).toHaveLength(13)
    expect(JSON.parse(localStorage.getItem(PREFERENCE_KEY_PREFIX + A)!).selections.materials).toEqual(state.committed)
    expect(fetches).not.toHaveBeenCalled()
    fetches.mockRestore()
    // Manual removal of a manually picked item.
    await box(w, manual).setValue(false)
    expect(state.committed).not.toContain(19697)
    await w.find('[data-test="preset-extra"]').trigger('click')
    await w.find('[data-test="preset-t1"]').trigger('click')
    expect(state.committed).toEqual([])
    await w.find('[data-test="preset-t6"]').trigger('click')
    expect(state.committed).toEqual([...t6].sort((a, b) => a - b))
    expect(state.committed).toHaveLength(8)
    expect(JSON.parse(localStorage.getItem(PREFERENCE_KEY_PREFIX + A)!).selections.materials)
      .toEqual([...t6].sort((a, b) => a - b))
  })
})
