import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, type PropType } from 'vue'
import { connection } from '@/api/accountSession'
import { ACCOUNT_CHANGED_CODE, ApiRequestError } from '@/api/http'
import { systemApi, type OwnerSystemDetails } from '@/api/systemApi'
import { provideSyncOperations } from '../provideSyncOperations'
import SyncScreen from '../SyncScreen.vue'
import { STATUS_POLL_INTERVAL_MS } from '../useSyncOperations'
import { FakeSyncApi, acceptance, deferred, statusOf } from './syncFixtures'

/** Owner/ordinary projection of System Status (STORY-STATUS-002); all backend answers controlled. */
const Shell = defineComponent({
  props: { api: { type: Object as PropType<FakeSyncApi>, required: true } },
  setup(props) {
    provideSyncOperations(props.api)
    return () => h(SyncScreen)
  }
})

const DETAILS: OwnerSystemDetails = {
  running: false, lastCheckedAt: null, lastChangedAt: null, lastRecipeSyncAt: null, lastGraphRebuildAt: null,
  lastFailure: 'upstream 503', cachedPriceItems: 12, stalePriceItems: 3, newestPriceFetchedAt: null, priceCacheError: null
}
const FORBIDDEN = new ApiRequestError('restricted', 'OWNER_REQUIRED', 403)
const OWNER_TRIGGERS = ['FULL_ITEM_CATALOG', 'FULL_RECIPE_CATALOG', 'FULL_TP_CATALOG']

beforeEach(() => {
  vi.useFakeTimers()
  connection.phase = 'connected'
  connection.generation = 1
  vi.spyOn(systemApi, 'readStatus').mockResolvedValue({ globalHealth: 'OK', priceHealth: 'RUNNING' })
})
afterEach(() => {
  connection.phase = 'disconnected'
  vi.restoreAllMocks()
  vi.useRealTimers()
})

async function settle(): Promise<void> {
  for (let step = 0; step < 10; step += 1) await Promise.resolve()
  await nextTick()
}
async function mountPage(api = new FakeSyncApi()): Promise<VueWrapper> {
  const wrapper = mount(Shell, { props: { api } })
  await settle()
  return wrapper
}
const has = (w: VueWrapper, id: string): boolean => w.find(`[data-test="${id}"]`).exists()
async function click(w: VueWrapper, id: string): Promise<void> {
  await w.find(`[data-test="${id}"]`).trigger('click')
  await settle()
}

