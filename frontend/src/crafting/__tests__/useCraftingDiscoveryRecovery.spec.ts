import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, type EffectScope } from 'vue'
import { ApiRequestError } from '@/api/http'
import type { CraftingDiscoveryResponse, SyncTaskAccepted, SyncTaskStatus } from '@/api/types'
import { DISCOVERY_REFRESH_MAX_POLLS, useCraftingDiscovery, type DiscoveryRefreshPorts } from '../useCraftingDiscovery'
import { FakeCraftingApi, deferred, echoedDiscoveryResponse, selectorOptions } from './fixtures'

/**
 * Discovery selection, keyed-refresh follow-up and stale-answer branches, with controlled promises
 * and sleeps. The messages asserted are the ones the person reads under the cached rows.
 */
let scope: EffectScope
let api: FakeCraftingApi
let ports: DiscoveryRefreshPorts
let parked: Array<() => void>

const needsKey = (request: Parameters<FakeCraftingApi['calculateDiscovery']>[0]): CraftingDiscoveryResponse =>
  ({ ...echoedDiscoveryResponse(request), freshness: { refresh: 'CREDENTIAL_REQUIRED' } }) as CraftingDiscoveryResponse
const accepted = (coversRequest?: boolean): SyncTaskAccepted => ({
  taskId: 't', operation: 'ACCOUNT_SYNC', statusUrl: '/api/sync/tasks/t', ...(coversRequest === undefined ? {} : { coversRequest })
})
const status = (state: string, failure: { error: string; message: string } | null = null): SyncTaskStatus =>
  ({ taskId: 't', operation: 'ACCOUNT_SYNC', state, submittedAt: 'x', startedAt: 'x', finishedAt: null, failure }) as SyncTaskStatus

beforeEach(() => {
  scope = effectScope()
  api = new FakeCraftingApi()
  parked = []
  ports = {
    hasKey: () => true,
    startAccountRefresh: async () => accepted(true),
    readTaskStatus: async () => status('SUCCEEDED'),
    sleep: () => new Promise<void>((resolve) => parked.push(resolve))
  }
})
afterEach(() => { scope.stop(); vi.useRealTimers() })

const open = (own: DiscoveryRefreshPorts | null = ports) => scope.run(() => useCraftingDiscovery(api, own))!

describe('selector and selection', () => {
  it('sends nothing and shows no result when the selector offers no character', async () => {
    api.selectorHandler = async () => ({ ...selectorOptions, characterOptions: [], characterOptionCount: 0 })
    const state = open()
    await state.open()
    expect(api.discoveryRequests).toHaveLength(0)
    expect(state.canCalculate.value).toBe(false)
    expect(state.selectedScopeId.value).toBeNull()
    expect(state.hasResult.value).toBe(false)
    expect(state.isLoading.value).toBe(false)
  })

  it('drops the selector answer and its failure once the owner is disposed', async () => {
    const options = deferred<typeof selectorOptions>()
    api.selectorHandler = () => options.promise
    const state = open()
    const pending = state.open()
    scope.stop()
    options.resolve(selectorOptions)
    await pending
    expect(state.scopeOptions.value).toEqual([])

    scope = effectScope()
    const failing = deferred<typeof selectorOptions>()
    api.selectorHandler = () => failing.promise
    const second = open()
    const pendingSecond = second.open()
    scope.stop()
    failing.reject(new Error('late'))
    await pendingSecond
    expect(second.selectorError.value).toBeNull()
  })

  it('shows a selector failure and sends no calculation', async () => {
    api.selectorHandler = () => Promise.reject(new ApiRequestError('down', 'BACKEND_UNREACHABLE', null))
    const state = open()
    await state.open()
    expect(state.selectorError.value).toBe('BACKEND_UNREACHABLE: down')
    expect(api.discoveryRequests).toHaveLength(0)
  })

  it('starts no request after disposal', async () => {
    const state = open()
    await state.open()
    scope.stop()
    await state.reload()
    expect(api.discoveryRequests).toHaveLength(1)
  })

  it('discards an older selection answer and failure that arrive after a newer one', async () => {
    const first = deferred<CraftingDiscoveryResponse>()
    const second = deferred<CraftingDiscoveryResponse>()
    const answers = [first, second]
    api.discoveryHandler = (_, index) => answers[index]!.promise
    const state = open()
    const older = state.open()
    await flushPromises()
    const newer = state.reload()
    await flushPromises()
    second.resolve(echoedDiscoveryResponse({ scope: { discipline: 'Weaponsmith', characterName: 'x', rating: 1 } }))
    await newer
    first.reject(new Error('older failure'))
    await older
    expect(state.requestError.value).toBeNull()
    expect(state.hasResult.value).toBe(true)
  })
})

