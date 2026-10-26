import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref, type Component } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { ApiRequestError } from '@/api/http'
import type { BankContents, MaterialStorage } from '@/api/types'
import { provideSyncOperations } from '@/sync/provideSyncOperations'
import { FakeSyncApi, acceptance, statusOf } from '@/sync/__tests__/syncFixtures'
import { STATUS_POLL_INTERVAL_MS } from '@/sync/useSyncOperations'
import BankScreen from '../BankScreen.vue'
import MaterialsScreen from '../MaterialsScreen.vue'
import { FakeAccountApi, bankWithEmptySlots, deferred } from './accountFixtures'

const wrappers: Array<ReturnType<typeof mount>> = []
afterEach(() => { wrappers.forEach((wrapper) => wrapper.unmount()); wrappers.length = 0; vi.useRealTimers() })

function open(screen: Component, api: FakeAccountApi, syncApi: FakeSyncApi, visible = ref(true)) {
  const host = defineComponent({ setup() {
    provideSyncOperations(syncApi)
    return () => visible.value ? h(screen, { api }) : h('div')
  } })
  const wrapper = mount(host)
  wrappers.push(wrapper)
  return wrapper
}

describe('Inventory refresh', () => {
  it.each([
    ['Bank', BankScreen, 'bankCalls'], ['Materials', MaterialsScreen, 'materialCalls']
  ] as const)('refreshes account data before re-reading %s', async (_, screen, calls) => {
    const api = new FakeAccountApi()
    const syncApi = new FakeSyncApi()
    const pending = deferred<ReturnType<typeof statusOf>>()
    syncApi.statusHandler = () => pending.promise
    const wrapper = open(screen, api, syncApi)
    await flushPromises()
    expect(syncApi.submissions).toEqual([])
    expect(api[calls]).toBe(1)
    await wrapper.get('[data-test="inventory-refresh"]').trigger('click')
    await flushPromises()
    expect(syncApi.submissions).toEqual(['ACCOUNT_SYNC'])
    expect(api[calls]).toBe(1)
    expect(wrapper.get('[data-test="inventory-refresh"]').attributes('disabled')).toBeDefined()
    pending.resolve(statusOf(acceptance('ACCOUNT_SYNC').statusUrl, 'SUCCEEDED'))
    await flushPromises()
    expect(api[calls]).toBe(2)
    expect(wrapper.get('[data-test="inventory-refresh-status"]').text()).toBe('Completed')
  })

  it('keeps the snapshot when refresh fails and never treats a failed task as success', async () => {
    const api = new FakeAccountApi()
    const syncApi = new FakeSyncApi()
    syncApi.statusHandler = async (url) => statusOf(url, 'FAILED', { error: 'SYNC_FAILED', message: 'Account refresh failed.' })
    const wrapper = open(BankScreen, api, syncApi)
    await flushPromises()
    await wrapper.get('[data-test="inventory-refresh"]').trigger('click')
    await flushPromises()
    expect(api.bankCalls).toBe(1)
    expect(wrapper.findAll('[data-test="bank-slot"]')).toHaveLength(5)
    expect(wrapper.text()).toContain('Account refresh failed.')
    expect(wrapper.get('[data-test="inventory-refresh-status"]').text()).toBe('Failed')
  })

  it('retries an unknown status without submitting another refresh', async () => {
    const api = new FakeAccountApi()
    const syncApi = new FakeSyncApi()
    syncApi.statusHandler = async (url, index) => {
      if (index === 0) throw new ApiRequestError('Unavailable', 'BACKEND_UNREACHABLE', null)
      return statusOf(url, 'SUCCEEDED')
    }
    const wrapper = open(BankScreen, api, syncApi)
    await flushPromises()
    await wrapper.get('[data-test="inventory-refresh"]').trigger('click')
    await flushPromises()
    expect(api.bankCalls).toBe(1)
    expect(wrapper.text()).toContain('outcome could not be confirmed')
    await wrapper.get('[data-test="inventory-refresh-retry"]').trigger('click')
    await flushPromises()
    expect(syncApi.submissions).toEqual(['ACCOUNT_SYNC'])
    expect(api.bankCalls).toBe(2)
  })

  it('waits for a covering refresh instead of reloading after a narrower task', async () => {
    const api = new FakeAccountApi()
    const syncApi = new FakeSyncApi()
    const covering = deferred<ReturnType<typeof statusOf>>()
    syncApi.submitHandler = async (key, index) => ({ ...acceptance(key), coversRequest: index !== 0 })
    syncApi.statusHandler = async (url, index) => index === 0 ? statusOf(url, 'SUCCEEDED') : covering.promise
    const wrapper = open(BankScreen, api, syncApi)
    await flushPromises()
    await wrapper.get('[data-test="inventory-refresh"]').trigger('click')
    await flushPromises()
    expect(api.bankCalls).toBe(1)
    expect(syncApi.submissions).toHaveLength(2)
    covering.resolve(statusOf(acceptance('ACCOUNT_SYNC').statusUrl, 'SUCCEEDED'))
    await flushPromises()
    expect(api.bankCalls).toBe(2)
  })

  it('keeps tracking while navigating away and does not duplicate refresh on return', async () => {
    vi.useFakeTimers()
    const api = new FakeAccountApi()
    const syncApi = new FakeSyncApi()
    const visible = ref(true)
    syncApi.statusHandler = async (url, index) => statusOf(url, index < 2 ? 'RUNNING' : 'SUCCEEDED')
    const wrapper = open(BankScreen, api, syncApi, visible)
    await flushPromises()
    await wrapper.get('[data-test="inventory-refresh"]').trigger('click')
    await flushPromises()
    visible.value = false
    await nextTick()
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS)
    visible.value = true
    await flushPromises()
    expect(wrapper.get('[data-test="inventory-refresh"]').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS)
    await flushPromises()
    expect(syncApi.submissions).toHaveLength(1)
    expect(api.bankCalls).toBe(3) // initial mount, return, successful refresh
  })

  it.each([
    ['Bank', BankScreen, 'bankHandler'], ['Materials', MaterialsScreen, 'materialsHandler']
  ] as const)('distinguishes never synchronized %s from an empty inventory', async (_, screen, handler) => {
    const api = new FakeAccountApi()
    const pending = { freshness: { status: 'PENDING', completedAt: null, refresh: 'CREDENTIAL_REQUIRED' } }
    if (handler === 'bankHandler') api.bankHandler = async (): Promise<BankContents> => ({ ...pending, slotCount: null, slots: null })
    else api.materialsHandler = async (): Promise<MaterialStorage> => ({ ...pending, categoryCount: null, categories: null })
    const wrapper = open(screen, api, new FakeSyncApi())
    await flushPromises()
    expect(wrapper.get('[data-test="inventory-freshness"]').text()).toContain('Not synchronized yet')
    expect(wrapper.find('[data-test="bank-no-slots"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="materials-empty"]').exists()).toBe(false)
  })

  it('renders backend stale status and publication time without inventing freshness', async () => {
    const api = new FakeAccountApi()
    api.bankHandler = async () => ({ ...bankWithEmptySlots,
      freshness: { status: 'STALE', completedAt: '2026-10-26T08:00:00Z', refresh: 'CREDENTIAL_REQUIRED' } })
    const wrapper = open(BankScreen, api, new FakeSyncApi())
    await flushPromises()
    expect(wrapper.get('[data-test="inventory-freshness"]').text()).toContain('may be out of date')
    expect(wrapper.get('time').attributes('datetime')).toBe('2026-10-26T08:00:00Z')
  })
})