describe('System Status owner projection', () => {
  it('showsOnlyGenericLabelsAndNoOwnerControlsWhenTheBackendDeniesOwnerDetails', async () => {
    vi.spyOn(systemApi, 'readOwnerDetails').mockRejectedValue(FORBIDDEN)
    const wrapper = await mountPage()

    expect(wrapper.find('[data-test="global-health"]').text()).toBe('OK')
    expect(wrapper.find('[data-test="price-cache-health"]').text()).toBe('Running')
    expect(has(wrapper, 'sync-trigger-ACCOUNT_SYNC')).toBe(true)
    expect(has(wrapper, 'owner-admin')).toBe(false)
    OWNER_TRIGGERS.forEach((key) => expect(has(wrapper, `sync-trigger-${key}`)).toBe(false))
    expect(has(wrapper, 'owner-status-unavailable')).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('upstream 503')
    expect(systemApi.readOwnerDetails).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('showsDiagnosticsAndTheThreeControlsOnlyAfterOwnerAuthorization', async () => {
    const pending = deferred<OwnerSystemDetails>()
    vi.spyOn(systemApi, 'readOwnerDetails').mockReturnValue(pending.promise)
    const wrapper = await mountPage()
    expect(has(wrapper, 'owner-admin')).toBe(false)

    pending.resolve(DETAILS)
    await settle()
    expect(has(wrapper, 'owner-admin')).toBe(true)
    OWNER_TRIGGERS.forEach((key) => expect(has(wrapper, `sync-trigger-${key}`)).toBe(true))
    expect(wrapper.find('[data-test="global-last-failure"]').text()).toBe('upstream 503')
    wrapper.unmount()
  })

  it('sendsEachOwnerActionWithNoPayloadAndFollowsOnlyItsOwnTask', async () => {
    vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
    const api = new FakeSyncApi()
    const wrapper = await mountPage(api)

    for (const key of OWNER_TRIGGERS) await click(wrapper, `sync-trigger-${key}`)

    expect(api.submissions).toEqual(OWNER_TRIGGERS)
    OWNER_TRIGGERS.forEach((key) => {
      expect(api.statusRequests).toContain(acceptance(key as 'FULL_ITEM_CATALOG').statusUrl)
      expect(wrapper.find(`[data-test="sync-state-${key}"]`).text()).toBe('Completed')
    })
    wrapper.unmount()
  })

  it('doesNotPresentADifferentOrNarrowerTaskAsTheFullOperation', async () => {
    vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
    const api = new FakeSyncApi()
    api.submitHandler = (operation) =>
      Promise.resolve({ ...acceptance(operation), operation: 'GLOBAL_SYNC' })
    const wrapper = await mountPage(api)
    await click(wrapper, 'sync-trigger-FULL_ITEM_CATALOG')
    expect(wrapper.find('[data-test="sync-state-FULL_ITEM_CATALOG"]').text()).toBe('Not accepted')
    expect(api.statusRequests).toEqual([])

    api.submitHandler = (operation) => Promise.resolve({ ...acceptance(operation), coversRequest: false })
    await click(wrapper, 'sync-trigger-FULL_RECIPE_CATALOG')
    expect(wrapper.find('[data-test="sync-state-FULL_RECIPE_CATALOG"]').text()).toBe('Not accepted')
    expect(api.submissionCount('FULL_RECIPE_CATALOG')).toBe(1)
    expect(api.statusRequests).toEqual([])
    wrapper.unmount()
  })

  it('doesNotTreatAnotherOperationsStatusAsCompletionOfTheRequestedOne', async () => {
    vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
    const api = new FakeSyncApi()
    api.statusHandler = (url) => Promise.resolve({ ...statusOf(url, 'SUCCEEDED'), operation: 'GLOBAL_SYNC' })
    const wrapper = await mountPage(api)
    await click(wrapper, 'sync-trigger-FULL_TP_CATALOG')
    expect(wrapper.find('[data-test="sync-state-FULL_TP_CATALOG"]').text()).toBe('Outcome not established')
    wrapper.unmount()
  })

  it('removesOwnerPresentationImmediatelyOnAccountReplacementAndDiscardsLateOwnerAnswers', async () => {
    const first = deferred<OwnerSystemDetails>()
    const read = vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValueOnce(DETAILS)
    const api = new FakeSyncApi()
    api.statusHandler = (url) => Promise.resolve(statusOf(url, 'RUNNING'))
    const wrapper = await mountPage(api)
    await click(wrapper, 'sync-trigger-FULL_ITEM_CATALOG')
    expect(has(wrapper, 'sync-diagnostics-FULL_ITEM_CATALOG')).toBe(true)

    read.mockReturnValueOnce(first.promise)
    connection.generation = 2
    await settle()
    expect(has(wrapper, 'owner-admin')).toBe(false)
    expect(wrapper.text()).not.toContain('task-FULL_ITEM_CATALOG')

    // A late answer for the former account must not restore anything.
    connection.generation = 3
    read.mockReturnValueOnce(Promise.resolve(DETAILS))
    await settle()
    first.resolve(DETAILS)
    await settle()
    // Generation 3 owns its own discovery; the former task presentation is gone.
    expect(has(wrapper, 'sync-diagnostics-FULL_ITEM_CATALOG')).toBe(false)
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 3)
    expect(api.statusRequestCount('FULL_ITEM_CATALOG')).toBeLessThanOrEqual(1)
    wrapper.unmount()
  })

  it('discardsALateOwnerAnswerAfterDisconnect', async () => {
    const late = deferred<OwnerSystemDetails>()
    vi.spyOn(systemApi, 'readOwnerDetails').mockReturnValue(late.promise)
    const wrapper = await mountPage()
    connection.phase = 'disconnected'
    await settle()
    late.resolve(DETAILS)
    await settle()
    expect(has(wrapper, 'owner-admin')).toBe(false)
    wrapper.unmount()
  })

  it('ignoresAnAccountChangedDiscoveryWithoutGrantingOrDenyingOwnership', async () => {
    vi.spyOn(systemApi, 'readOwnerDetails')
      .mockRejectedValue(new ApiRequestError('changed', ACCOUNT_CHANGED_CODE, null))
    const wrapper = await mountPage()
    expect(has(wrapper, 'owner-admin')).toBe(false)
    expect(has(wrapper, 'owner-status-unavailable')).toBe(false)
    wrapper.unmount()
  })

  it('reportsOwnerStatusUnavailableOnAnOutageWithoutOwnerControlsOrAnAuthorizationClaim', async () => {
    vi.spyOn(systemApi, 'readOwnerDetails').mockRejectedValue(new ApiRequestError('down', 'BACKEND_UNREACHABLE', null))
    const wrapper = await mountPage()
    expect(has(wrapper, 'owner-status-unavailable')).toBe(true)
    expect(has(wrapper, 'owner-admin')).toBe(false)
    OWNER_TRIGGERS.forEach((key) => expect(has(wrapper, `sync-trigger-${key}`)).toBe(false))
    wrapper.unmount()
  })

  it('withdrawsOwnerControlsWhenAnActionIsDeniedAndDoesNotRetryIt', async () => {
    vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
    const api = new FakeSyncApi()
    api.submitHandler = () => Promise.reject(FORBIDDEN)
    const wrapper = await mountPage(api)
    await click(wrapper, 'sync-trigger-FULL_ITEM_CATALOG')
    expect(has(wrapper, 'owner-admin')).toBe(false)
    expect(api.submissionCount('FULL_ITEM_CATALOG')).toBe(1)
    wrapper.unmount()
  })

  it('withdrawsOwnerControlsWhenAPeriodicRecheckLosesAuthorization', async () => {
    const read = vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValueOnce(DETAILS)
    const wrapper = await mountPage()
    expect(has(wrapper, 'owner-admin')).toBe(true)
    read.mockRejectedValueOnce(FORBIDDEN)
    await vi.advanceTimersByTimeAsync(5000)
    await settle()
    expect(has(wrapper, 'owner-admin')).toBe(false)
    wrapper.unmount()
  })

  it('doesNotOverlapDiscoveryOrStatusReads', async () => {
    const slow = deferred<OwnerSystemDetails>()
    const read = vi.spyOn(systemApi, 'readOwnerDetails').mockReturnValue(slow.promise)
    const wrapper = await mountPage()
    await vi.advanceTimersByTimeAsync(20_000)
    expect(read).toHaveBeenCalledTimes(1)
    slow.resolve(DETAILS)
    wrapper.unmount()
  })

  it('makesNoStatusOrOwnerRequestWhileDisconnected', async () => {
    connection.phase = 'disconnected'
    const owner = vi.spyOn(systemApi, 'readOwnerDetails')
    const wrapper = await mountPage()
    expect(owner).not.toHaveBeenCalled()
    expect(systemApi.readStatus).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
