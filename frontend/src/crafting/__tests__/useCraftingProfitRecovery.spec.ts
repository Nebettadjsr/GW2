import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, type EffectScope } from 'vue'
import { ApiRequestError } from '@/api/http'
import type { SyncTaskAccepted, SyncTaskStatus } from '@/api/types'
import {
  RECOVERY_BUDGET_MS,
  RECOVERY_KEY_NEEDED,
  RECOVERY_NOTHING_ADMITTED,
  RECOVERY_POLL_INTERVAL_MS,
  RECOVERY_REFRESH_NOT_COVERED,
  RECOVERY_REFRESH_UNAVAILABLE,
  RECOVERY_TASK_UNKNOWN,
  RECOVERY_TIMED_OUT,
  useCraftingProfit,
  type ProfitRefreshPorts,
  type RecoveryAlarm
} from '../useCraftingProfit'
import {
  FakeCraftingApi,
  accountWork,
  deferred,
  finalFreshness,
  profitResponse,
  profitableRow,
  provisionalFreshness
} from './fixtures'

/**
 * Bounded missing-source recovery of Crafting Profit, driven straight through the composable with a
 * controlled clock, controlled sleeps and controlled alarms: key-needed and refused refreshes, task
 * failure, the five-minute budget, stale answers after a new selection and disposal. Each expected
 * message is the one the person would read; nothing waits in real time.
 */
const credentialFailure = {
  kind: 'ACCOUNT', taskId: null, taskStatusUrl: null, code: 'CREDENTIAL_REQUIRED', message: 'A key is needed', retryable: true
}
const needsKey = () => provisionalFreshness({ outstandingWork: [], failures: [credentialFailure] })
const provisional = () => profitResponse([profitableRow], 'ALL', undefined, provisionalFreshness())
const final = () => profitResponse([profitableRow], 'ALL', undefined, finalFreshness())

let scope: EffectScope
let api: FakeCraftingApi
let clock: number
let parked: Array<() => void>
let alarms: Array<{ fire: () => void }>
let ports: ProfitRefreshPorts

const succeeded = (url: string): SyncTaskStatus => ({
  taskId: url, operation: 'ACCOUNT_SYNC', state: 'SUCCEEDED', submittedAt: 'x', startedAt: 'x', finishedAt: 'x', failure: null
})

beforeEach(() => {
  scope = effectScope()
  api = new FakeCraftingApi()
  clock = 1_700_000_000_000
  parked = []
  alarms = []
  ports = {
    readTaskStatus: async (url) => succeeded(url),
    now: () => clock,
    sleep: () => new Promise<void>((resolve) => parked.push(resolve)),
    alarm(): RecoveryAlarm {
      let fire: () => void = () => undefined
      const expired = new Promise<void>((resolve) => { fire = resolve })
      alarms.push({ fire })
      return { expired, cancel: () => undefined }
    }
  }
})

afterEach(() => {
  scope.stop()
  vi.useRealTimers()
})

function open() {
  return scope.run(() => useCraftingProfit(api, ports))!
}

/** Lets every parked wait finish, `ms` of the controlled clock later. */
async function release(ms = RECOVERY_POLL_INTERVAL_MS): Promise<void> {
  clock += ms
  const waiting = parked
  parked = []
  waiting.forEach((resolve) => resolve())
  await flushPromises()
}

async function opened(first = provisional) {
  api.profitHandler = () => Promise.resolve(first())
  const state = open()
  await state.open()
  await flushPromises()
  return state
}

