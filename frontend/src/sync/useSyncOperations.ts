/**
 * Trigger and task-tracking state for the backend synchronization operations
 * (`CURRENT_ARCHITECTURE.md` 5.7–5.9).
 *
 * It orchestrates nothing: a trigger is one POST, and everything afterwards is read from the task
 * status the backend reports. No sequence, ordering, item selection or outcome is decided here, and
 * no progress is invented — the backend reports lifecycle states, not steps or counts.
 *
 * One instance is created by the application shell (`provideSyncOperations`), not by the screen that
 * renders it: opening another application area therefore keeps an unfinished task tracked, without a
 * second trigger and without a second polling loop. The timers are cleared when that owning scope is
 * disposed — the tracking really going away — which for the shell means the application unmounting.
 * Nothing is persisted, so a browser reload starts with no tracked task at all.
 */
import { onScopeDispose, reactive } from 'vue'
import { ApiRequestError, UNREACHABLE_CODE } from '@/api/http'
import { syncApi, type SyncApi } from '@/api/syncApi'
import type { SyncTaskAccepted, SyncTaskStatus } from '@/api/types'
import { SYNC_OPERATIONS, type SyncOperation, type SyncOperationKey } from './operations'

/** Delay between two status lookups of the same task. */
export const STATUS_POLL_INTERVAL_MS = 3_000

/**
 * Upper bound on the status lookups one tracked task gets; 600 at the interval above is half an
 * hour. A task this client never sees finish is not a reason to keep asking forever, and reaching
 * the bound reports an outcome that could not be established here — never a result.
 */
export const MAX_STATUS_POLLS = 600

const TERMINAL_STATES: readonly string[] = ['SUCCEEDED', 'FAILED']

/** What the panel renders for one operation. Every lifecycle fact comes from `status`. */
export interface SyncOperationView {
  readonly key: SyncOperationKey
  readonly label: string
  /** The trigger request is in flight; the backend has not accepted or refused it yet. */
  isSubmitting: boolean
  /** A task of this operation is tracked and has not reached a terminal state here. */
  isTracking: boolean
  readonly isBusy: boolean
  taskId: string | null
  /** The last status body read for `taskId`. */
  status: SyncTaskStatus | null
  /** The trigger was refused or never answered, so no task of this client's is running. */
  submissionError: string | null
  /** The trigger failed in transport: whether the backend admitted it is unknown. */
  isAdmissionUnknown: boolean
  /** The status lookup failed — distinct from a task that ran and failed. */
  lookupError: string | null
  /** The backend cannot resolve the identifier, so asking it again cannot establish the outcome. */
  isTaskUnresolvable: boolean
  readonly canRetryStatus: boolean
}

export interface SyncOperationsState {
  operations: readonly SyncOperationView[]
  trigger(key: SyncOperationKey): Promise<void>
  retryStatus(key: SyncOperationKey): Promise<void>
}

interface OperationRuntime {
  readonly operation: SyncOperation
  readonly view: SyncOperationView
  /** Identifies the tracked task; an answer carrying a superseded token is discarded. */
  token: number
  timer: ReturnType<typeof setTimeout> | null
  statusUrl: string | null
  remainingPolls: number
}

