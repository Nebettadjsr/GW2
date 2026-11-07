import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { connection } from '@/api/accountSession'
import { resetMaterialProtection, useMaterialProtection } from '@/materials/materialProtectionState'
import BankScreen from '../BankScreen.vue'
import MaterialsScreen from '../MaterialsScreen.vue'
import InventoryTile from '../InventoryTile.vue'
import { FakeAccountApi, deferred, materialStorage } from './accountFixtures'

const A = 'aaaaaaaa-0000-0000-0000-000000000001'
const B = 'bbbbbbbb-0000-0000-0000-000000000002'
const stubs = { global: { stubs: { InventoryRefresh: true } } }

async function open(screen: typeof BankScreen | typeof MaterialsScreen, api: FakeAccountApi) {
  const w = mount(screen, { props: { api }, ...stubs })
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
    const w = mount(InventoryTile, { props: { itemId: 5, count: 1, iconUrl: null, title: 't', selected: false } })
    const cb = w.find('input[type="checkbox"]')
    expect(cb.attributes('aria-label')).toBe('Protect item #5')
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

  it('togglingUpdatesEveryDuplicateAcrossStacksAndViews_withoutCommitting', async () => {
    const api = new FakeAccountApi()
    const mats = await open(MaterialsScreen, api)
    const checkboxes = boxes(mats)
    expect(checkboxes).toHaveLength(4) // 12134 twice, 19697 (unowned), 19698
    await checkboxes[0]!.setValue(true)
    expect(checkboxes.map((c) => (c.element as HTMLInputElement).checked)).toEqual([true, false, false, true])
    const state = useMaterialProtection().state
    expect(state.pending).toEqual([12134])
    expect(state.committed).toEqual([])
    mats.unmount()

    const bank = await open(BankScreen, api)
    const bankBoxes = boxes(bank).map((c) => (c.element as HTMLInputElement).checked)
    expect(bankBoxes).toEqual([false, false, true]) // 19697, 24295, 12134
    expect(bank.find('[data-test="selection-state"]').text()).toContain('Unconfirmed changes')
    await box(bank, 2).setValue(false)
    expect(state.pending).toEqual([])
  })

  it('confirmationCommitsPending_emptyConfirmClears_navigationNeverCommits', async () => {
    const api = new FakeAccountApi()
    const state = useMaterialProtection().state
    const w = await open(BankScreen, api)
    expect(w.find('[data-test="selection-state"]').text()).toBe('No pending changes.')
    expect(w.find('[data-test="selection-confirm"]').attributes('disabled')).toBeDefined()
    await box(w, 0).setValue(true)
    w.unmount()
    expect(state.committed).toEqual([])
    const m = await open(MaterialsScreen, api)
    expect(m.find('[data-test="selection-committed"]').text()).toContain('0 items')
    await m.find('[data-test="selection-confirm"]').trigger('click')
    expect(state.committed).toEqual([19697])
    expect(m.find('[data-test="selection-committed"]').text()).toContain('1 item.')
    expect(m.find('[data-test="selection-state"]').text()).toBe('No pending changes.')
    // Clearing: uncheck, then explicitly confirm the empty set.
    await boxes(m).find((c) => (c.element as HTMLInputElement).checked)!.setValue(false)
    expect(state.committed).toEqual([19697])
    await m.find('[data-test="selection-confirm"]').trigger('click')
    expect(state.committed).toEqual([])
  })

  it('discardRestoresCommitted', async () => {
    const w = await open(MaterialsScreen, new FakeAccountApi())
    await box(w, 1).setValue(true)
    await w.find('[data-test="selection-discard"]').trigger('click')
    expect(useMaterialProtection().state.pending).toEqual([])
  })

  it('selectionSurvivesRereadAndRefresh_whileInventoryUpdates', async () => {
    const api = new FakeAccountApi()
    const w = await open(MaterialsScreen, api)
    await box(w, 2).setValue(true)
    await w.find('[data-test="materials-reload"]').trigger('click')
    await flushPromises()
    expect(api.materialCalls).toBe(2)
    expect(useMaterialProtection().state.pending).toEqual([19698])
    expect((box(w, 2).element as HTMLInputElement).checked).toBe(true)
  })

  it('accountSwitchRemovesPriorSelectionImmediately_staleResponseCannotRestoreIt', async () => {
    const api = new FakeAccountApi()
    const first = await open(MaterialsScreen, api)
    await box(first, 2).setValue(true)
    first.unmount()

    const late = deferred<typeof materialStorage>()
    const slow = new FakeAccountApi()
    slow.loadMaterials = () => late.promise as never
    const w = mount(MaterialsScreen, { props: { api: slow }, ...stubs })
    await flushPromises()
    connection.accountId = B
    await flushPromises()
    const state = useMaterialProtection().state
    expect(state.pending).toEqual([])
    expect(state.committed).toEqual([])
    expect(w.find('[data-test="selection-bar"]').exists()).toBe(true)
    late.resolve(materialStorage)
    await flushPromises()
    expect(state.pending).toEqual([])
    expect(boxes(w).every((c) => !(c.element as HTMLInputElement).checked)).toBe(true)
    connection.accountId = null
    await flushPromises()
    expect(w.find('[data-test="selection-bar"]').exists()).toBe(false)
    expect(boxes(w)).toHaveLength(0)
  })
})