describe('a refresh that needs the key', () => {
  beforeEach(() => {
    api.profitHandler = () => Promise.resolve(profitResponse([profitableRow], 'ALL', undefined, needsKey()))
  })

  it('says a key is needed when the page cannot admit account work at all', async () => {
    const state = open()
    await state.open()
    await flushPromises()
    expect(state.recoveryFailure.value).toBe(RECOVERY_KEY_NEEDED)
    expect(state.isRecovering.value).toBe(false)
    expect(state.rows.value).toHaveLength(1)
  })

  it('says a key is needed when none is retained, without starting a refresh', async () => {
    const start = vi.fn()
    ports.startAccountRefresh = start
    ports.hasKey = () => false
    const state = open()
    await state.open()
    await flushPromises()
    expect(state.recoveryFailure.value).toBe(RECOVERY_KEY_NEEDED)
    expect(start).not.toHaveBeenCalled()
  })

  it('reports a refresh that could not be started, with the cause', async () => {
    ports.hasKey = () => true
    ports.startAccountRefresh = () => Promise.reject(new ApiRequestError('later', 'PROOF_UNAVAILABLE', 503))
    const state = open()
    await state.open()
    await flushPromises()
    expect(state.recoveryFailure.value).toBe(`${RECOVERY_REFRESH_UNAVAILABLE} (PROOF_UNAVAILABLE: later)`)
  })

  it('words a non-Error refusal as text', async () => {
    ports.hasKey = () => true
    ports.startAccountRefresh = () => Promise.reject('plain refusal')
    const state = open()
    await state.open()
    await flushPromises()
    expect(state.recoveryFailure.value).toBe(`${RECOVERY_REFRESH_UNAVAILABLE} (plain refusal)`)
  })

  it('does not treat a refresh that joined a narrower task as the requested refresh', async () => {
    ports.hasKey = () => true
    ports.startAccountRefresh = async (): Promise<SyncTaskAccepted> => ({
      taskId: 'narrow', operation: 'ACCOUNT_SYNC', statusUrl: '/api/sync/tasks/narrow', coversRequest: false
    })
    const state = open()
    await state.open()
    await flushPromises()
    expect(state.recoveryFailure.value).toBe(RECOVERY_REFRESH_NOT_COVERED)
    expect(state.isRecovering.value).toBe(false)
  })

  it('shows nothing from a refusal that arrives after a new selection', async () => {
    const answer = deferred<SyncTaskAccepted>()
    ports.hasKey = () => true
    ports.startAccountRefresh = () => answer.promise
    const state = open()
    await state.open()
    await flushPromises()
    api.profitHandler = () => Promise.resolve(final())
    await state.selectScope('ALL')
    answer.reject(new Error('late'))
    await flushPromises()
    expect(state.recoveryFailure.value).toBeNull()
    expect(state.isProvisional.value).toBe(false)
  })

  it('admits the keyed refresh only once per cycle and reports the backend failure when it persists', async () => {
    const start = vi.fn(async (): Promise<SyncTaskAccepted> => ({
      taskId: 't', operation: 'ACCOUNT_SYNC', statusUrl: '/api/sync/tasks/t', coversRequest: true
    }))
    ports.hasKey = () => true
    ports.startAccountRefresh = start
    const state = open()
    await state.open()
    await flushPromises()
    await release()
    expect(start).toHaveBeenCalledTimes(1)
    expect(state.recoveryFailure.value).toBe('CREDENTIAL_REQUIRED: A key is needed')
    expect(api.profitRequests).toHaveLength(2)
  })
})

describe('outstanding work without a task to follow', () => {
  it('reports the backend failure it supplied', async () => {
    const failure = { ...credentialFailure, code: 'SYNC_FAILED', message: 'broke' }
    const state = await opened(() =>
      profitResponse([profitableRow], 'ALL', undefined, provisionalFreshness({ outstandingWork: [], failures: [failure] })))
    expect(state.recoveryFailure.value).toBe('SYNC_FAILED: broke')
  })

  it('reports a task the backend no longer knows', async () => {
    const state = await opened(() =>
      profitResponse([profitableRow], 'ALL', undefined,
        provisionalFreshness({ outstandingWork: [accountWork('t', { taskState: 'UNKNOWN' })] })))
    expect(state.recoveryFailure.value).toBe(RECOVERY_TASK_UNKNOWN)
  })

  it('reports that nothing was admitted when no work is advertised', async () => {
    const state = await opened(() =>
      profitResponse([profitableRow], 'ALL', undefined, provisionalFreshness({ outstandingWork: [] })))
    expect(state.recoveryFailure.value).toBe(RECOVERY_NOTHING_ADMITTED)
  })

  it('asks the calculation again, paced by one wait, when another entry point holds the work', async () => {
    const held = () => profitResponse([profitableRow], 'ALL', undefined,
      provisionalFreshness({ outstandingWork: [accountWork('t', { taskState: 'NONE', taskStatusUrl: null })] }))
    api.profitHandler = (_, index) => Promise.resolve(index < 2 ? held() : final())
    const state = open()
    await state.open()
    await flushPromises()
    expect(api.profitRequests).toHaveLength(2)
    expect(state.isRecovering.value).toBe(true)
    await release()
    expect(api.profitRequests).toHaveLength(3)
    expect(state.isProvisional.value).toBe(false)
    expect(state.isRecovering.value).toBe(false)
  })
})

