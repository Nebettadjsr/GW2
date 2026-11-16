import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { connection } from '@/api/accountSession'
import type { SyncApi } from '@/api/syncApi'
import { resetMaterialProtection, useMaterialProtection } from '@/materials/materialProtectionState'
import CraftingProfitScreen from '@/crafting/CraftingProfitScreen.vue'
import { FakeCraftingApi } from '@/crafting/__tests__/fixtures'
import { shellProvide } from '@/sync/__tests__/shellProvide'
import BankScreen from '../BankScreen.vue'
import { FakeAccountApi } from './accountFixtures'
import { BANK_ITEM as ITEM, allocationRows, fourStackBank, type BankSettings, type BankSnapshot } from './bankReservationFixtures'

const A = 'aaaaaaaa-0000-0000-0000-000000000235'
const B = 'bbbbbbbb-0000-0000-0000-000000000235'
const mounted: VueWrapper[] = []
let snapshot: BankSnapshot
beforeEach(() => {
  localStorage.clear()
  resetMaterialProtection()
  connection.accountId = A
  snapshot = fourStackBank()
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
async function open() {
  const api = new FakeAccountApi()
  api.bankHandler = async () => snapshot
  const wrapper = mount(BankScreen, { props: { api }, attachTo: document.body, global: { provide: shellProvide() } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}
const boxes = (wrapper: VueWrapper) => wrapper.findAll('[data-test="protect-checkbox"]').map(box => box.element as HTMLInputElement)
function expectRows(wrapper: VueWrapper, checked: boolean[], mixed = checked.map(() => false)) {
  expect(boxes(wrapper).map(box => box.checked)).toEqual(checked)
  expect(boxes(wrapper).map(box => box.indeterminate)).toEqual(mixed)
}
async function clickSlot(wrapper: VueWrapper, slot: number) {
  ;(wrapper.get(`[data-slot="${slot}"] [data-test="item-count"]`).element as HTMLElement).click()
  await flushPromises()
}
async function reserve(wrapper: VueWrapper, target: number) {
  await wrapper.get('[data-slot="2"] .inventory-tile').trigger('contextmenu', { button: 2 })
  await wrapper.get('[data-test="menu-reserve"]').trigger('click')
  await wrapper.get('[data-test="reserve-input"]').setValue(String(target))
  await wrapper.get('[data-test="reserve-dialog"]').trigger('submit')
  await flushPromises()
}
async function settings(): Promise<BankSettings> {
  useMaterialProtection().setActivation(true)
  const api = new FakeCraftingApi()
  const profit = mount(CraftingProfitScreen, { props: { api, refreshApi: {} as SyncApi } })
  mounted.push(profit)
  await flushPromises()
  const result = api.profitRequests.at(-1)!.settings as BankSettings
  profit.unmount()
  return result
}
async function refresh() {
  document.dispatchEvent(new Event('visibilitychange'))
  await flushPromises()
}

describe('Bank stack reservation intent through rendered controls', () => {
  it('clicks the actual third stack exactly once and sends all four guarded rows including zeros', async () => {
    const wrapper = await open()
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    await clickSlot(wrapper, 2)
    expectRows(wrapper, [false, false, true, false])
    expect(writes).toHaveBeenCalledTimes(1)
    expect(useMaterialProtection().quantityFor('bank', ITEM)).toBe(250)
    expect(await settings()).toMatchObject({ protectedItemIds: [], protectedBankItemIds: [],
      protectedBankQuantities: { [ITEM]: 250 }, protectedBankAllocations: allocationRows([0, 0, 250, 0]) })
  })

  it.each([
    [250, [true, false, false, false], [false, false, false, false]],
    [300, [true, false, false, false], [false, true, false, false]]
  ] as const)('numeric target %i allocates ascending slots with native full/mixed/ordinary states', async (target, checked, mixed) => {
    const wrapper = await open()
    await reserve(wrapper, target)
    expectRows(wrapper, [...checked], [...mixed])
    expect(await settings()).toMatchObject({ protectedItemIds: [], protectedBankItemIds: [],
      protectedBankQuantities: { [ITEM]: target }, protectedBankAllocations: [] })
  })

  it.each([
    [0, 50, [false, false, false, false], [false, true, false, false], [0, 50, 0, 0]],
    [1, 500, [true, true, false, false], [false, false, false, false], [250, 250, 0, 0]],
    [2, 550, [true, false, true, false], [false, true, false, false], [250, 50, 250, 0]]
  ] as const)('clicking slot %i from target300 preserves the other current allocations', async (slot, target, checked, mixed, units) => {
    const wrapper = await open()
    await reserve(wrapper, 300)
    await clickSlot(wrapper, slot)
    expectRows(wrapper, [...checked], [...mixed])
    expect(useMaterialProtection().quantityFor('bank', ITEM)).toBe(target)
    expect(await settings()).toMatchObject({ protectedBankQuantities: { [ITEM]: target },
      protectedBankAllocations: allocationRows([...units]) })
  })

  it('numeric editing replaces manual hints, while bulk ALL then NONE remains source isolated', async () => {
    const wrapper = await open()
    useMaterialProtection().reserveQuantity(ITEM, 'materials', 17)
    await clickSlot(wrapper, 2)
    await reserve(wrapper, 300)
    expectRows(wrapper, [true, false, false, false], [false, true, false, false])
    expect((await settings()).protectedBankAllocations).toEqual([])
    await wrapper.get('[data-test="selection-toggle"]').trigger('click')
    expectRows(wrapper, [true, true, true, true])
    expect(await settings()).toMatchObject({ protectedBankItemIds: [ITEM], protectedBankQuantities: {},
      protectedBankAllocations: [], protectedMaterialQuantities: { [ITEM]: 17 } })
    await wrapper.get('[data-test="selection-toggle"]').trigger('click')
    expectRows(wrapper, [false, false, false, false])
    expect(useMaterialProtection().quantityFor('materials', ITEM)).toBe(17)
  })

  it('restores the chosen third stack after a browser-state restart, then forgets only the deleted account', async () => {
    const wrapper = await open()
    await clickSlot(wrapper, 2)
    wrapper.unmount()
    resetMaterialProtection()
    const restored = await open()
    expectRows(restored, [false, false, true, false])
    expect((await settings()).protectedBankAllocations).toEqual(allocationRows([0, 0, 250, 0]))
    restored.unmount()
    connection.accountId = B
    await flushPromises()
    useMaterialProtection().reserveQuantity(ITEM, 'materials', 17)
    useMaterialProtection().forgetAccount(A)
    expect(useMaterialProtection().quantityFor('materials', ITEM)).toBe(17)
    connection.accountId = A
    await flushPromises()
    const empty = await open()
    expectRows(empty, [false, false, false, false])
    expect(useMaterialProtection().quantityFor('bank', ITEM)).toBe(0)
    await clickSlot(empty, 0)
    expect((await settings()).protectedBankAllocations).toEqual(allocationRows([250, 0, 0, 0]))
  })

  it('keeps the latest manual allocation in the account shadow when storage fails across A→B→A', async () => {
    const wrapper = await open()
    await reserve(wrapper, 300)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('full', 'QuotaExceededError') })
    await clickSlot(wrapper, 2)
    expect(wrapper.get('[data-test="selection-storage-notice"]').text()).toContain('will not be kept after you restart')
    wrapper.unmount()
    connection.accountId = B
    await flushPromises()
    const other = await open()
    expectRows(other, [false, false, false, false])
    useMaterialProtection().reserveQuantity(ITEM, 'materials', 17)
    other.unmount()
    connection.accountId = A
    await flushPromises()
    const restored = await open()
    expectRows(restored, [true, false, true, false], [false, true, false, false])
    expect(await settings()).toMatchObject({ protectedBankQuantities: { [ITEM]: 550 },
      protectedBankAllocations: allocationRows([250, 50, 250, 0]), protectedMaterialQuantities: {} })
  })

  it('does not rewrite preferences on an unchanged snapshot', async () => {
    const wrapper = await open()
    await clickSlot(wrapper, 2)
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    await refresh()
    expectRows(wrapper, [false, false, true, false])
    expect(writes).not.toHaveBeenCalled()
  })

  it('rejects a manual sum above the supported target range without destroying the previous target or hints', async () => {
    // Both counts fit the supported positive-int transport; only the requested combined target overflows.
    const maximum = 2147483647
    snapshot.slots = [{ ...snapshot.slots[0]!, count: maximum }, { ...snapshot.slots[1]!, count: 1 }]
    const wrapper = await open()
    await clickSlot(wrapper, 0)
    const previous = allocationRows([maximum, 0], snapshot)
    expect((await settings()).protectedBankAllocations).toEqual(previous)
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    boxes(wrapper)[1]!.click() // A one-unit Bank stack intentionally has no count badge.
    await flushPromises()
    expect(useMaterialProtection().quantityFor('bank', ITEM)).toBe(maximum)
    expectRows(wrapper, [true, false])
    expect(writes).not.toHaveBeenCalled()
    expect((await settings()).protectedBankAllocations).toEqual(previous)
  })

  it.each(['count', 'item', 'binding', 'owner', 'extra row', 'missing row'])(
    'redistributes the unchanged fixed target after a %s guard change, with at most one cleanup write', async change => {
      const wrapper = await open()
      await clickSlot(wrapper, 2)
      snapshot = structuredClone(snapshot)
      if (change === 'count') snapshot.slots[3]!.count = 100
      if (change === 'item') snapshot.slots[3]!.itemId = 19698
      if (change === 'binding') snapshot.slots[3]!.binding = 'Unrecognized raw binding'
      if (change === 'owner') snapshot.slots[3]!.boundTo = 'Other Character'
      if (change === 'extra row') snapshot.slots.push({ ...snapshot.slots[0]!, slot: 4 })
      if (change === 'missing row') snapshot.slots.pop()
      const writes = vi.spyOn(Storage.prototype, 'setItem')
      await refresh()
      expect(writes.mock.calls.length).toBeLessThanOrEqual(1)
      expect(boxes(wrapper).map(box => box.checked)).toEqual(snapshot.slots.map(row => row.slot === 0))
      expect(boxes(wrapper).every(box => !box.indeterminate)).toBe(true)
      expect(useMaterialProtection().quantityFor('bank', ITEM)).toBe(250)
      expect(wrapper.get('[data-test="reservation-note"]').text()).toContain('250')
    }
  )

  it('keeps a fixed target through stock below target, absence, and a later split into new slots', async () => {
    const wrapper = await open()
    await reserve(wrapper, 300)
    snapshot = { ...snapshot, slots: [{ ...snapshot.slots[0]!, count: 100 }] }
    await refresh()
    expectRows(wrapper, [true])
    expect(useMaterialProtection().quantityFor('bank', ITEM)).toBe(300)
    snapshot = { ...snapshot, slots: [] }
    await refresh()
    expect(useMaterialProtection().quantityFor('bank', ITEM)).toBe(300)
    snapshot = fourStackBank()
    snapshot.slots = snapshot.slots.map(row => ({ ...row, slot: row.slot + 30 }))
    await refresh()
    expectRows(wrapper, [true, false, false, false], [false, true, false, false])
    expect((await settings()).protectedBankQuantities).toEqual({ [ITEM]: 300 })
  })
})
