import { describe, expect, it } from 'vitest'
import { describeOperationStatus, describeTaskState } from '../statusPresentation'
import type { SyncOperationView } from '../useSyncOperations'
import { SYNC_FAILURE, statusOf } from './syncFixtures'

/**
 * The wording of a synchronization situation, checked directly: the four backend states explained
 * without changing their meaning, and the distinctions the contract makes kept apart.
 */
const IDLE: SyncOperationView = {
  key: 'ACCOUNT_SYNC',
  label: 'Synchronize account',
  isSubmitting: false,
  isTracking: false,
  isBusy: false,
  taskId: null,
  status: null,
  submissionError: null,
  isAdmissionUnknown: false,
  lookupError: null,
  isTaskUnresolvable: false,
  canRetryStatus: false
}

function view(changed: Partial<SyncOperationView>): SyncOperationView {
  return { ...IDLE, ...changed }
}

const RUNNING = statusOf('/api/sync/tasks/task-1', 'RUNNING')

describe('describeTaskState', () => {
  it('explainsTheFourBackendStatesInUserOrientedWords', () => {
    expect(describeTaskState('PENDING')).toEqual({
      text: 'Accepted, waiting to start',
      tone: 'busy'
    })
    expect(describeTaskState('RUNNING')).toEqual({ text: 'Running', tone: 'busy' })
    expect(describeTaskState('SUCCEEDED')).toEqual({ text: 'Completed', tone: 'success' })
    expect(describeTaskState('FAILED')).toEqual({ text: 'Failed', tone: 'failure' })
  })

  it('keepsAnUnrecognizedStateVisibleAsItselfInsteadOfRoundingItToSuccess', () => {
    const described = describeTaskState('QUEUED_BEHIND_ANOTHER')

    expect(described.text).toContain('QUEUED_BEHIND_ANOTHER')
    expect(described.tone).toBe('unknown')
    expect(described.tone).not.toBe('success')
  })
})

describe('describeOperationStatus', () => {
  it('distinguishesNotStartedFromSubmittingFromTracking', () => {
    expect(describeOperationStatus(IDLE)).toEqual({ text: 'Not started', tone: 'idle' })
    expect(describeOperationStatus(view({ isSubmitting: true, isBusy: true }))).toEqual({
      text: 'Starting…',
      tone: 'busy'
    })
    expect(describeOperationStatus(view({ isTracking: true, isBusy: true, status: RUNNING }))).toEqual(
      { text: 'Running', tone: 'busy' }
    )
  })

  it('separatesARefusedTriggerFromOneWhoseAdmissionIsUnknown', () => {
    const refused = view({ submissionError: 'SYNC_ALREADY_RUNNING: already running' })
    const unanswered = view({
      submissionError: 'BACKEND_UNREACHABLE: no answer',
      isAdmissionUnknown: true
    })

    expect(describeOperationStatus(refused)).toEqual({ text: 'Not accepted', tone: 'failure' })
    expect(describeOperationStatus(unanswered)).toEqual({
      text: 'Outcome not established',
      tone: 'unknown'
    })
  })

  it('separatesATaskThatFailedFromALookupThatEstablishedNothing', () => {
    const failed = view({ status: statusOf('/api/sync/tasks/task-1', 'FAILED', SYNC_FAILURE) })
    const notFollowed = view({ status: RUNNING, lookupError: 'BACKEND_UNREACHABLE: no answer' })

    expect(describeOperationStatus(failed)).toEqual({ text: 'Failed', tone: 'failure' })
    // The last reported state stays visible, explicitly not as this task's outcome.
    expect(describeOperationStatus(notFollowed).text).toBe('Running — outcome not established')
    expect(describeOperationStatus(notFollowed).tone).toBe('unknown')
  })

  it('keepsAReportedOutcomeWhenTrackingStoppedAfterIt', () => {
    const finishedThenLookupFailed = view({
      status: statusOf('/api/sync/tasks/task-1', 'SUCCEEDED'),
      lookupError: 'BACKEND_UNREACHABLE: no answer'
    })

    // The backend already reported the outcome; a later failed lookup does not take it away.
    expect(describeOperationStatus(finishedThenLookupFailed)).toEqual({
      text: 'Completed',
      tone: 'success'
    })
  })
})
