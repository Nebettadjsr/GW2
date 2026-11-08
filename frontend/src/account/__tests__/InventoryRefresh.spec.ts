import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref, type Component } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { connection } from '@/api/accountSession'
import { ApiRequestError } from '@/api/http'
import type { BankContents, MaterialStorage, SyncTaskAccepted } from '@/api/types'
import { resetMaterialProtection, useMaterialProtection } from '@/materials/materialProtectionState'
import { provideSyncOperations } from '@/sync/provideSyncOperations'
import { FakeSyncApi, acceptance, statusOf } from '@/sync/__tests__/syncFixtures'
import { STATUS_POLL_INTERVAL_MS } from '@/sync/useSyncOperations'
import BankScreen from '../BankScreen.vue'
import MaterialsScreen from '../MaterialsScreen.vue'
import { FakeAccountApi, bankWithEmptySlots, deferred, materialStorage } from './accountFixtures'

/**
 * The Bank and Materials screens refresh themselves from their own source (BANK_SYNC /
 * MATERIALS_SYNC) through the shared task tracking; there is no manual button. These tests mount
 * the real screens against the real shared tracking and only control the two network edges: the
 * source-specific POST and the task status route.
 */
const submitted = vi.hoisted(() => ({ views: [] as string[], handler: null as null | ((view: string, index: number) => Promise<unknown>) }))
vi.mock('@/api/syncApi', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  startAccountViewRefresh: (view: string) => {
    const index = submitted.views.length
    submitted.views.push(view)
    return submitted.handler!(view, index)
  }
}))

const ACCOUNT = 'aaaaaaaa-0000-0000-0000-000000000001'
const OPERATION = { bank: 'BANK_SYNC', materials: 'MATERIALS_SYNC' } as const
type View = keyof typeof OPERATION

const wrappers: Array<ReturnType<typeof mount>> = []
const stale = { status: 'STALE', completedAt: '2026-10-26T08:00:00Z', refresh: 'CREDENTIAL_REQUIRED' } as const
const current = { status: 'CURRENT', completedAt: '2026-11-08T12:00:00Z', refresh: 'CREDENTIAL_REQUIRED' } as const

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(Date.parse('2026-11-08T12:00:00Z'))
  localStorage.clear()
  resetMaterialProtection()
  connection.accountId = ACCOUNT
  submitted.views = []
  submitted.handler = async (view) => acceptance(view === 'bank' ? 'BANK_SYNC' : 'MATERIALS_SYNC')
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  connection.accountId = null
  resetMaterialProtection()
  localStorage.clear()
  vi.useRealTimers()
})

/** Backend stand-in whose published snapshot is replaced only by `publish()`. */
function source(view: View, api: FakeAccountApi) {
  const state = { published: false }
  const oldBank: BankContents = { ...bankWithEmptySlots, freshness: stale }
  const newBank: BankContents = { ...bankWithEmptySlots, freshness: current,
    slots: bankWithEmptySlots.slots.map((slot) => slot.slot === 0 ? { ...slot, count: 99 } : slot) }
  const oldMaterials: MaterialStorage = { ...materialStorage, freshness: stale }
  const newMaterials: MaterialStorage = { ...materialStorage, freshness: current,
    categories: materialStorage.categories.map((c, i) => i === 0
      ? { ...c, materials: c.materials.map((m) => m.position === 0 ? { ...m, count: 99 } : m) } : c) }
  api.bankHandler = async () => state.published ? newBank : oldBank
  api.materialsHandler = async () => state.published ? newMaterials : oldMaterials
  return { publish: () => { state.published = true }, view }
}

function open(screen: Component, api: FakeAccountApi, syncApi: FakeSyncApi, visible = ref(true)) {
  const host = defineComponent({ setup() {
    provideSyncOperations(syncApi)
    return () => visible.value ? h(screen, { api }) : h('div')
  } })
  const wrapper = mount(host)
  wrappers.push(wrapper)
  return wrapper
}

