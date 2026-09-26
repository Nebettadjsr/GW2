import type { SyncApi } from '@/api/syncApi'
import type { ApiErrorBody, PriceRefreshVariant, SyncTaskAccepted, SyncTaskStatus } from '@/api/types'
import type { SyncOperationKey } from '../operations'

/**
 * Controlled backend answers for the synchronization-panel tests.
 *
 * The stand-in answers like the routes it stands in for: a trigger returns an acceptance carrying
 * a task identity and the status location to poll, and a status lookup returns a lifecycle state —
 * a failed task included, which the real backend reports with HTTP 200.
 */
const TASK_ID_PREFIX = 'task-'

export function acceptance(operation: SyncOperationKey): SyncTaskAccepted {
  const taskId = `${TASK_ID_PREFIX}${operation}`
  return { taskId, operation, statusUrl: `/api/sync/tasks/${taskId}` }
}

/** The status body for the task the advertised `statusUrl` names. */
export function statusOf(
  statusUrl: string,
  state: string,
  failure: ApiErrorBody | null = null
): SyncTaskStatus {
  const taskId = statusUrl.slice(statusUrl.lastIndexOf('/') + 1)
  return {
    taskId,
    operation: taskId.slice(TASK_ID_PREFIX.length),
    state,
    submittedAt: '2026-09-25T10:00:00Z',
    startedAt: state === 'PENDING' ? null : '2026-09-25T10:00:01Z',
    finishedAt: state === 'SUCCEEDED' || state === 'FAILED' ? '2026-09-25T10:00:09Z' : null,
    failure
  }
}

/** The backend's own wording for a failed task, no-rollback qualification included. */
export const SYNC_FAILURE: ApiErrorBody = {
  error: 'SYNC_FAILED',
  message: 'Synchronization failed; steps that had already completed were not rolled back'
}

/** A promise a test resolves by hand, for in-flight and out-of-order checks. */
export interface Deferred<T> {
  promise: Promise<T>
  resolve(value: T): void
  reject(cause: unknown): void
}

export function deferred<T>(): Deferred<T> {
  let resolve: (value: T) => void = () => undefined
  let reject: (cause: unknown) => void = () => undefined
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

/** Records every request and answers from handlers the test supplies. */
export class FakeSyncApi implements SyncApi {
  readonly submissions: SyncOperationKey[] = []
  readonly statusRequests: string[] = []

  submitHandler: (operation: SyncOperationKey, callIndex: number) => Promise<SyncTaskAccepted> = (
    operation
  ) => Promise.resolve(acceptance(operation))

  statusHandler: (statusUrl: string, callIndex: number) => Promise<SyncTaskStatus> = (statusUrl) =>
    Promise.resolve(statusOf(statusUrl, 'SUCCEEDED'))

  startAccountSync(): Promise<SyncTaskAccepted> {
    return this.record('ACCOUNT_SYNC')
  }

  startGlobalSync(): Promise<SyncTaskAccepted> {
    return this.record('GLOBAL_SYNC')
  }

  /** The variant selects the operation key exactly as the backend's own mapping does. */
  startPriceRefresh(variant: PriceRefreshVariant): Promise<SyncTaskAccepted> {
    return this.record(variant === 'PROFIT' ? 'PRICE_REFRESH_PROFIT' : 'PRICE_REFRESH_DISCOVERY')
  }

  readTaskStatus(statusUrl: string): Promise<SyncTaskStatus> {
    const callIndex = this.statusRequests.length
    this.statusRequests.push(statusUrl)
    return this.statusHandler(statusUrl, callIndex)
  }

  submissionCount(operation: SyncOperationKey): number {
    return this.submissions.filter((submitted) => submitted === operation).length
  }

  statusRequestCount(operation: SyncOperationKey): number {
    const statusUrl = acceptance(operation).statusUrl
    return this.statusRequests.filter((requested) => requested === statusUrl).length
  }

  private record(operation: SyncOperationKey): Promise<SyncTaskAccepted> {
    const callIndex = this.submissions.length
    this.submissions.push(operation)
    return this.submitHandler(operation, callIndex)
  }
}