export function useSyncOperations(api: SyncApi = syncApi): SyncOperationsState {
  const runtimes: OperationRuntime[] = SYNC_OPERATIONS.map((operation) => ({
    operation,
    view: createView(operation),
    token: 0,
    timer: null,
    statusUrl: null,
    remainingPolls: 0
  }))
  let isDisposed = false

  /**
   * Submits one operation. A click while this operation is submitting or still tracking an
   * unfinished task of its own is ignored; the other operations stay independently triggerable,
   * exactly as the backend's per-key admission rule allows.
   */
  async function trigger(key: SyncOperationKey): Promise<void> {
    const runtime = runtimeOf(key)
    if (runtime.view.isBusy) return

    stopPolling(runtime)
    // Any still-pending answer belonging to the previous task of this operation is now stale.
    const token = ++runtime.token
    clearOutcome(runtime)
    runtime.view.isSubmitting = true

    try {
      const accepted = await runtime.operation.submit(api)
      if (isStale(runtime, token)) return
      track(runtime, accepted, token)
    } catch (cause) {
      if (isStale(runtime, token)) return
      runtime.view.submissionError = describeFailure(cause)
      // A trigger that never got an answer may or may not have been admitted. This client never
      // resubmits on its own; a further run stays a deliberate decision of the user's.
      runtime.view.isAdmissionUnknown = isTransportFailure(cause)
    } finally {
      runtime.view.isSubmitting = false
    }
  }

  /** Looks the tracked task up again. The trigger is never repeated — the task already exists. */
  async function retryStatus(key: SyncOperationKey): Promise<void> {
    const runtime = runtimeOf(key)
    if (!runtime.view.canRetryStatus) return

    runtime.view.lookupError = null
    runtime.view.isTracking = true
    runtime.remainingPolls = MAX_STATUS_POLLS
    await readStatus(runtime, runtime.token)
  }

  function track(runtime: OperationRuntime, accepted: SyncTaskAccepted, token: number): void {
    runtime.view.taskId = accepted.taskId
    runtime.view.isTracking = true
    runtime.statusUrl = accepted.statusUrl
    runtime.remainingPolls = MAX_STATUS_POLLS
    void readStatus(runtime, token)
  }

  async function readStatus(runtime: OperationRuntime, token: number): Promise<void> {
    const statusUrl = runtime.statusUrl
    if (statusUrl === null) return
    if (runtime.remainingPolls <= 0) {
      stopTracking(runtime, boundReachedMessage(), false)
      return
    }
    runtime.remainingPolls -= 1

    try {
      const status = await api.readTaskStatus(statusUrl)
      if (isStale(runtime, token)) return
      runtime.view.status = status
      runtime.view.lookupError = null
      if (TERMINAL_STATES.includes(status.state)) {
        runtime.view.isTracking = false
        return
      }
      // Scheduled only now that this lookup answered, so two status requests never overlap.
      runtime.timer = setTimeout(() => void readStatus(runtime, token), STATUS_POLL_INTERVAL_MS)
    } catch (cause) {
      if (isStale(runtime, token)) return
      stopTracking(runtime, describeFailure(cause), isUnresolvableTask(cause))
    }
  }

  function stopTracking(runtime: OperationRuntime, lookupError: string, isUnresolvable: boolean): void {
    stopPolling(runtime)
    runtime.view.isTracking = false
    runtime.view.lookupError = lookupError
    runtime.view.isTaskUnresolvable = isUnresolvable
  }

  function stopPolling(runtime: OperationRuntime): void {
    if (runtime.timer === null) return
    clearTimeout(runtime.timer)
    runtime.timer = null
  }

  function clearOutcome(runtime: OperationRuntime): void {
    runtime.statusUrl = null
    runtime.view.taskId = null
    runtime.view.status = null
    runtime.view.submissionError = null
    runtime.view.isAdmissionUnknown = false
    runtime.view.lookupError = null
    runtime.view.isTaskUnresolvable = false
  }

  /** True once the owning UI is gone, or once this answer belongs to a task no longer tracked. */
  function isStale(runtime: OperationRuntime, token: number): boolean {
    return isDisposed || token !== runtime.token
  }

  function runtimeOf(key: SyncOperationKey): OperationRuntime {
    const runtime = runtimes.find((candidate) => candidate.operation.key === key)
    if (runtime === undefined) throw new Error(`Unknown synchronization operation: ${key}`)
    return runtime
  }

  onScopeDispose(() => {
    isDisposed = true
    runtimes.forEach((runtime) => stopPolling(runtime))
  })

  return {
    operations: runtimes.map((runtime) => runtime.view),
    trigger,
    retryStatus
  }
}

function createView(operation: SyncOperation): SyncOperationView {
  return reactive({
    key: operation.key,
    label: operation.label,
    isSubmitting: false,
    isTracking: false,
    taskId: null as string | null,
    status: null as SyncTaskStatus | null,
    submissionError: null as string | null,
    isAdmissionUnknown: false,
    lookupError: null as string | null,
    isTaskUnresolvable: false,

    get isBusy(): boolean {
      return this.isSubmitting || this.isTracking
    },

    /** Retrying a lookup the backend answered with "unknown task" cannot establish anything. */
    get canRetryStatus(): boolean {
      return this.lookupError !== null && !this.isTaskUnresolvable && !this.isTracking
    }
  })
}

function boundReachedMessage(): string {
  return `This client stopped polling after ${MAX_STATUS_POLLS} status lookups.`
}

function isUnresolvableTask(cause: unknown): boolean {
  return cause instanceof ApiRequestError && cause.status === 404
}

function isTransportFailure(cause: unknown): boolean {
  return cause instanceof ApiRequestError && cause.code === UNREACHABLE_CODE
}

function describeFailure(cause: unknown): string {
  if (cause instanceof ApiRequestError) return `${cause.code}: ${cause.message}`
  return cause instanceof Error ? cause.message : String(cause)
}
