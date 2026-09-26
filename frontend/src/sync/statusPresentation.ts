/**
 * How a synchronization operation's situation is worded for a user
 * (`FRONTEND_UX_GUIDELINES.md` 5, 6).
 *
 * The backend's lifecycle states are explained in ordinary language without changing what they mean,
 * and the distinctions the contract makes are kept: a refused trigger, an unconfirmed one, a task
 * that ran and failed, and a lookup that never established an outcome are four different situations,
 * not one. An unrecognized state stays visible as itself — it must never round up to success.
 *
 * No progress, percentage, step count or cancellation exists in these words, because none exists in
 * the contract (`CURRENT_ARCHITECTURE.md` 5.7–5.9).
 */
import type { SyncOperationView } from './useSyncOperations'

/** Reinforces the wording; it never carries meaning on its own. */
export type StatusTone = 'idle' | 'busy' | 'success' | 'failure' | 'unknown'

export interface StatusPresentation {
  readonly text: string
  readonly tone: StatusTone
}

const TASK_STATES: Readonly<Record<string, StatusPresentation>> = {
  PENDING: { text: 'Accepted, waiting to start', tone: 'busy' },
  RUNNING: { text: 'Running', tone: 'busy' },
  SUCCEEDED: { text: 'Completed', tone: 'success' },
  FAILED: { text: 'Failed', tone: 'failure' }
}

/** The four states `CURRENT_ARCHITECTURE.md` 5.7 defines, plus anything else shown as itself. */
export function describeTaskState(state: string): StatusPresentation {
  return TASK_STATES[state] ?? { text: `Unrecognized state: ${state}`, tone: 'unknown' }
}

/** True once the backend has reported an outcome for the task — success or failure. */
function isFinished(state: string): boolean {
  const tone = describeTaskState(state).tone
  return tone === 'success' || tone === 'failure'
}

/** The one line that answers "what is happening with this operation?". */
export function describeOperationStatus(operation: SyncOperationView): StatusPresentation {
  if (operation.isSubmitting) return { text: 'Starting…', tone: 'busy' }

  if (operation.status !== null) {
    const reported = describeTaskState(operation.status.state)
    // Tracking stopped before the backend reported an outcome: the last state it did report stays
    // visible, and is explicitly not presented as this task's result.
    if (operation.lookupError !== null && !isFinished(operation.status.state)) {
      return { text: `${reported.text} — outcome not established`, tone: 'unknown' }
    }
    return reported
  }

  if (operation.submissionError !== null) {
    return operation.isAdmissionUnknown
      ? { text: 'Outcome not established', tone: 'unknown' }
      : { text: 'Not accepted', tone: 'failure' }
  }

  if (operation.lookupError !== null) return { text: 'Outcome not established', tone: 'unknown' }

  return { text: 'Not started', tone: 'idle' }
}
