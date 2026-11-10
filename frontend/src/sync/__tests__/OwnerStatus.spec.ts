import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref, type PropType } from 'vue'
import { connection } from '@/api/accountSession'
import { ACCOUNT_CHANGED_CODE, ApiRequestError } from '@/api/http'
import { systemApi, type DatasetSummary, type OwnerSystemDetails } from '@/api/systemApi'
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

  it('discardsAnOlderInFlightDetailsAnswerAfterAnOwnerActionIsDenied', async () => {
    const older = deferred<OwnerSystemDetails>()
    const read = vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValueOnce(DETAILS)
    const api = new FakeSyncApi()
    api.submitHandler = () => Promise.reject(FORBIDDEN)
    const wrapper = await mountPage(api)
    read.mockReturnValueOnce(older.promise)
    await vi.advanceTimersByTimeAsync(5000)
    expect(read).toHaveBeenCalledTimes(2)

    await click(wrapper, 'sync-trigger-FULL_ITEM_CATALOG')
    expect(has(wrapper, 'owner-admin')).toBe(false)
    older.resolve(DETAILS)
    await settle()
    expect(has(wrapper, 'owner-admin')).toBe(false)
    OWNER_TRIGGERS.forEach((key) => expect(has(wrapper, `sync-trigger-${key}`)).toBe(false))
    wrapper.unmount()
  })

  it('keepsOwnerControlsWhenOnlyTheOrdinaryAccountRefreshIsDenied', async () => {
    vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
    const api = new FakeSyncApi()
    api.submitHandler = (operation) =>
      operation === 'ACCOUNT_SYNC' ? Promise.reject(FORBIDDEN) : Promise.resolve(acceptance(operation))
    const wrapper = await mountPage(api)
    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    expect(has(wrapper, 'sync-submission-error-ACCOUNT_SYNC')).toBe(true)
    expect(has(wrapper, 'owner-admin')).toBe(true)
    OWNER_TRIGGERS.forEach((key) => expect(has(wrapper, `sync-trigger-${key}`)).toBe(true))
    wrapper.unmount()
  })

  it('withdrawsOwnerPresentationWhenAnOwnerTaskReadIsDeniedAndDiscardsAnOlderDetailsAnswer', async () => {
    const older = deferred<OwnerSystemDetails>()
    const read = vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValueOnce(DETAILS)
    const api = new FakeSyncApi()
    api.statusHandler = () => Promise.reject(FORBIDDEN)
    const wrapper = await mountPage(api)
    read.mockReturnValueOnce(older.promise)
    await vi.advanceTimersByTimeAsync(5000)

    await click(wrapper, 'sync-trigger-FULL_RECIPE_CATALOG')
    expect(has(wrapper, 'owner-admin')).toBe(false)
    expect(has(wrapper, 'sync-diagnostics-FULL_RECIPE_CATALOG')).toBe(false)
    older.resolve(DETAILS)
    await settle()
    expect(has(wrapper, 'owner-admin')).toBe(false)
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 3)
    expect(api.statusRequestCount('FULL_RECIPE_CATALOG')).toBe(1)
    wrapper.unmount()
  })

  it('doesNotLetAnUnmountedScreensLateFailureResetTasksOfANewlyMountedScreen', async () => {
    const oldRead = deferred<OwnerSystemDetails>()
    const read = vi.spyOn(systemApi, 'readOwnerDetails').mockReturnValueOnce(oldRead.promise)
    const api = new FakeSyncApi()
    api.statusHandler = (url) => Promise.resolve(statusOf(url, 'RUNNING'))
    const screenKey = ref(1)
    const KeyedShell = defineComponent({
      setup() {
        provideSyncOperations(api)
        return () => h(SyncScreen, { key: screenKey.value })
      }
    })
    const wrapper = mount(KeyedShell)
    await settle()

    read.mockResolvedValue(DETAILS)
    screenKey.value = 2
    await settle()
    await click(wrapper, 'sync-trigger-FULL_ITEM_CATALOG')
    expect(has(wrapper, 'sync-diagnostics-FULL_ITEM_CATALOG')).toBe(true)

    oldRead.reject(new ApiRequestError('down', 'BACKEND_UNREACHABLE', null))
    await settle()
    expect(has(wrapper, 'owner-admin')).toBe(true)
    expect(has(wrapper, 'sync-diagnostics-FULL_ITEM_CATALOG')).toBe(true)
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
  describe('navigation away and back (shared tracker remount)', () => {
    const show = ref(true)
    const NavShell = defineComponent({
      props: { api: { type: Object as PropType<FakeSyncApi>, required: true } },
      setup(props) {
        provideSyncOperations(props.api)
        return () => (show.value ? h(SyncScreen) : h('div'))
      }
    })
    const RUNNING = 'FULL_ITEM_CATALOG'
    const trigger = `sync-trigger-${RUNNING}`
    async function navigate(visible: boolean): Promise<void> {
      show.value = visible
      await settle()
    }
    async function startRunning(): Promise<{ wrapper: VueWrapper; api: FakeSyncApi }> {
      const api = new FakeSyncApi()
      let terminal = false
      api.statusHandler = (url) => Promise.resolve(statusOf(url, terminal ? 'SUCCEEDED' : 'RUNNING'))
      ;(api as unknown as { finish: () => void }).finish = () => { terminal = true }
      const wrapper = mount(NavShell, { props: { api } })
      show.value = true
      await settle()
      await click(wrapper, trigger)
      return { wrapper, api }
    }

    it('keepsTheSameTaskPollingAndSingleSubmissionAcrossRemountOfTheSameLease', async () => {
      vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
      const { wrapper, api } = await startRunning()
      expect(api.submissionCount(RUNNING)).toBe(1)

      await navigate(false)
      await navigate(true)
      // Privileged presentation is re-discovered, and the active task is still the same one.
      expect(has(wrapper, 'owner-admin')).toBe(true)
      expect(api.submissionCount(RUNNING)).toBe(1)
      expect(wrapper.find(`[data-test="${trigger}"]`).attributes('disabled')).toBeDefined()

      ;(api as unknown as { finish: () => void }).finish()
      await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS)
      await settle()
      expect(wrapper.find(`[data-test="sync-state-${RUNNING}"]`).text()).toBe('Completed')
      expect(api.submissionCount(RUNNING)).toBe(1)
      expect(new Set(api.statusRequests).size).toBe(1)
      wrapper.unmount()
    })

    it('doesNotShowOwnerControlsOnRemountUntilDiscoverySucceeds', async () => {
      const read = vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
      const { wrapper, api } = await startRunning()
      await navigate(false)
      const pending = deferred<OwnerSystemDetails>()
      read.mockReturnValue(pending.promise)
      await navigate(true)
      expect(has(wrapper, 'owner-admin')).toBe(false)
      pending.resolve(DETAILS)
      await settle()
      expect(has(wrapper, 'owner-admin')).toBe(true)
      expect(api.submissionCount(RUNNING)).toBe(1)
      wrapper.unmount()
    })

    it('keepsTrackingThroughATemporaryDiagnosticsOutageOnRemount', async () => {
      const read = vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
      const { wrapper, api } = await startRunning()
      await navigate(false)
      read.mockRejectedValue(new ApiRequestError('down', 'UNREACHABLE', 0))
      await navigate(true)
      expect(has(wrapper, 'owner-admin')).toBe(false)
      expect(has(wrapper, 'owner-status-unavailable')).toBe(true)
      read.mockResolvedValue(DETAILS)
      await vi.advanceTimersByTimeAsync(5000)
      await settle()
      expect(has(wrapper, 'owner-admin')).toBe(true)
      expect(wrapper.find(`[data-test="${trigger}"]`).attributes('disabled')).toBeDefined()
      expect(api.submissionCount(RUNNING)).toBe(1)
      wrapper.unmount()
    })

    it('clearsTrackingWhenTheAccountGenerationChangesWhileAway', async () => {
      vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
      const { wrapper, api } = await startRunning()
      await navigate(false)
      connection.generation = 2
      await settle()
      await navigate(true)
      expect(wrapper.find(`[data-test="${trigger}"]`).attributes('disabled')).toBeUndefined()
      expect(wrapper.find(`[data-test="sync-state-${RUNNING}"]`).text()).toBe('Not started')
      expect(api.submissionCount(RUNNING)).toBe(1)
      wrapper.unmount()
    })

    it('clearsTrackingWhenTheLeaseChangesWhileAway', async () => {
      vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
      const { wrapper } = await startRunning()
      await navigate(false)
      connection.leaseVersion += 1
      await settle()
      await navigate(true)
      expect(wrapper.find(`[data-test="sync-state-${RUNNING}"]`).text()).toBe('Not started')
      wrapper.unmount()
    })

    it('clearsTrackingWhenOwnerDiscoveryIsDeniedOnRemount', async () => {
      const read = vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
      const { wrapper } = await startRunning()
      await navigate(false)
      read.mockRejectedValue(FORBIDDEN)
      await navigate(true)
      expect(has(wrapper, 'owner-admin')).toBe(false)
      read.mockResolvedValue(DETAILS)
      connection.leaseVersion += 1
      await settle()
      expect(has(wrapper, 'owner-admin')).toBe(true)
      expect(wrapper.find(`[data-test="sync-state-${RUNNING}"]`).text()).toBe('Not started')
      wrapper.unmount()
    })
  })

  describe('shared dataset groups and account counts', () => {
    const ds = (over: Partial<DatasetSummary>): DatasetSummary => ({
      state: 'AVAILABLE', storedRows: 5, timestampedRows: 3, unknownTimestampRows: 2,
      oldestFetchedAt: '2026-11-01T10:00:00Z', newestFetchedAt: '2026-11-05T10:00:00Z', ...over
    })
    const coverage = (w: VueWrapper, key: string): string | undefined =>
      w.find(`[data-test="dataset-${key}"]`).attributes('data-coverage')

    it('rendersEveryCoverageStateBesideItsActionWithoutImplyingCompleteness', async () => {
      vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue({
        ...DETAILS,
        items: ds({}),
        recipes: ds({ storedRows: 0, timestampedRows: 0, unknownTimestampRows: 0, oldestFetchedAt: null, newestFetchedAt: null }),
        tradingPostPrices: ds({ storedRows: 4, timestampedRows: 0, unknownTimestampRows: 4, oldestFetchedAt: null, newestFetchedAt: null })
      })
      const wrapper = await mountPage()
      expect(coverage(wrapper, 'FULL_ITEM_CATALOG')).toBe('known')
      expect(wrapper.find('[data-test="dataset-stored-FULL_ITEM_CATALOG"]').text()).toBe('5')
      expect(wrapper.find('[data-test="dataset-unknown-FULL_ITEM_CATALOG"]').text()).toBe('2')
      expect(wrapper.find('[data-test="dataset-oldest-FULL_ITEM_CATALOG"]').text()).not.toBe('—')
      expect(coverage(wrapper, 'FULL_RECIPE_CATALOG')).toBe('empty')
      expect(wrapper.find('[data-test="dataset-empty-FULL_RECIPE_CATALOG"]').text()).toBe('No stored recipes.')
      expect(coverage(wrapper, 'FULL_TP_CATALOG')).toBe('untimed')
      expect(wrapper.find('[data-test="dataset-untimed-FULL_TP_CATALOG"]').text()).toContain('Unknown')
      expect(has(wrapper, 'dataset-oldest-FULL_TP_CATALOG')).toBe(false)
      // The action sits in the same card as its coverage.
      expect(wrapper.find('[data-test="owner-control-FULL_ITEM_CATALOG"] [data-test="dataset-FULL_ITEM_CATALOG"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="owner-control-FULL_ITEM_CATALOG"] [data-test="sync-trigger-FULL_ITEM_CATALOG"]').exists()).toBe(true)
      wrapper.unmount()
    })

    it('showsUnavailableOrUnreportedSummariesNeverAsZero', async () => {
      vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue({
        ...DETAILS,
        items: ds({ state: 'UNAVAILABLE', storedRows: null, timestampedRows: null, unknownTimestampRows: null, oldestFetchedAt: null, newestFetchedAt: null }),
        accounts: { state: 'UNAVAILABLE', totalAccounts: null, activeLast60Minutes: null }
      })
      const wrapper = await mountPage()
      expect(coverage(wrapper, 'FULL_ITEM_CATALOG')).toBe('unavailable')
      expect(wrapper.find('[data-test="dataset-unavailable-FULL_ITEM_CATALOG"]').text()).toContain('not zero')
      expect(coverage(wrapper, 'FULL_RECIPE_CATALOG')).toBe('unknown')
      expect(has(wrapper, 'owner-accounts-total')).toBe(false)
      expect(wrapper.find('[data-test="owner-accounts-unavailable"]').text()).toContain('not zero')
      wrapper.unmount()
    })

    it('showsOwnerAccountCountsInPlaceOfTheGenericHealthBoxesAndKeepsActionsWorking', async () => {
      vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue({
        ...DETAILS, accounts: { state: 'AVAILABLE', totalAccounts: 7, activeLast60Minutes: 2 }
      })
      const api = new FakeSyncApi()
      const wrapper = await mountPage(api)
      expect(wrapper.find('[data-test="owner-accounts-total"]').text()).toBe('7')
      expect(wrapper.find('[data-test="owner-accounts-active"]').text()).toBe('2')
      expect(has(wrapper, 'global-health')).toBe(false)
      expect(has(wrapper, 'price-cache-health')).toBe(false)
      expect(has(wrapper, 'status-data-explainer')).toBe(true)
      await click(wrapper, 'sync-trigger-FULL_ITEM_CATALOG')
      expect(api.submissions).toEqual(['FULL_ITEM_CATALOG'])
      wrapper.unmount()
    })

    it('ordinaryUsersKeepConciseSharedHealthAndTheExplanationButNoOwnerCounts', async () => {
      vi.spyOn(systemApi, 'readOwnerDetails').mockRejectedValue(FORBIDDEN)
      const wrapper = await mountPage()
      expect(has(wrapper, 'status-data-explainer')).toBe(true)
      expect(has(wrapper, 'global-health')).toBe(true)
      expect(has(wrapper, 'owner-accounts')).toBe(false)
      expect(wrapper.find('[data-test="dataset-FULL_ITEM_CATALOG"]').exists()).toBe(false)
      wrapper.unmount()
    })
  })
})