describe('following the advertised task', () => {
  it('reports a failed task by its backend code and message', async () => {
    ports.readTaskStatus = async (url) => ({
      ...succeeded(url), state: 'FAILED', failure: { error: 'SYNC_FAILED', message: 'GW2 refused' }
    })
    const state = await opened()
    expect(state.recoveryFailure.value).toBe('SYNC_FAILED: GW2 refused')
  })

  it('reports a failed task that carried no detail', async () => {
    ports.readTaskStatus = async (url) => ({ ...succeeded(url), state: 'FAILED', failure: null })
    const state = await opened()
    expect(state.recoveryFailure.value).toBe('The refresh task failed.')
  })

  it('reports a status lookup that failed', async () => {
    ports.readTaskStatus = () => Promise.reject(new ApiRequestError('gone', 'TASK_NOT_FOUND', 404))
    const state = await opened()
    expect(state.recoveryFailure.value).toBe('TASK_NOT_FOUND: gone')
  })

  it('reports a failed recalculation after the task succeeded', async () => {
    api.profitHandler = (_, index) => index === 0 ? Promise.resolve(provisional()) : Promise.reject(new Error('down'))
    const state = open()
    await state.open()
    await flushPromises()
    expect(state.recoveryFailure.value).toBe('down')
    expect(state.rows.value).toHaveLength(1)
  })

  it('reports the budget as used up when the recalculation is still provisional after it', async () => {
    api.profitHandler = (_, index) => {
      if (index === 1) clock += RECOVERY_BUDGET_MS
      return Promise.resolve(provisional())
    }
    const state = open()
    await state.open()
    await flushPromises()
    expect(state.recoveryFailure.value).toBe(RECOVERY_TIMED_OUT)
  })

  it('reports the budget as used up when the pause between rounds outlasts it', async () => {
    const state = await opened()
    expect(state.isRecovering.value).toBe(true)
    await release(RECOVERY_BUDGET_MS)
    expect(state.recoveryFailure.value).toBe(RECOVERY_TIMED_OUT)
    expect(state.isRecovering.value).toBe(false)
  })

  it('reports the budget as used up when a task is still running after it', async () => {
    ports.readTaskStatus = async (url) => ({ ...succeeded(url), state: 'RUNNING', finishedAt: null })
    const state = await opened()
    expect(state.isRecovering.value).toBe(true)
    await release(RECOVERY_BUDGET_MS)
    expect(state.recoveryFailure.value).toBe(RECOVERY_TIMED_OUT)
  })

  it('keeps polling a running task until it succeeds, then shows the final result', async () => {
    let reads = 0
    ports.readTaskStatus = async (url) =>
      reads++ < 2 ? { ...succeeded(url), state: 'RUNNING', finishedAt: null } : succeeded(url)
    api.profitHandler = (_, index) => Promise.resolve(index === 0 ? provisional() : final())
    const state = open()
    await state.open()
    await flushPromises()
    await release()
    await release()
    expect(reads).toBe(3)
    expect(state.isProvisional.value).toBe(false)
    expect(state.recoveryFailure.value).toBeNull()
  })
})

