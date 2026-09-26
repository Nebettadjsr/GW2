import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, type PropType } from 'vue'
import { ApiRequestError, UNREACHABLE_CODE } from '@/api/http'
import type { SyncTaskAccepted, SyncTaskStatus } from '@/api/types'
import { provideSyncOperations } from '../provideSyncOperations'
import SyncScreen from '../SyncScreen.vue'
import { MAX_STATUS_POLLS, STATUS_POLL_INTERVAL_MS } from '../useSyncOperations'
import { FakeSyncApi, SYNC_FAILURE, acceptance, deferred, statusOf } from './syncFixtures'

/**
 * Interaction checks for the synchronization area (STORY-WEB-002 semantics, STORY-WEB-004
 * presentation).
 *
 * Every backend answer is controlled and every wait is a faked timer: no GW2 API call, no real
 * backend and no wall-clock delay is involved, so nothing here establishes that a real
 * synchronization ran (TEST_STRATEGY 33).
 *
 * The page injects the shell's tracking state, so these tests supply it through a stand-in shell.
 * Unmounting that shell is what disposes the tracking — the case in which the timers must stop.
 */
const Shell = defineComponent({
  name: 'TestShell',
  props: { api: { type: Object as PropType<FakeSyncApi>, required: true } },
  setup(props) {
    provideSyncOperations(props.api)
    return () => h(SyncScreen)
  }
})

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

/** Drains the pending promise continuations and Vue's render queue without moving the clock. */
async function settle(): Promise<void> {
  for (let step = 0; step < 10; step += 1) await Promise.resolve()
  await nextTick()
}

/** Moves the faked clock to the next scheduled status lookup and lets its answer be applied. */
async function advanceToNextPoll(): Promise<void> {
  await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS)
  await settle()
}

function mountControls(api: FakeSyncApi): VueWrapper {
  return mount(Shell, { props: { api } })
}

async function click(wrapper: VueWrapper, testId: string): Promise<void> {
  await wrapper.find(`[data-test="${testId}"]`).trigger('click')
  await settle()
}

function textOf(wrapper: VueWrapper, testId: string): string {
  return wrapper.find(`[data-test="${testId}"]`).text()
}

function exists(wrapper: VueWrapper, testId: string): boolean {
  return wrapper.find(`[data-test="${testId}"]`).exists()
}

function isDisabled(wrapper: VueWrapper, testId: string): boolean {
  return (wrapper.find(`[data-test="${testId}"]`).element as HTMLButtonElement).disabled
}