describe('the keyed refresh behind cached rows', () => {
  beforeEach(() => {
    api.discoveryHandler = (request, index) => Promise.resolve(index === 0 ? needsKey(request) : echoedDiscoveryResponse(request))
  })

  it('shows the cached rows and says a key is needed when the page has no refresh ports', async () => {
    const state = open(null)
    await state.open()
    expect(state.hasResult.value).toBe(true)
    expect(state.refreshNotice.value).toContain('no GW2 API key is connected')
  })

  it('reports a failed refresh task by its backend code, or as unknown', async () => {
    ports.readTaskStatus = async () => status('FAILED', { error: 'SYNC_FAILED', message: 'x' })
    const state = open()
    await state.open()
    expect(state.refreshNotice.value).toContain('(SYNC_FAILED)')

    api.discoveryRequests.length = 0
    scope = effectScope()
    ports.readTaskStatus = async () => status('FAILED')
    const other = open()
    await other.open()
    expect(other.refreshNotice.value).toContain('(unknown)')
  })

  it('says the refresh is still needed when the recalculation reports it again', async () => {
    api.discoveryHandler = (request) => Promise.resolve(needsKey(request))
    const state = open()
    await state.open()
    expect(state.refreshNotice.value).toBe('Account data still needs a refresh. Reload to try again.')
    expect(api.discoveryRequests).toHaveLength(2)
  })

  it('stops after the bounded number of status reads', async () => {
    let reads = 0
    ports.readTaskStatus = async () => { reads += 1; return status('RUNNING') }
    ports.sleep = async () => undefined
    const state = open()
    await state.open()
    expect(reads).toBe(DISCOVERY_REFRESH_MAX_POLLS)
    expect(state.refreshNotice.value).toContain('did not finish in time')
  })

  it('reports a refresh that could not be completed, with the cause', async () => {
    ports.startAccountRefresh = () => Promise.reject(new Error('offline'))
    const state = open()
    await state.open()
    expect(state.refreshNotice.value).toContain('could not be completed right now')
    expect(state.refreshNotice.value).toContain('(offline)')
  })

  it('does not treat a refresh that joined a narrower task as started', async () => {
    ports.startAccountRefresh = async () => accepted(false)
    const state = open()
    await state.open()
    expect(state.refreshNotice.value).toContain('waiting behind another task')
    expect(api.discoveryRequests).toHaveLength(1)
  })

  it('keeps the default sleep on browser timers', async () => {
    vi.useFakeTimers()
    let reads = 0
    delete ports.sleep
    ports.readTaskStatus = async () => (reads++ === 0 ? status('RUNNING') : status('SUCCEEDED'))
    const state = open()
    const pending = state.open()
    await vi.advanceTimersByTimeAsync(999)
    expect(reads).toBe(1)
    await vi.advanceTimersByTimeAsync(1)
    await pending
    expect(reads).toBe(2)
    expect(state.refreshNotice.value).toBeNull()
  })

  it('ignores each step of a refresh whose selection was replaced meanwhile', async () => {
    for (const stage of ['start', 'status', 'recalculate', 'sleep'] as const) {
      scope = effectScope()
      api = new FakeCraftingApi()
      parked = []
      const gate = deferred<void>()
      const local: DiscoveryRefreshPorts = {
        hasKey: () => true,
        startAccountRefresh: async () => { if (stage === 'start') await gate.promise; return accepted(true) },
        readTaskStatus: async () => {
          if (stage === 'status') await gate.promise
          return stage === 'sleep' ? status('RUNNING') : status('SUCCEEDED')
        },
        sleep: async () => { if (stage === 'sleep') await gate.promise }
      }
      api.discoveryHandler = async (request, index) => {
        if (stage === 'recalculate' && index === 1) await gate.promise
        return index === 0 ? needsKey(request) : echoedDiscoveryResponse(request)
      }
      const state = scope.run(() => useCraftingDiscovery(api, local))!
      const pending = state.open()
      await flushPromises()
      const requestsBefore = api.discoveryRequests.length
      api.discoveryHandler = async (request) => echoedDiscoveryResponse(request)
      await state.reload()
      const requestsAfterReload = api.discoveryRequests.length
      gate.resolve()
      await pending
      await flushPromises()
      expect(state.refreshNotice.value, stage).toBeNull()
      expect(api.discoveryRequests.length, stage).toBe(requestsAfterReload)
      expect(requestsAfterReload, stage).toBeGreaterThanOrEqual(requestsBefore)
      scope.stop()
    }
  })
})