describe('alarm, retry and stale answers', () => {
  it('ignores a budget alarm that fires after the cycle already finished', async () => {
    api.profitHandler = (_, index) => Promise.resolve(index === 0 ? provisional() : final())
    const state = open()
    await state.open()
    await flushPromises()
    expect(state.isRecovering.value).toBe(false)
    alarms.forEach((alarm) => alarm.fire())
    await flushPromises()
    expect(state.recoveryFailure.value).toBeNull()
  })

  it('ignores the alarm of a cycle that an explicit retry replaced', async () => {
    ports.readTaskStatus = () => new Promise<SyncTaskStatus>(() => undefined)
    const state = await opened()
    expect(state.isRecovering.value).toBe(true)
    const retry = state.retryRefresh()
    await flushPromises()
    alarms[0]!.fire()
    await flushPromises()
    expect(state.recoveryFailure.value).toBeNull()
    expect(state.isRecovering.value).toBe(true)
    void retry
  })

  it('discards a recalculation that answers after the budget alarm ended the cycle', async () => {
    const late = deferred<ReturnType<typeof provisional>>()
    api.profitHandler = (_, index) => (index === 0 ? Promise.resolve(provisional()) : late.promise)
    const state = open()
    await state.open()
    await flushPromises()
    alarms[0]!.fire()
    await flushPromises()
    expect(state.recoveryFailure.value).toBe(RECOVERY_TIMED_OUT)
    late.resolve(final())
    await flushPromises()
    expect(state.isProvisional.value).toBe(true)
    expect(state.recoveryFailure.value).toBe(RECOVERY_TIMED_OUT)
  })

  it('discards a failing recalculation that answers after the budget alarm ended the cycle', async () => {
    const late = deferred<ReturnType<typeof provisional>>()
    api.profitHandler = (_, index) => (index === 0 ? Promise.resolve(provisional()) : late.promise)
    const state = open()
    await state.open()
    await flushPromises()
    alarms[0]!.fire()
    late.reject(new Error('late failure'))
    await flushPromises()
    expect(state.recoveryFailure.value).toBe(RECOVERY_TIMED_OUT)
  })

  it('retries with the request first and reports its failure inside the new cycle', async () => {
    api.profitHandler = () => Promise.reject(new ApiRequestError('down', 'BACKEND_UNREACHABLE', null))
    const state = open()
    await state.retryRefresh()
    expect(api.profitRequests).toEqual([{}])
    expect(state.recoveryFailure.value).toBe('BACKEND_UNREACHABLE: down')
    expect(state.isRecovering.value).toBe(false)
  })

  it('sends the selected scope and the settings in effect when retrying', async () => {
    const state = await opened(final)
    api.profitHandler = () => Promise.resolve(final())
    await state.retryRefresh()
    expect(api.profitRequests.at(-1)).toMatchObject({ settings: state.settings.value })
    expect(api.profitRequests.at(-1)).toHaveProperty('scope')
  })

  it('discards an older selection answer, success or failure, that arrives after a newer one', async () => {
    const first = deferred<ReturnType<typeof final>>()
    const second = deferred<ReturnType<typeof final>>()
    const answers = [first, second]
    api.profitHandler = (_, index) => answers[index]!.promise
    const state = open()
    const older = state.reload()
    await flushPromises()
    const newer = state.reload()
    await flushPromises()
    second.resolve(profitResponse([profitableRow], 'ALL'))
    await newer
    first.reject(new Error('older failure'))
    await older
    expect(state.requestError.value).toBeNull()
    expect(state.hasResult.value).toBe(true)
  })

  it('drops every answer once the owner is disposed and starts nothing afterwards', async () => {
    const answer = deferred<ReturnType<typeof final>>()
    api.profitHandler = () => answer.promise
    const state = open()
    const pending = state.open()
    await flushPromises()
    scope.stop()
    answer.resolve(final())
    await pending
    expect(state.hasResult.value).toBe(false)
    await state.reload()
    expect(api.profitRequests).toHaveLength(1)
  })

  it('drops the selector answer once the owner is disposed', async () => {
    const options = deferred<Awaited<ReturnType<FakeCraftingApi['loadSelectorOptions']>>>()
    api.selectorHandler = () => options.promise
    const state = open()
    const pending = state.open()
    scope.stop()
    options.resolve({ defaultScopeKind: 'ALL', disciplines: [], characterOptionCount: 0, characterOptions: [] })
    await pending
    expect(state.scopeOptions.value).toEqual([])
  })

  it('shows a selector failure without losing the calculation', async () => {
    api.selectorHandler = () => Promise.reject(new Error('selector down'))
    const state = open()
    await state.open()
    expect(state.selectorError.value).toBe('selector down')
    expect(state.hasResult.value).toBe(true)
  })
})

describe('the default clock, waits and alarm', () => {
  it('recovers on browser timers when no ports are injected', async () => {
    vi.useFakeTimers()
    let reads = 0
    const own: ProfitRefreshPorts = {
      readTaskStatus: async (url) => (reads++ === 0 ? { ...succeeded(url), state: 'RUNNING', finishedAt: null } : succeeded(url))
    }
    api.profitHandler = (_, index) => Promise.resolve(index === 0 ? provisional() : final())
    const state = scope.run(() => useCraftingProfit(api, own))!
    await state.open()
    await vi.advanceTimersByTimeAsync(RECOVERY_POLL_INTERVAL_MS * 2)
    expect(reads).toBe(2)
    expect(state.isProvisional.value).toBe(false)
    expect(state.isRecovering.value).toBe(false)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('ends on the real alarm when a task status never answers', async () => {
    vi.useFakeTimers()
    const own: ProfitRefreshPorts = { readTaskStatus: () => new Promise<SyncTaskStatus>(() => undefined) }
    api.profitHandler = () => Promise.resolve(provisional())
    const state = scope.run(() => useCraftingProfit(api, own))!
    await state.open()
    await vi.advanceTimersByTimeAsync(RECOVERY_BUDGET_MS)
    expect(state.recoveryFailure.value).toBe(RECOVERY_TIMED_OUT)
    expect(state.isRecovering.value).toBe(false)
  })
})
