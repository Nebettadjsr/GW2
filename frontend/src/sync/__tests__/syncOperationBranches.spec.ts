import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, type EffectScope } from 'vue'
import { ApiRequestError, UNREACHABLE_CODE } from '@/api/http'
import {
  MAX_STATUS_POLLS,
  STATUS_POLL_INTERVAL_MS,
  useSyncOperations,
  type SyncOperationsState
} from '../useSyncOperations'
import { FakeSyncApi, acceptance, deferred, statusOf } from './syncFixtures'

/**
 * Submission failure, lookup failure and retry, the polling bound and disposal while a request is in
 * flight — all with controlled promises and a fake clock, so nothing here waits in real time.
 */
let scope: EffectScope
let api: FakeSyncApi

beforeEach(() => {
  vi.useFakeTimers()
  scope = effectScope()
  api = new FakeSyncApi()
})

afterEach(() => {
  scope.stop()
  vi.useRealTimers()
})

function open(): SyncOperationsState {
  return scope.run(() => useSyncOperations(api))!
}
const account = (sync: SyncOperationsState) => sync.operations.find((view) => view.key === 'ACCOUNT_SYNC')!

describe('submission', () => {
  it('ignores a second click while the first trigger is unanswered', async () => {
    const answer = deferred<ReturnType<typeof acceptance>>()
    api.submitHandler = () => answer.promise
    const sync = open()
    const first = sync.trigger('ACCOUNT_SYNC')
    await sync.trigger('ACCOUNT_SYNC')
    expect(api.submissionCount('ACCOUNT_SYNC')).toBe(1)
    expect(account(sync).isSubmitting).toBe(true)
    answer.resolve(acceptance('ACCOUNT_SYNC'))
    await first
    await vi.advanceTimersByTimeAsync(0)
    expect(account(sync).isBusy).toBe(false)
  })

  it('reports a refused trigger by code and keeps it free of an unknown-admission warning', async () => {
    api.submitHandler = () => Promise.reject(new ApiRequestError('busy', 'SYNC_ALREADY_RUNNING', 409))
    const sync = open()
    await sync.trigger('ACCOUNT_SYNC')
    expect(account(sync).submissionError).toBe('SYNC_ALREADY_RUNNING: busy')
    expect(account(sync).isAdmissionUnknown).toBe(false)
    expect(account(sync).isBusy).toBe(false)
  })

  it('marks admission unknown when the trigger never reached the backend', async () => {
    api.submitHandler = () => Promise.reject(new ApiRequestError('offline', UNREACHABLE_CODE, null))
    const sync = open()
    await sync.trigger('ACCOUNT_SYNC')
    expect(account(sync).isAdmissionUnknown).toBe(true)
    expect(api.submissionCount('ACCOUNT_SYNC')).toBe(1)
  })

  it('words a plain Error and a non-Error failure', async () => {
    api.submitHandler = (_, index) => (index === 0 ? Promise.reject(new Error('plain')) : Promise.reject('text'))
    const sync = open()
    await sync.trigger('ACCOUNT_SYNC')
    expect(account(sync).submissionError).toBe('plain')
    await sync.trigger('ACCOUNT_SYNC')
    expect(account(sync).submissionError).toBe('text')
  })

  it('drops a submission answer that arrives after the owner was disposed', async () => {
    const answer = deferred<ReturnType<typeof acceptance>>()
    api.submitHandler = () => answer.promise
    const sync = open()
    const pending = sync.trigger('ACCOUNT_SYNC')
    scope.stop()
    answer.resolve(acceptance('ACCOUNT_SYNC'))
    await pending
    expect(account(sync).taskId).toBeNull()
    expect(api.statusRequests).toHaveLength(0)
  })

  it('drops a submission failure that arrives after the owner was disposed', async () => {
    const answer = deferred<ReturnType<typeof acceptance>>()
    api.submitHandler = () => answer.promise
    const sync = open()
    const pending = sync.trigger('ACCOUNT_SYNC')
    scope.stop()
    answer.reject(new Error('late'))
    await pending
    expect(account(sync).submissionError).toBeNull()
  })

  it('rejects an unknown operation key', async () => {
    const sync = open()
    await expect(sync.trigger('NOPE' as never)).rejects.toThrow('Unknown synchronization operation: NOPE')
  })
})