describe('SyncScreen', () => {
  it('startsNoSynchronizationWhenMounted', async () => {
    const api = new FakeSyncApi()

    const wrapper = mountControls(api)
    await settle()

    expect(api.submissions).toEqual([])
    expect(api.statusRequests).toEqual([])
    expect(textOf(wrapper, 'sync-state-ACCOUNT_SYNC')).toBe('Not started')
    expect(isDisabled(wrapper, 'sync-trigger-GLOBAL_SYNC')).toBe(false)
  })

  it('namesEachOperationForTheUserAndKeepsTheBackendKeysOutOfTheControls', async () => {
    const wrapper = mountControls(new FakeSyncApi())
    await settle()

    const triggers = wrapper.findAll('[data-test^="sync-trigger-"]').map((button) => button.text())
    expect(triggers).toEqual([
      'Synchronize account',
      'Synchronize game data',
      'Refresh Trading Post prices for Crafting Profit',
      'Refresh Trading Post prices for Crafting Discovery'
    ])
    // No task exists yet, so there is no identifier or timestamp to disclose.
    expect(exists(wrapper, 'sync-diagnostics-ACCOUNT_SYNC')).toBe(false)
  })

  it('submitsEachTriggerExactlyOnceAndTracksTheAcceptedTask', async () => {
    const api = new FakeSyncApi()
    api.statusHandler = (statusUrl) => Promise.resolve(statusOf(statusUrl, 'PENDING'))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    await click(wrapper, 'sync-trigger-GLOBAL_SYNC')
    await click(wrapper, 'sync-trigger-PRICE_REFRESH_PROFIT')
    await click(wrapper, 'sync-trigger-PRICE_REFRESH_DISCOVERY')

    expect(api.submissions).toEqual([
      'ACCOUNT_SYNC',
      'GLOBAL_SYNC',
      'PRICE_REFRESH_PROFIT',
      'PRICE_REFRESH_DISCOVERY'
    ])
    expect(api.statusRequests).toEqual([
      acceptance('ACCOUNT_SYNC').statusUrl,
      acceptance('GLOBAL_SYNC').statusUrl,
      acceptance('PRICE_REFRESH_PROFIT').statusUrl,
      acceptance('PRICE_REFRESH_DISCOVERY').statusUrl
    ])
    // The identifier is disclosed, but only inside the labelled technical details.
    expect(textOf(wrapper, 'sync-diagnostics-PRICE_REFRESH_DISCOVERY')).toContain(
      acceptance('PRICE_REFRESH_DISCOVERY').taskId
    )
    expect(textOf(wrapper, 'sync-task-PRICE_REFRESH_DISCOVERY')).toContain(
      acceptance('PRICE_REFRESH_DISCOVERY').taskId
    )
  })

  it('rendersEveryLifecycleStateTheBackendReportsInUserOrientedWords', async () => {
    const api = new FakeSyncApi()
    const states = ['PENDING', 'RUNNING', 'SUCCEEDED']
    api.statusHandler = (statusUrl, callIndex) =>
      Promise.resolve(statusOf(statusUrl, states[callIndex] ?? 'SUCCEEDED'))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    expect(textOf(wrapper, 'sync-state-ACCOUNT_SYNC')).toBe('Accepted, waiting to start')
    expect(exists(wrapper, 'sync-finished-ACCOUNT_SYNC')).toBe(false)

    await advanceToNextPoll()
    expect(textOf(wrapper, 'sync-state-ACCOUNT_SYNC')).toBe('Running')
    expect(isDisabled(wrapper, 'sync-trigger-ACCOUNT_SYNC')).toBe(true)
    expect(textOf(wrapper, 'sync-busy-reason-ACCOUNT_SYNC')).toContain('Unavailable until')

    await advanceToNextPoll()
    expect(textOf(wrapper, 'sync-state-ACCOUNT_SYNC')).toBe('Completed')
    expect(exists(wrapper, 'sync-finished-ACCOUNT_SYNC')).toBe(true)
    expect(isDisabled(wrapper, 'sync-trigger-ACCOUNT_SYNC')).toBe(false)
    // The backend's own state code stays available as evidence, in the secondary details only.
    expect(textOf(wrapper, 'sync-reported-state-ACCOUNT_SYNC')).toContain('SUCCEEDED')

    // Terminal: the panel stops asking rather than polling a finished task forever.
    const requestsWhenTerminal = api.statusRequests.length
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 5)
    expect(api.statusRequests).toHaveLength(requestsWhenTerminal)
  })

  it('showsAnUnrecognizedStateAsItselfRatherThanAsSuccess', async () => {
    const api = new FakeSyncApi()
    api.statusHandler = (statusUrl) => Promise.resolve(statusOf(statusUrl, 'PAUSED_BY_OPERATOR'))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-GLOBAL_SYNC')

    const state = textOf(wrapper, 'sync-state-GLOBAL_SYNC')
    expect(state).toContain('PAUSED_BY_OPERATOR')
    expect(state).not.toContain('Completed')
    // An unknown state is not terminal either, so the client keeps looking it up.
    await advanceToNextPoll()
    expect(api.statusRequestCount('GLOBAL_SYNC')).toBe(2)
  })

  it('treatsAFailedTaskReturnedWithHttp200AsAFailureAndStopsPolling', async () => {
    const api = new FakeSyncApi()
    api.statusHandler = (statusUrl) => Promise.resolve(statusOf(statusUrl, 'FAILED', SYNC_FAILURE))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-GLOBAL_SYNC')

    expect(textOf(wrapper, 'sync-state-GLOBAL_SYNC')).toBe('Failed')
    const failure = textOf(wrapper, 'sync-task-failure-GLOBAL_SYNC')
    expect(failure).toContain('were not rolled back')
    expect(failure).toContain('SYNC_FAILED')
    // A task failure is not a lookup failure: the status request itself succeeded.
    expect(exists(wrapper, 'sync-lookup-error-GLOBAL_SYNC')).toBe(false)

    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 5)
    expect(api.statusRequestCount('GLOBAL_SYNC')).toBe(1)
  })

  it('keepsTheOperationStatusesSeparateIncludingTheTwoPriceVariants', async () => {
    const api = new FakeSyncApi()
    api.statusHandler = (statusUrl) =>
      Promise.resolve(
        statusUrl === acceptance('PRICE_REFRESH_PROFIT').statusUrl
          ? statusOf(statusUrl, 'RUNNING')
          : statusOf(statusUrl, 'FAILED', SYNC_FAILURE)
      )
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-PRICE_REFRESH_PROFIT')
    await click(wrapper, 'sync-trigger-PRICE_REFRESH_DISCOVERY')

    expect(textOf(wrapper, 'sync-state-PRICE_REFRESH_PROFIT')).toBe('Running')
    expect(textOf(wrapper, 'sync-state-PRICE_REFRESH_DISCOVERY')).toBe('Failed')
    expect(textOf(wrapper, 'sync-state-ACCOUNT_SYNC')).toBe('Not started')
    expect(exists(wrapper, 'sync-task-failure-PRICE_REFRESH_PROFIT')).toBe(false)
  })

  it('ignoresADuplicateClickWhileTheSameOperationIsUnfinished', async () => {
    const api = new FakeSyncApi()
    const slowSubmission = deferred<SyncTaskAccepted>()
    api.submitHandler = () => slowSubmission.promise
    api.statusHandler = (statusUrl) => Promise.resolve(statusOf(statusUrl, 'RUNNING'))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    expect(isDisabled(wrapper, 'sync-trigger-ACCOUNT_SYNC')).toBe(true)
    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    expect(api.submissionCount('ACCOUNT_SYNC')).toBe(1)

    slowSubmission.resolve(acceptance('ACCOUNT_SYNC'))
    await settle()

    // Accepted and still running: a further trigger of the same operation stays suppressed.
    expect(isDisabled(wrapper, 'sync-trigger-ACCOUNT_SYNC')).toBe(true)
    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    expect(api.submissionCount('ACCOUNT_SYNC')).toBe(1)
  })

  it('triggersAnIndependentOperationWhileAnotherIsStillRunning', async () => {
    const api = new FakeSyncApi()
    api.statusHandler = (statusUrl) => Promise.resolve(statusOf(statusUrl, 'RUNNING'))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    expect(isDisabled(wrapper, 'sync-trigger-GLOBAL_SYNC')).toBe(false)

    await click(wrapper, 'sync-trigger-GLOBAL_SYNC')

    expect(api.submissions).toEqual(['ACCOUNT_SYNC', 'GLOBAL_SYNC'])
    expect(textOf(wrapper, 'sync-state-ACCOUNT_SYNC')).toBe('Running')
    expect(textOf(wrapper, 'sync-state-GLOBAL_SYNC')).toBe('Running')
  })

  it('showsABackendRejectionWithoutQueueingOrResubmitting', async () => {
    const api = new FakeSyncApi()
    api.submitHandler = () =>
      Promise.reject(
        new ApiRequestError(
          'A GLOBAL_SYNC task is already running (task 1c3f).',
          'SYNC_ALREADY_RUNNING',
          409
        )
      )
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-GLOBAL_SYNC')

    const rejection = textOf(wrapper, 'sync-submission-error-GLOBAL_SYNC')
    expect(rejection).toContain('did not start this operation')
    expect(rejection).toContain('SYNC_ALREADY_RUNNING')
    expect(textOf(wrapper, 'sync-state-GLOBAL_SYNC')).toBe('Not accepted')
    expect(exists(wrapper, 'sync-diagnostics-GLOBAL_SYNC')).toBe(false)
    // No task of this client's exists, so nothing is polled and nothing is sent again.
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 10)
    expect(api.statusRequests).toEqual([])
    expect(api.submissionCount('GLOBAL_SYNC')).toBe(1)
  })

  it('reportsAnUnansweredTriggerAsAnUnknownAdmissionAndNeverRetriesIt', async () => {
    const api = new FakeSyncApi()
    api.submitHandler = () =>
      Promise.reject(new ApiRequestError('The backend could not be reached.', UNREACHABLE_CODE, null))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')

    const message = textOf(wrapper, 'sync-submission-error-ACCOUNT_SYNC')
    expect(message).toContain(UNREACHABLE_CODE)
    expect(message).toContain('whether the backend started this operation is unknown')
    // An unknown admission is not a refusal: the state must not read as "not accepted".
    expect(textOf(wrapper, 'sync-state-ACCOUNT_SYNC')).toBe('Outcome not established')

    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 10)
    expect(api.submissionCount('ACCOUNT_SYNC')).toBe(1)
    expect(api.statusRequests).toEqual([])
  })

  it('distinguishesAStatusLookupFailureFromATaskFailureAndOffersARetry', async () => {
    const api = new FakeSyncApi()
    api.statusHandler = () =>
      Promise.reject(new ApiRequestError('The backend could not be reached.', UNREACHABLE_CODE, null))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')

    const lookupError = textOf(wrapper, 'sync-lookup-error-ACCOUNT_SYNC')
    expect(lookupError).toContain(UNREACHABLE_CODE)
    expect(lookupError).toContain('outcome is not established')
    expect(exists(wrapper, 'sync-task-failure-ACCOUNT_SYNC')).toBe(false)
    expect(exists(wrapper, 'sync-submission-error-ACCOUNT_SYNC')).toBe(false)
    expect(exists(wrapper, 'sync-status-retry-ACCOUNT_SYNC')).toBe(true)

    // Stopped, not retried on its own.
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 10)
    expect(api.statusRequestCount('ACCOUNT_SYNC')).toBe(1)
  })

  it('retriesTheStatusLookupWithoutRepeatingTheTrigger', async () => {
    const api = new FakeSyncApi()
    api.statusHandler = (statusUrl, callIndex) =>
      callIndex === 0
        ? Promise.reject(new ApiRequestError('unreachable', UNREACHABLE_CODE, null))
        : Promise.resolve(statusOf(statusUrl, 'SUCCEEDED'))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    await click(wrapper, 'sync-status-retry-ACCOUNT_SYNC')

    expect(api.submissionCount('ACCOUNT_SYNC')).toBe(1)
    expect(api.statusRequestCount('ACCOUNT_SYNC')).toBe(2)
    expect(textOf(wrapper, 'sync-state-ACCOUNT_SYNC')).toBe('Completed')
    expect(exists(wrapper, 'sync-lookup-error-ACCOUNT_SYNC')).toBe(false)
  })

  it('stopsAtAMissingTaskWithoutClaimingAnOutcomeOrOfferingARetry', async () => {
    const api = new FakeSyncApi()
    api.statusHandler = () =>
      Promise.reject(new ApiRequestError('No such task: task-ACCOUNT_SYNC.', 'TASK_NOT_FOUND', 404))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')

    const lookupError = textOf(wrapper, 'sync-lookup-error-ACCOUNT_SYNC')
    expect(lookupError).toContain('TASK_NOT_FOUND')
    expect(lookupError).toContain('neither success, nor failure, nor a rollback')
    expect(exists(wrapper, 'sync-status-retry-ACCOUNT_SYNC')).toBe(false)
    expect(exists(wrapper, 'sync-task-failure-ACCOUNT_SYNC')).toBe(false)
    expect(textOf(wrapper, 'sync-state-ACCOUNT_SYNC')).toBe('Outcome not established')

    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 10)
    expect(api.statusRequestCount('ACCOUNT_SYNC')).toBe(1)
  })

  it('neverOverlapsTwoStatusRequestsForTheSameTask', async () => {
    const api = new FakeSyncApi()
    const slowLookup = deferred<SyncTaskStatus>()
    api.statusHandler = (statusUrl, callIndex) =>
      callIndex === 0 ? slowLookup.promise : Promise.resolve(statusOf(statusUrl, 'SUCCEEDED'))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 20)

    // The first lookup has not answered, so no second one was ever issued.
    expect(api.statusRequestCount('ACCOUNT_SYNC')).toBe(1)

    slowLookup.resolve(statusOf(acceptance('ACCOUNT_SYNC').statusUrl, 'RUNNING'))
    await settle()
    await advanceToNextPoll()

    expect(api.statusRequestCount('ACCOUNT_SYNC')).toBe(2)
    expect(textOf(wrapper, 'sync-state-ACCOUNT_SYNC')).toBe('Completed')
  })

  it('keepsALateAnswerInItsOwnOperationWhenTwoRunOutOfOrder', async () => {
    const api = new FakeSyncApi()
    const slowAccountLookup = deferred<SyncTaskStatus>()
    api.statusHandler = (statusUrl) =>
      statusUrl === acceptance('ACCOUNT_SYNC').statusUrl
        ? slowAccountLookup.promise
        : Promise.resolve(statusOf(statusUrl, 'FAILED', SYNC_FAILURE))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    await click(wrapper, 'sync-trigger-GLOBAL_SYNC')
    expect(textOf(wrapper, 'sync-state-GLOBAL_SYNC')).toBe('Failed')

    slowAccountLookup.resolve(statusOf(acceptance('ACCOUNT_SYNC').statusUrl, 'SUCCEEDED'))
    await settle()

    expect(textOf(wrapper, 'sync-state-ACCOUNT_SYNC')).toBe('Completed')
    expect(textOf(wrapper, 'sync-state-GLOBAL_SYNC')).toBe('Failed')
    expect(exists(wrapper, 'sync-task-failure-ACCOUNT_SYNC')).toBe(false)
    expect(exists(wrapper, 'sync-task-failure-GLOBAL_SYNC')).toBe(true)
  })

  it('stopsPollingWhenTheTrackingStateIsDisposed', async () => {
    const api = new FakeSyncApi()
    api.statusHandler = (statusUrl) => Promise.resolve(statusOf(statusUrl, 'RUNNING'))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    expect(api.statusRequestCount('ACCOUNT_SYNC')).toBe(1)

    // The owning scope going away — not merely leaving the page, which keeps tracking (App.spec).
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * 20)

    expect(api.statusRequestCount('ACCOUNT_SYNC')).toBe(1)
  })

  it('boundsPollingAndReportsThatTheOutcomeWasNotEstablished', async () => {
    const api = new FakeSyncApi()
    api.statusHandler = (statusUrl) => Promise.resolve(statusOf(statusUrl, 'RUNNING'))
    const wrapper = mountControls(api)

    await click(wrapper, 'sync-trigger-ACCOUNT_SYNC')
    await vi.advanceTimersByTimeAsync(STATUS_POLL_INTERVAL_MS * (MAX_STATUS_POLLS + 5))
    await settle()

    expect(api.statusRequestCount('ACCOUNT_SYNC')).toBe(MAX_STATUS_POLLS)
    const lookupError = textOf(wrapper, 'sync-lookup-error-ACCOUNT_SYNC')
    expect(lookupError).toContain('stopped polling')
    // The last state the backend did report stays visible, and is not claimed as the outcome.
    const state = textOf(wrapper, 'sync-state-ACCOUNT_SYNC')
    expect(state).toContain('Running')
    expect(state).toContain('outcome not established')
    expect(exists(wrapper, 'sync-status-retry-ACCOUNT_SYNC')).toBe(true)
  })
})
