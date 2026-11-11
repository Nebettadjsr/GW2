import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { connection } from '@/api/accountSession'
import { ApiRequestError } from '@/api/http'
import { systemApi, type GlobalSystemStatus } from '@/api/systemApi'
import { provideSyncOperations } from '../provideSyncOperations'
import SyncScreen from '../SyncScreen.vue'
import { FakeSyncApi } from './syncFixtures'

const Shell = defineComponent({
  setup() {
    provideSyncOperations(new FakeSyncApi())
    return () => h(SyncScreen)
  }
})

async function settle(): Promise<void> {
  for (let step = 0; step < 10; step += 1) await Promise.resolve()
  await nextTick()
}

async function mountWith(status: GlobalSystemStatus) {
  vi.spyOn(systemApi, 'readStatus').mockResolvedValue(status)
  vi.spyOn(systemApi, 'readOwnerDetails').mockRejectedValue(new ApiRequestError('no', 'OWNER_REQUIRED', 403))
  const wrapper = mount(Shell)
  await settle()
  return wrapper
}

const text = (w: ReturnType<typeof mount>, key: string) => w.find(`[data-test="${key}"]`).text()
const exists = (w: ReturnType<typeof mount>, key: string) => w.find(`[data-test="${key}"]`).exists()

beforeEach(() => {
  vi.useFakeTimers()
  connection.phase = 'connected'
})
afterEach(() => {
  connection.phase = 'disconnected'
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('ordinary account overview', () => {
  it('showsLoadedZeroDistinctFromNotLoadedAndUnknown', async () => {
    const w = await mountWith({
      globalHealth: 'OK', priceHealth: 'OK',
      accountOverview: {
        characters: { state: 'CURRENT', count: 0 },
        recipes: { state: 'NOT_LOADED', count: null },
        materials: { state: 'UNAVAILABLE', count: null, total: null }
      }
    })
    expect(text(w, 'saved-characters')).toBe('0')
    expect(text(w, 'saved-recipes')).toBe('Not loaded yet')
    expect(text(w, 'saved-material-types')).toContain('Unknown')
    expect(text(w, 'saved-material-total')).toContain('Unknown')
    w.unmount()
  })

  it('marksStaleCountsAndFormatsLargeTotalsAndOmitsUnpermittedSources', async () => {
    const w = await mountWith({
      globalHealth: 'OK', priceHealth: 'OK',
      accountOverview: { materials: { state: 'STALE', count: 3, total: 5_000_000_000 } }
    })
    expect(text(w, 'saved-material-types')).toContain('may be out of date')
    expect(text(w, 'saved-material-total')).toContain((5_000_000_000).toLocaleString())
    expect(exists(w, 'saved-characters')).toBe(false)
    expect(exists(w, 'saved-recipes')).toBe(false)
    w.unmount()
  })

  it('usesOneCompactServiceLineInsteadOfTwoSharedCardsAndSingleColumn', async () => {
    const w = await mountWith({ globalHealth: 'RUNNING', priceHealth: 'PROBLEM' })
    const line = w.find('[data-test="shared-service-line"]')
    expect(line.exists()).toBe(true)
    expect(line.attributes('role')).toBe('status')
    expect(text(w, 'global-health')).toBe('Running')
    expect(text(w, 'price-cache-health')).toBe('Problem')
    expect(w.find('article[data-test="global-refresh-status"]').exists()).toBe(false)
    expect(w.find('article[data-test="price-cache-status"]').exists()).toBe(false)
    expect(w.find('.health-grid').classes()).toContain('health-grid--single')
    w.unmount()
  })

  it('opensWithoutAnyAccountSynchronizationRequest', async () => {
    const api = new FakeSyncApi()
    const Probe = defineComponent({ setup() { provideSyncOperations(api); return () => h(SyncScreen) } })
    const read = vi.spyOn(systemApi, 'readStatus').mockResolvedValue({ globalHealth: 'OK', priceHealth: 'OK' })
    vi.spyOn(systemApi, 'readOwnerDetails').mockRejectedValue(new ApiRequestError('no', 'OWNER_REQUIRED', 403))
    const w = mount(Probe)
    await settle()
    expect(read).toHaveBeenCalledTimes(1)
    expect(api.submissions).toEqual([])
    expect(api.statusRequests).toEqual([])
    w.unmount()
  })
})