describe('status lookup', () => {
  it('polls on the interval until the task reaches a terminal state', async () => {
    api.statusHandler = (url, index) => Promise.resolve(statusOf(url, index < 2 ? 'RUNNING' : 'FAILED'))
    const sync = open()
    await sync.trigger('ACCOUNT_SYNC')
    await vi.advanceTimersByTimeAsync(0)
    expect(api.statusRequests).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS - 1)
    expect(api.statusRequests).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(api.statusRequests).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS)
    expect(account(sync).status?.state).toBe('FAILED')
    expect(account(sync).isTracking).toBe(false)
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 5)
    expect(api.statusRequests).toHaveLength(3)
  })

  it('treats an unknown task as unresolvable and offers no retry', async () => {
    api.statusHandler = () => Promise.reject(new ApiRequestError('gone', 'TASK_NOT_FOUND', 404))
    const sync = open()
    await sync.trigger('ACCOUNT_SYNC')
    await vi.advanceTimersByTimeAsync(0)
    expect(account(sync).lookupError).toBe('TASK_NOT_FOUND: gone')
    expect(account(sync).isTaskUnresolvable).toBe(true)
    expect(account(sync).canRetryStatus).toBe(false)
    await sync.retryStatus('ACCOUNT_SYNC')
    expect(api.statusRequests).toHaveLength(1)
    expect(api.submissionCount('ACCOUNT_SYNC')).toBe(1)
  })

  it('retries only the lookup after a transient failure, without a new trigger', async () => {
    api.statusHandler = (url, index) =>
      index === 0 ? Promise.reject(new ApiRequestError('offline', UNREACHABLE_CODE, null)) : Promise.resolve(statusOf(url, 'SUCCEEDED'))
    const sync = open()
    await sync.trigger('ACCOUNT_SYNC')
    await vi.advanceTimersByTimeAsync(0)
    expect(account(sync).canRetryStatus).toBe(true)
    expect(account(sync).isTracking).toBe(false)
    await sync.retryStatus('ACCOUNT_SYNC')
    expect(account(sync).lookupError).toBeNull()
    expect(account(sync).status?.state).toBe('SUCCEEDED')
    expect(api.submissionCount('ACCOUNT_SYNC')).toBe(1)
  })

  it('stops after the bounded number of lookups and a retry gets a fresh bound', async () => {
    api.statusHandler = (url) => Promise.resolve(statusOf(url, 'RUNNING'))
    const sync = open()
    await sync.trigger('ACCOUNT_SYNC')
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * (MAX_STATUS_POLLS + 2))
    expect(api.statusRequests).toHaveLength(MAX_STATUS_POLLS)
    expect(account(sync).lookupError).toBe(`This client stopped polling after ${MAX_STATUS_POLLS} status lookups.`)
    expect(account(sync).isTracking).toBe(false)

    await sync.retryStatus('ACCOUNT_SYNC')
    expect(api.statusRequests).toHaveLength(MAX_STATUS_POLLS + 1)
    expect(account(sync).isTracking).toBe(true)
  })

  it('ignores a retry when there is nothing to look up again', async () => {
    const sync = open()
    await sync.retryStatus('GLOBAL_SYNC')
    expect(api.statusRequests).toHaveLength(0)
  })

  it('drops a status answer and a status failure that arrive after disposal', async () => {
    const answer = deferred<ReturnType<typeof statusOf>>()
    api.statusHandler = () => answer.promise
    const sync = open()
    await sync.trigger('ACCOUNT_SYNC')
    await vi.advanceTimersByTimeAsync(0)
    scope.stop()
    answer.resolve(statusOf('/api/sync/tasks/task-ACCOUNT_SYNC', 'SUCCEEDED'))
    await vi.advanceTimersByTimeAsync(0)
    expect(account(sync).status).toBeNull()

    scope = effectScope()
    api = new FakeSyncApi()
    const failing = deferred<ReturnType<typeof statusOf>>()
    api.statusHandler = () => failing.promise
    const second = open()
    await second.trigger('ACCOUNT_SYNC')
    await vi.advanceTimersByTimeAsync(0)
    scope.stop()
    failing.reject(new Error('late'))
    await vi.advanceTimersByTimeAsync(0)
    expect(account(second).lookupError).toBeNull()
  })

  it('reports a failed repeat submission after a narrower run ended', async () => {
    api.submitHandler = (_, index) =>
      index === 0
        ? Promise.resolve({ ...acceptance('ACCOUNT_SYNC'), coversRequest: false })
        : Promise.reject(new ApiRequestError('offline', UNREACHABLE_CODE, null))
    const sync = open()
    await sync.trigger('ACCOUNT_SYNC')
    await vi.advanceTimersByTimeAsync(0)
    expect(account(sync).isTracking).toBe(false)
    expect(account(sync).submissionError).toContain(UNREACHABLE_CODE)
    expect(account(sync).isAdmissionUnknown).toBe(true)
  })

  it('drops a repeat submission answer that arrives after disposal', async () => {
    const repeat = deferred<ReturnType<typeof acceptance>>()
    api.submitHandler = (_, index) =>
      index === 0 ? Promise.resolve({ ...acceptance('ACCOUNT_SYNC'), coversRequest: false }) : repeat.promise
    const sync = open()
    await sync.trigger('ACCOUNT_SYNC')
    await vi.advanceTimersByTimeAsync(0)
    expect(api.submissionCount('ACCOUNT_SYNC')).toBe(2)
    scope.stop()
    repeat.reject(new Error('late'))
    await vi.advanceTimersByTimeAsync(0)
    expect(account(sync).submissionError).toBeNull()
  })
})