const tiles = (wrapper: ReturnType<typeof mount>, view: View) => wrapper.findAll(
  view === 'bank' ? '[data-test="bank-slot"]' : '.material-category').length
const firstCount = (wrapper: ReturnType<typeof mount>, view: View) => (view === 'bank'
  ? wrapper.get('[data-test="bank-slot"]') : wrapper.get('.material-category')).text()
const settle = async () => { await vi.advanceTimersByTimeAsync(0); await flushPromises() }

const SCREENS = [['bank', BankScreen], ['materials', MaterialsScreen]] as const

describe.each(SCREENS)('%s automatic refresh', (view, screen) => {
  const reads = (api: FakeAccountApi) => view === 'bank' ? api.bankCalls : api.materialCalls
  const otherReads = (api: FakeAccountApi) => view === 'bank' ? api.materialCalls : api.bankCalls
  const op = OPERATION[view]

  it('refreshes only its own source and replaces the tiles after publication', async () => {
    const api = new FakeAccountApi()
    const backend = source(view, api)
    const syncApi = new FakeSyncApi()
    const pending = deferred<ReturnType<typeof statusOf>>()
    syncApi.statusHandler = () => pending.promise
    const wrapper = open(screen, api, syncApi)
    await settle()
    expect(submitted.views).toEqual([view])
    expect(syncApi.submissions).toEqual([]) // never ACCOUNT_SYNC or another source
    expect(tiles(wrapper, view)).toBeGreaterThan(0)
    expect(firstCount(wrapper, view)).not.toContain('99')
    expect(wrapper.text()).toContain('Stale data – refreshing')
    backend.publish()
    pending.resolve(statusOf(acceptance(op).statusUrl, 'SUCCEEDED'))
    await settle()
    expect(firstCount(wrapper, view)).toContain('99')
    expect(wrapper.find('.refresh-status').exists()).toBe(false)
    expect(wrapper.find('.refresh-error').exists()).toBe(false)
    expect(otherReads(api)).toBe(0)
    expect(submitted.views).toEqual([view])
  })

  it('keeps tiles and the pending selection when the task fails and does not resubmit', async () => {
    const api = new FakeAccountApi()
    source(view, api)
    const syncApi = new FakeSyncApi()
    syncApi.statusHandler = async (url) => statusOf(url, 'FAILED', { error: 'SYNC_FAILED', message: 'Failed.' })
    const wrapper = open(screen, api, syncApi)
    await settle()
    const before = tiles(wrapper, view)
    await wrapper.get('[data-test="protect-checkbox"]').setValue(true)
    const pending = [...useMaterialProtection().state.pending]
    expect(pending).toHaveLength(1)
    await settle()
    expect(wrapper.get('.refresh-error').text()).toContain('Update failed — showing saved data.')
    expect(tiles(wrapper, view)).toBe(before)
    expect(firstCount(wrapper, view)).not.toContain('99')
    expect(useMaterialProtection().state.pending).toEqual(pending)
    expect(submitted.views).toEqual([view])
  })

  it('treats an unknown outcome as failure: keeps the data and never counts it as success', async () => {
    const api = new FakeAccountApi()
    source(view, api)
    const syncApi = new FakeSyncApi()
    syncApi.statusHandler = async () => { throw new ApiRequestError('Unavailable', 'BACKEND_UNREACHABLE', null) }
    const wrapper = open(screen, api, syncApi)
    await settle()
    await settle()
    expect(wrapper.get('.refresh-error').text()).toContain('showing saved data')
    expect(tiles(wrapper, view)).toBeGreaterThan(0)
    expect(firstCount(wrapper, view)).not.toContain('99')
    expect(submitted.views).toEqual([view]) // the admitted-or-not trigger is not repeated
    expect(otherReads(api)).toBe(0)
  })

  it('waits for a covering task after a narrower one instead of reloading', async () => {
    const api = new FakeAccountApi()
    const backend = source(view, api)
    const syncApi = new FakeSyncApi()
    const covering = deferred<ReturnType<typeof statusOf>>()
    submitted.handler = async (v, index): Promise<SyncTaskAccepted> =>
      ({ ...acceptance(v === 'bank' ? 'BANK_SYNC' : 'MATERIALS_SYNC'), coversRequest: index !== 0 })
    syncApi.statusHandler = async (url, index) => index === 0 ? statusOf(url, 'SUCCEEDED') : covering.promise
    const wrapper = open(screen, api, syncApi)
    await settle()
    await settle()
    expect(submitted.views).toEqual([view, view])
    const readsWhileWaiting = reads(api)
    expect(firstCount(wrapper, view)).not.toContain('99')
    expect(wrapper.text()).toContain('Stale data – refreshing')
    backend.publish()
    covering.resolve(statusOf(acceptance(op).statusUrl, 'SUCCEEDED'))
    await settle()
    expect(reads(api)).toBeGreaterThan(readsWhileWaiting)
    expect(firstCount(wrapper, view)).toContain('99')
  })

  it('keeps tracking while the screen is left and joins the same task on return', async () => {
    const api = new FakeAccountApi()
    const backend = source(view, api)
    const syncApi = new FakeSyncApi()
    const visible = ref(true)
    syncApi.statusHandler = async (url, index) => statusOf(url, index < 2 ? 'RUNNING' : 'SUCCEEDED')
    const wrapper = open(screen, api, syncApi, visible)
    await settle()
    visible.value = false
    await nextTick()
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS)
    visible.value = true
    await settle()
    expect(wrapper.text()).toContain('Stale data – refreshing')
    backend.publish()
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 2)
    await settle()
    expect(submitted.views).toEqual([view]) // no duplicate admission after navigation
    expect(firstCount(wrapper, view)).toContain('99')
  })

  it('ignores a task that finishes after the account was replaced', async () => {
    const api = new FakeAccountApi()
    const backend = source(view, api)
    const syncApi = new FakeSyncApi()
    const pending = deferred<ReturnType<typeof statusOf>>()
    syncApi.statusHandler = () => pending.promise
    const wrapper = open(screen, api, syncApi)
    await settle()
    const readsBefore = reads(api)
    connection.generation += 1
    backend.publish()
    pending.resolve(statusOf(acceptance(op).statusUrl, 'SUCCEEDED'))
    await settle()
    expect(reads(api)).toBe(readsBefore)
    expect(firstCount(wrapper, view)).not.toContain('99')
  })

  it('distinguishes a never published snapshot from a successfully empty one', async () => {
    const api = new FakeAccountApi()
    const pending = { freshness: { status: 'PENDING', completedAt: null, refresh: 'CREDENTIAL_REQUIRED' } } as const
    const syncApi = new FakeSyncApi()
    syncApi.statusHandler = () => new Promise(() => undefined)
    if (view === 'bank') api.bankHandler = async (): Promise<BankContents> => ({ ...pending, slotCount: null, slots: null })
    else api.materialsHandler = async (): Promise<MaterialStorage> => ({ ...pending, categoryCount: null, categories: null })
    const never = open(screen, api, syncApi)
    await settle()
    expect(never.text()).toContain('Stale data – refreshing') // PENDING is refreshed, not shown as empty
    expect(never.find('[data-test="bank-no-slots"]').exists()).toBe(false)
    expect(never.find('[data-test="materials-empty"]').exists()).toBe(false)
    expect(submitted.views).toEqual([view])

    const emptyApi = new FakeAccountApi()
    if (view === 'bank') emptyApi.bankHandler = async () => ({ slotCount: 0, slots: [], freshness: current })
    else emptyApi.materialsHandler = async () => ({ categoryCount: 0, categories: [], freshness: current })
    const empty = open(screen, emptyApi, new FakeSyncApi())
    await settle()
    expect(empty.text()).not.toContain('Stale data – refreshing')
    expect(empty.find(view === 'bank' ? '[data-test="bank-no-slots"]' : '[data-test="materials-empty"]').exists()).toBe(true)
    expect(submitted.views).toEqual([view]) // the current empty snapshot started no refresh
  })
})
