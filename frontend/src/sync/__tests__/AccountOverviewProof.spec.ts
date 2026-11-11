import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { connection } from '@/api/accountSession'
import { ApiRequestError } from '@/api/http'
import { systemApi, type GlobalSystemStatus } from '@/api/systemApi'
import { provideSyncOperations } from '../provideSyncOperations'
import SyncScreen from '../SyncScreen.vue'
import { FakeSyncApi, deferred } from './syncFixtures'

/** Grant-scoped own counts in the generic status must follow the current proof exactly. */
let api = new FakeSyncApi()
const Shell = defineComponent({ setup() { provideSyncOperations(api); return () => h(SyncScreen) } })

const withCounts = (characters: number): GlobalSystemStatus => ({
  globalHealth: 'OK', priceHealth: 'OK',
  accountOverview: { characters: { state: 'CURRENT', count: characters } }
})
const NO_COUNTS: GlobalSystemStatus = { globalHealth: 'OK', priceHealth: 'OK', accountOverview: {} }

async function settle(): Promise<void> {
  for (let i = 0; i < 15; i += 1) await Promise.resolve()
  await nextTick()
}
const characters = (w: ReturnType<typeof mount>) => w.find('[data-test="saved-characters"]')

beforeEach(() => {
  api = new FakeSyncApi()
  vi.useFakeTimers()
  connection.phase = 'connected'
  connection.generation = 1
  connection.leaseVersion = 1
  connection.grants = ['account', 'characters']
  vi.spyOn(systemApi, 'readOwnerDetails').mockRejectedValue(new ApiRequestError('no', 'OWNER_REQUIRED', 403))
})
afterEach(() => {
  connection.phase = 'disconnected'
  connection.generation = 0
  connection.leaseVersion = 0
  connection.grants = []
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('own counts follow the current proof', () => {
  it('withdrawsLoadedCountsOnDisconnect', async () => {
    vi.spyOn(systemApi, 'readStatus').mockResolvedValue(withCounts(7))
    const w = mount(Shell)
    await settle()
    expect(characters(w).text()).toBe('7')
    connection.phase = 'disconnected'
    await settle()
    expect(characters(w).exists()).toBe(false)
    w.unmount()
  })

  it('withdrawsCountsOnAccountReplacementUntilTheNewAccountAnswers', async () => {
    const next = deferred<GlobalSystemStatus>()
    const read = vi.spyOn(systemApi, 'readStatus').mockResolvedValueOnce(withCounts(7)).mockReturnValueOnce(next.promise)
    const w = mount(Shell)
    await settle()
    connection.generation += 1
    connection.leaseVersion += 1
    await settle()
    expect(characters(w).exists()).toBe(false)
    next.resolve(withCounts(2))
    await settle()
    expect(characters(w).text()).toBe('2')
    expect(read).toHaveBeenCalledTimes(2)
    w.unmount()
  })

  it('withdrawsCountsAtOnceWhenTheSameAccountProofIsReducedAndShowsTheReducedAnswer', async () => {
    const read = vi.spyOn(systemApi, 'readStatus').mockResolvedValueOnce(withCounts(7)).mockResolvedValue(NO_COUNTS)
    const w = mount(Shell)
    await settle()
    expect(characters(w).text()).toBe('7')
    connection.grants = ['account']
    connection.leaseVersion += 1
    // Withdrawn before any answer for the new proof arrives.
    await nextTick()
    expect(characters(w).exists()).toBe(false)
    await settle()
    expect(read).toHaveBeenCalledTimes(2)
    expect(characters(w).exists()).toBe(false)
    w.unmount()
  })

  it('lateSuccessFromThePriorProofCannotRestoreCountsAndCurrentProofWins', async () => {
    const old = deferred<GlobalSystemStatus>()
    const read = vi.spyOn(systemApi, 'readStatus').mockReturnValueOnce(old.promise).mockResolvedValue(NO_COUNTS)
    const w = mount(Shell)
    await settle()
    connection.grants = ['account']
    connection.leaseVersion += 1
    await settle()
    // The old in-flight request must not block re-discovery for the current proof.
    expect(read).toHaveBeenCalledTimes(2)
    old.resolve(withCounts(7))
    await settle()
    expect(characters(w).exists()).toBe(false)
    w.unmount()
  })

  it('lateErrorFromThePriorProofIsIgnored', async () => {
    const old = deferred<GlobalSystemStatus>()
    vi.spyOn(systemApi, 'readStatus').mockReturnValueOnce(old.promise).mockResolvedValue(withCounts(3))
    const w = mount(Shell)
    await settle()
    connection.leaseVersion += 1
    await settle()
    old.reject(new Error('stale failure'))
    await settle()
    expect(characters(w).text()).toBe('3')
    expect(w.text()).not.toContain('stale failure')
    w.unmount()
  })

  it('disposalPreventsAnOldAnswerFromApplyingAndNothingIsPosted', async () => {
    const old = deferred<GlobalSystemStatus>()
    vi.spyOn(systemApi, 'readStatus').mockReturnValue(old.promise)
    const w = mount(Shell)
    await settle()
    w.unmount()
    old.resolve(withCounts(9))
    await settle()
    expect(api.submissions).toEqual([])
  })

  it('sameProofRefreshRetainsLastPublishedCounts', async () => {
    const read = vi.spyOn(systemApi, 'readStatus').mockResolvedValueOnce(withCounts(7)).mockReturnValue(new Promise(() => undefined))
    const w = mount(Shell)
    await settle()
    await vi.advanceTimersByTimeAsync(5000)
    expect(read).toHaveBeenCalledTimes(2)
    expect(characters(w).text()).toBe('7')
    expect(api.submissions).toEqual([])
    w.unmount()
  })
})
