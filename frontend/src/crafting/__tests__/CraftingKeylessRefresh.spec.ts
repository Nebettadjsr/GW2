import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { STORAGE_KEY } from '@/api/accountSession'
import { ApiRequestError } from '@/api/http'
import type { SyncApi } from '@/api/syncApi'
import type { CraftingDiscoveryResponse, SyncTaskAccepted, SyncTaskStatus } from '@/api/types'
import CraftingDiscoveryScreen from '../CraftingDiscoveryScreen.vue'
import CraftingProfitScreen from '../CraftingProfitScreen.vue'
import {
  DEFAULT_SETTINGS,
  FakeCraftingApi,
  deferred,
  discoveryRows,
  echoedDiscoveryResponse,
  finalFreshness,
  profitResponse,
  profitableRow,
  provisionalFreshness
} from './fixtures'

/**
 * STORY-WEB-037: cached calculations are keyless reads. The page renders the usable cached result
 * first; only when the backend reports a refresh it could not admit without a key (CREDENTIAL_REQUIRED)
 * and a key is retained does the page call the explicit keyed refresh, follow its task and recalculate.
 */
const needsKey = provisionalFreshness({
  outstandingWork: [],
  failures: [{
    kind: 'ACCOUNT', taskId: null, taskStatusUrl: null, code: 'CREDENTIAL_REQUIRED',
    message: 'Account sources need a refresh; no GW2 key was supplied', retryable: true
  }]
})
const cached = { ...profitableRow, totalProfitCopper: 1_234 }
const refreshed = { ...profitableRow, totalProfitCopper: 4_321 }
const accepted: SyncTaskAccepted = { taskId: 'k1', operation: 'ACCOUNT_SYNC', statusUrl: '/api/sync/tasks/k1', coversRequest: true }

const instantClock = {
  now: () => Date.now(),
  sleep: () => Promise.resolve(),
  alarm: () => ({ expired: new Promise<void>(() => undefined), cancel: () => undefined })
}

function task(state: string): SyncTaskStatus {
  return { taskId: 'k1', operation: 'ACCOUNT_SYNC', state, submittedAt: 'x', startedAt: null, finishedAt: null, failure: null }
}

interface Sync extends SyncApi { started: number; reads: string[] }

function syncApi(start: () => Promise<SyncTaskAccepted>, state = 'SUCCEEDED'): Sync {
  const sync: Sync = {
    started: 0,
    reads: [],
    startAccountSync: () => { sync.started += 1; return start() },
    startProfitDataRefresh: () => { sync.started += 1; return start() },
    startGlobalSync: () => Promise.reject(new Error('unexpected')),
    startPriceRefresh: () => Promise.reject(new Error('unexpected')),
    readTaskStatus: (url) => { sync.reads.push(url); return Promise.resolve(task(state)) }
  }
  return sync
}

const profits = (w: ReturnType<typeof mount>) => w.findAll('[data-test="total-profit"]').map((e) => e.text())

beforeEach(() => window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A'))
afterEach(() => window.sessionStorage.clear())

describe('Profit: keyless read, then a keyed refresh behind the cached result', () => {
  function profitApi(): FakeCraftingApi {
    const api = new FakeCraftingApi()
    api.profitHandler = (_r, i) => Promise.resolve(i === 0
      ? profitResponse([cached], 'ALL', DEFAULT_SETTINGS, needsKey)
      : profitResponse([refreshed], 'ALL', DEFAULT_SETTINGS, finalFreshness()))
    return api
  }

  it('renders the cached rows before the refresh proof/admission finishes, then recalculates', async () => {
    const admission = deferred<SyncTaskAccepted>()
    const sync = syncApi(() => admission.promise)
    const api = profitApi()
    const wrapper = mount(CraftingProfitScreen, { props: { api, refreshApi: sync, refreshClock: instantClock } })
    await flushPromises()

    expect(sync.started).toBe(1)
    expect(profits(wrapper)).toEqual(['+12s 34c'])
    expect(api.profitRequests).toHaveLength(1)

    admission.resolve(accepted)
    await flushPromises()

    expect(sync.reads).toEqual(['/api/sync/tasks/k1'])
    expect(api.profitRequests).toHaveLength(2)
    expect(profits(wrapper)).toEqual(['+43s 21c'])
    expect(sync.started).toBe(1)
  })

  it('keeps the cached view and the key when admission is transiently unavailable', async () => {
    const sync = syncApi(() => Promise.reject(new ApiRequestError('later', 'PROOF_UNAVAILABLE', 503)))
    const api = profitApi()
    const wrapper = mount(CraftingProfitScreen, { props: { api, refreshApi: sync, refreshClock: instantClock } })
    await flushPromises()

    expect(profits(wrapper)).toEqual(['+12s 34c'])
    expect(wrapper.text()).toContain('could not be started right now')
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('KEY-A')
    expect(api.profitRequests).toHaveLength(1)

    // Retry admits again and recovers.
    sync.startProfitDataRefresh = () => Promise.resolve(accepted)
    await wrapper.find('[data-test="refresh-retry"]').trigger('click')
    await flushPromises()
    expect(profits(wrapper)).toEqual(['+43s 21c'])
  })

  it('admits nothing and says so when no key is retained', async () => {
    window.sessionStorage.clear()
    const sync = syncApi(() => Promise.resolve(accepted))
    const wrapper = mount(CraftingProfitScreen, { props: { api: profitApi(), refreshApi: sync, refreshClock: instantClock } })
    await flushPromises()

    expect(sync.started).toBe(0)
    expect(profits(wrapper)).toEqual(['+12s 34c'])
    expect(wrapper.text()).toContain('no GW2 API key is connected')
  })
})

describe('Discovery: keyless read, then a keyed refresh behind the cached result', () => {
  function discoveryApi(): FakeCraftingApi {
    const api = new FakeCraftingApi()
    api.discoveryHandler = (request, i): Promise<CraftingDiscoveryResponse> => {
      const rows = [i === 0 ? cached : refreshed]
      const response = echoedDiscoveryResponse(request, rows.length > 0 ? rows : discoveryRows)
      return Promise.resolve(i === 0
        ? { ...response, freshness: { status: 'STALE', refresh: 'CREDENTIAL_REQUIRED' } }
        : response)
    }
    return api
  }

  it('shows cached rows first, admits the keyed sync once and recalculates', async () => {
    const admission = deferred<SyncTaskAccepted>()
    const sync = syncApi(() => admission.promise)
    const api = discoveryApi()
    const wrapper = mount(CraftingDiscoveryScreen, { props: { api, refreshApi: sync } })
    await flushPromises()

    expect(sync.started).toBe(1)
    expect(wrapper.findAll('[data-test="discovery-row"]')).toHaveLength(1)
    expect(api.discoveryRequests).toHaveLength(1)

    admission.resolve(accepted)
    await flushPromises()

    expect(api.discoveryRequests).toHaveLength(2)
    expect(sync.started).toBe(1)
  })

  it('keeps the cached rows and key, with honest guidance, when admission is unavailable', async () => {
    const sync = syncApi(() => Promise.reject(new ApiRequestError('later', 'PROOF_UNAVAILABLE', 503)))
    const wrapper = mount(CraftingDiscoveryScreen, { props: { api: discoveryApi(), refreshApi: sync } })
    await flushPromises()

    expect(wrapper.findAll('[data-test="discovery-row"]')).toHaveLength(1)
    expect(wrapper.find('[data-test="discovery-refresh-notice"]').text()).toContain('your key are kept')
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('KEY-A')
  })
})
