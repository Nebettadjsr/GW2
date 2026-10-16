import { computed, ref, shallowRef, type ComputedRef, type Ref } from 'vue'
import type { CraftingApi } from '@/api/craftingApi'
import { ApiRequestError } from '@/api/http'
import {
  PROFIT_STATUS_PENDING,
  type CalculationFreshness,
  type CraftingProfitRequest,
  type CraftingProfitResponse,
  type CraftingRow,
  type EffectiveScope,
  type EffectiveSettings,
  type OutstandingRefresh,
  type SyncTaskStatus
} from '@/api/types'
import { buildScopeOptions, defaultScopeOptionId, type ScopeOption } from './scopeOptions'

/**
 * Screen state for Crafting Profit: the selector facts, the current scope/settings selection, the
 * backend's answer for that selection, and the bounded automatic recovery that replaces a
 * provisional answer with the final one (UD-015, `TARGET_ARCHITECTURE.md` 9.1).
 *
 * It decides nothing about crafting and nothing about freshness. Every displayed number, state and
 * default comes from a backend response — including the initial settings, which are read from the
 * first response's echo instead of being repeated here — and whether a result is provisional,
 * which refresh work is outstanding and which task to follow are all read from the response's
 * freshness envelope rather than inferred.
 */

/** One overall budget for the whole automatic recovery cycle (UD-015 item 6, AC4). */
export const RECOVERY_BUDGET_MS = 5 * 60_000

/** How long to wait between two reads of an advertised task's status resource. */
export const RECOVERY_POLL_INTERVAL_MS = 1_000

/** Wording for the one case where recovery ran out of its own budget. */
export const RECOVERY_TIMED_OUT =
  'The automatic refresh did not finish within five minutes. It may still be running in the backend.'

/** Wording for a task reference the backend can no longer resolve. */
export const RECOVERY_TASK_UNKNOWN =
  'The refresh task the backend advertised is no longer known to it.'

/** Wording for a provisional answer that advertised no refresh work to follow at all. */
export const RECOVERY_NOTHING_ADMITTED =
  'The backend reported the inputs as not current but admitted no refresh work for them.'

/**
 * A one-shot timer the recovery budget runs on, cancelled when the cycle ends before it.
 *
 * It is what makes the budget independent of the requests in flight: checks between awaits can
 * only fire once a request answered, so a task-status read or a recalculation that never answers
 * would otherwise keep recovery animating indefinitely (AC4).
 */
export interface RecoveryAlarm {
  /** Resolves once the requested time has passed; stays pending forever after `cancel()`. */
  expired: Promise<void>
  cancel(): void
}

/** The browser timer. Used whenever no alarm port is injected. */
export function timerAlarm(milliseconds: number): RecoveryAlarm {
  let handle = 0
  const expired = new Promise<void>((resolve) => {
    handle = window.setTimeout(resolve, milliseconds)
  })
  return { expired, cancel: () => window.clearTimeout(handle) }
}

/**
 * Ports the recovery cycle needs: the advertised task-status read, and the clock, the waiting and
 * the budget timer it is bounded by. Injected so a test drives the five-minute budget
 * deterministically — including while a request it never answers is pending — instead of waiting
 * for it.
 */
export interface ProfitRefreshPorts {
  readTaskStatus(statusUrl: string): Promise<SyncTaskStatus>
  now?(): number
  sleep?(milliseconds: number): Promise<void>
  alarm?(milliseconds: number): RecoveryAlarm
}

export interface CraftingProfitState {
  scopeOptions: ComputedRef<ScopeOption[]>
  selectedScopeId: Ref<string | null>
  settings: Ref<EffectiveSettings | null>
  rows: Ref<readonly CraftingRow[]>
  effectiveScope: Ref<EffectiveScope | null>
  isLoading: Ref<boolean>
  hasResult: Ref<boolean>
  requestError: Ref<string | null>
  selectorError: Ref<string | null>

  /** The inputs the displayed result was calculated from, exactly as the backend reported them. */
  freshness: Ref<CalculationFreshness | null>
  /** The backend had no usable synchronized inputs, so there is no result and no fabricated row. */
  isPending: Ref<boolean>
  /** A result is displayed and its inputs are not confirmed current: the stale-data case. */
  isProvisional: ComputedRef<boolean>
  /** The automatic recovery cycle is running; this is what the loading animation follows. */
  isRecovering: Ref<boolean>
  /** Why recovery stopped without reaching a final result; null while it may still get there. */
  recoveryFailure: Ref<string | null>

  open(): Promise<void>
  reload(): Promise<void>
  selectScope(scopeOptionId: string): Promise<void>
  applySettings(settings: EffectiveSettings): Promise<void>
  /** Starts one new bounded recovery cycle for the current selection. */
  retryRefresh(): Promise<void>
}

/** What one calculation request did, told apart so a late or failed answer cannot be mistaken. */
type RequestOutcome =
  | { kind: 'applied'; response: CraftingProfitResponse }
  | { kind: 'superseded' }
  | { kind: 'failed'; message: string }

/** Whether a wait inside the recovery cycle may continue, was abandoned, or used up the budget. */
type Tick = 'continue' | 'abandoned' | 'expired'

type TaskOutcome =
  | { kind: 'succeeded' }
  | { kind: 'abandoned' }
  | { kind: 'expired' }
  | { kind: 'failed'; message: string }

export function useCraftingProfit(api: CraftingApi, refresh: ProfitRefreshPorts): CraftingProfitState {
  const scopeOptions = shallowRef<ScopeOption[]>([])
  const selectedScopeId = ref<string | null>(null)
  const settings = ref<EffectiveSettings | null>(null)
  const rows = shallowRef<readonly CraftingRow[]>([])
  const effectiveScope = ref<EffectiveScope | null>(null)
  const isLoading = ref(false)
  const hasResult = ref(false)
  const requestError = ref<string | null>(null)
  const selectorError = ref<string | null>(null)
  const freshness = shallowRef<CalculationFreshness | null>(null)
  const isPending = ref(false)
  const isRecovering = ref(false)
  const recoveryFailure = ref<string | null>(null)

  const now = refresh.now ?? (() => Date.now())
  const sleep =
    refresh.sleep ?? ((milliseconds: number) => new Promise<void>((resolve) => window.setTimeout(resolve, milliseconds)))
  const alarm = refresh.alarm ?? timerAlarm

  /**
   * Identifies the newest requested calculation, whatever asked for it. A response whose id is no
   * longer the newest belongs to a superseded request and is discarded, so neither a slow earlier
   * selection nor a recovery resubmission can overwrite a newer context's results.
   */
  let newestRequestId = 0

  /**
   * The only recovery cycle whose steps may still act. Every event that invalidates a cycle — a new
   * foreground request, an explicit retry — increments it, so a cycle still mid-poll stops at its
   * next checkpoint instead of writing into a context that moved on.
   */
  let activeCycle = 0

  const selectedScope = computed<ScopeOption | null>(
    () => scopeOptions.value.find((option) => option.id === selectedScopeId.value) ?? null
  )

  const isProvisional = computed(
    () => hasResult.value && freshness.value !== null && freshness.value.provisional
  )

  async function loadSelectorOptions(): Promise<void> {
    selectorError.value = null
    try {
      const loaded = await api.loadSelectorOptions()
      const options = buildScopeOptions(loaded)
      scopeOptions.value = options
      // A selection the backend no longer offers falls back to its documented default entry.
      if (!options.some((option) => option.id === selectedScopeId.value)) {
        selectedScopeId.value = defaultScopeOptionId(loaded, options)
      }
    } catch (cause) {
      selectorError.value = describeFailure(cause)
    }
  }

  /**
   * One calculation request. `foreground` is what the user asked for and owns the loading state; a
   * background request is a recovery resubmission and must leave the displayed cached result and
   * its controls alone until its own answer arrives.
   *
   * `cycle`, where a recovery cycle owns the request, is the second correlation: an answer that
   * arrives after that cycle was abandoned — or after its budget ran out while the request was
   * still pending — is discarded instead of applied. Nothing is assumed about the backend having
   * stopped; its answer simply can no longer change what is displayed.
   */
  async function sendProfit(
    request: CraftingProfitRequest,
    foreground: boolean,
    cycle?: number
  ): Promise<RequestOutcome> {
    const requestId = ++newestRequestId
    if (foreground) isLoading.value = true

    try {
      const response = await api.calculateProfit(request)
      if (requestId !== newestRequestId) return { kind: 'superseded' }
      if (cycle !== undefined && cycle !== activeCycle) return { kind: 'superseded' }
      apply(response)
      return { kind: 'applied', response }
    } catch (cause) {
      if (requestId !== newestRequestId) return { kind: 'superseded' }
      if (cycle !== undefined && cycle !== activeCycle) return { kind: 'superseded' }
      return { kind: 'failed', message: describeFailure(cause) }
    } finally {
      if (foreground && requestId === newestRequestId) isLoading.value = false
    }
  }

  /** A request the user asked for: it abandons any recovery cycle belonging to the old context. */
  async function requestProfit(request: CraftingProfitRequest): Promise<void> {
    abandonRecovery()
    recoveryFailure.value = null
    requestError.value = null

    const outcome = await sendProfit(request, true)
    if (outcome.kind === 'superseded') return

    if (outcome.kind === 'failed') {
      requestError.value = outcome.message
      // Cleared rather than kept: an earlier selection's rows are not this selection's answer.
      rows.value = []
      hasResult.value = false
      isPending.value = false
      freshness.value = null
      return
    }

    startRecoveryIfNeeded(outcome.response)
  }

  function apply(response: CraftingProfitResponse): void {
    freshness.value = response.freshness ?? null
    effectiveScope.value = response.scope
    settings.value = response.settings

    // A pending answer has no usable inputs behind it. Nothing is invented from it: no row, no
    // count and no empty result that would read as "nothing is craftable".
    if (response.status === PROFIT_STATUS_PENDING || response.rows === null) {
      rows.value = []
      hasResult.value = false
      isPending.value = true
      return
    }

    rows.value = response.rows
    hasResult.value = true
    isPending.value = false
  }

  function abandonRecovery(): void {
    activeCycle += 1
    isRecovering.value = false
  }

  function startRecoveryIfNeeded(response: CraftingProfitResponse): void {
    if (response.freshness === null || response.freshness === undefined) return
    if (!response.freshness.provisional) return
    // Not awaited: the provisional result is already on screen and recovery runs behind it.
    void runRecovery()
  }

  /**
   * One bounded recovery cycle. The deadline is taken once, here, and covers everything the cycle
   * does — polling, following successive tasks and recalculating — so admitting further work never
   * extends it (AC4).
   *
   * It is enforced two ways, because one of them is not enough. The checks between awaits end the
   * cycle promptly once a request answered; the alarm ends it while one has not, which is the only
   * thing that bounds a task-status read or a recalculation that never answers at all.
   *
   * `resubmitFirst` is the explicit-retry entry: its own calculation request is the cycle's first
   * step rather than something that happens before the budget starts.
   */
  function runRecovery(resubmitFirst = false): Promise<void> {
    const cycle = ++activeCycle
    const deadlineAt = now() + RECOVERY_BUDGET_MS
    isRecovering.value = true
    recoveryFailure.value = null

    let settled = false
    const budget = alarm(RECOVERY_BUDGET_MS)
    void budget.expired.then(() => {
      if (!settled) expire(cycle)
    })

    return recover(cycle, deadlineAt, resubmitFirst).finally(() => {
      settled = true
      budget.cancel()
      if (cycle === activeCycle) isRecovering.value = false
    })
  }

  /**
   * The budget ran out while the cycle was still waiting for an answer. The animation stops, the
   * reason and its retry appear and the rows on screen stay — and the cycle is abandoned, so
   * whatever it is still awaiting can no longer write into the page when it eventually answers.
   * Nothing is cancelled in the backend and nothing assumes it was.
   */
  function expire(cycle: number): void {
    if (cycle !== activeCycle) return
    recoveryFailure.value = RECOVERY_TIMED_OUT
    abandonRecovery()
  }

  async function recover(cycle: number, deadlineAt: number, resubmitFirst: boolean): Promise<void> {
    /**
     * The first round acts at once — the result on screen is already stale and the work is already
     * admitted. Every later round waits one interval first, which is what bounds the request rate:
     * a backend that keeps admitting fresh work, or answers a task as finished immediately while
     * its sources stay stale, would otherwise turn recovery into an unpaced resubmission loop. The
     * wait is inside the same budget, so pacing cannot extend the five minutes either.
     */
    let firstRound = true

    if (resubmitFirst) {
      const asked = await sendProfit(currentRequest(), false, cycle)
      if (asked.kind === 'superseded') return
      if (asked.kind === 'failed') return stop(cycle, asked.message)
    }

    while (true) {
      if (cycle !== activeCycle) return

      const envelope = freshness.value
      // Only a calculation for the current context that the backend itself reports as final ends
      // recovery. A succeeded task never does.
      if (envelope === null || !envelope.provisional) return

      if (now() >= deadlineAt) return stop(cycle, RECOVERY_TIMED_OUT)

      if (!firstRound) {
        const waited = await tick(cycle, deadlineAt)
        if (waited === 'abandoned') return
        if (waited === 'expired') return stop(cycle, RECOVERY_TIMED_OUT)
      }
      firstRound = false

      const followable = envelope.outstandingWork.filter(isFollowable)

      if (followable.length === 0) {
        const reported = firstFailureOf(envelope)
        if (reported !== null) return stop(cycle, reported)
        if (envelope.outstandingWork.some((work) => work.taskState === 'UNKNOWN')) {
          return stop(cycle, RECOVERY_TASK_UNKNOWN)
        }
        if (envelope.outstandingWork.length === 0) return stop(cycle, RECOVERY_NOTHING_ADMITTED)

        // Work is outstanding but has no task to follow yet — another entry point holds the
        // mechanism. There is nothing to poll, so this round falls through to asking the
        // calculation again; the next round's wait is what keeps that paced.
      }

      for (const work of followable) {
        const outcome = await awaitTask(cycle, work, deadlineAt)
        if (outcome.kind === 'abandoned') return
        if (outcome.kind === 'expired') return stop(cycle, RECOVERY_TIMED_OUT)
        if (outcome.kind === 'failed') return stop(cycle, outcome.message)
      }

      if (cycle !== activeCycle) return

      // Task success is not evidence that the required sources are fresh: ask again with the
      // settings currently in effect and let the backend's own checks decide.
      const resubmitted = await sendProfit(currentRequest(), false, cycle)
      if (resubmitted.kind === 'superseded') return
      if (resubmitted.kind === 'failed') return stop(cycle, resubmitted.message)
    }
  }

  async function awaitTask(
    cycle: number,
    work: OutstandingRefresh,
    deadlineAt: number
  ): Promise<TaskOutcome> {
    const statusUrl = work.taskStatusUrl
    if (statusUrl === null) return { kind: 'succeeded' }

    while (true) {
      if (cycle !== activeCycle) return { kind: 'abandoned' }
      if (now() >= deadlineAt) return { kind: 'expired' }

      let status: SyncTaskStatus
      try {
        status = await refresh.readTaskStatus(statusUrl)
      } catch (cause) {
        return { kind: 'failed', message: describeFailure(cause) }
      }

      if (cycle !== activeCycle) return { kind: 'abandoned' }
      if (status.state === 'SUCCEEDED') return { kind: 'succeeded' }
      if (status.state === 'FAILED') {
        const failure = status.failure
        return {
          kind: 'failed',
          message:
            failure === null || failure === undefined
              ? 'The refresh task failed.'
              : `${failure.error}: ${failure.message}`
        }
      }

      const waited = await tick(cycle, deadlineAt)
      if (waited === 'abandoned') return { kind: 'abandoned' }
      if (waited === 'expired') return { kind: 'expired' }
    }
  }

  async function tick(cycle: number, deadlineAt: number): Promise<Tick> {
    await sleep(RECOVERY_POLL_INTERVAL_MS)
    if (cycle !== activeCycle) return 'abandoned'
    return now() < deadlineAt ? 'continue' : 'expired'
  }

  /**
   * Ends recovery without touching the result on screen: the last usable rows stay, the animation
   * stops, and the reason is offered with a retry.
   */
  function stop(cycle: number, message: string): void {
    if (cycle !== activeCycle) return
    recoveryFailure.value = message
  }

  /** The current selection as a request body; omitted parts ask the backend for its own defaults. */
  function currentRequest(): CraftingProfitRequest {
    const request: CraftingProfitRequest = {}
    const scope = selectedScope.value
    if (scope !== null) request.scope = scope.request
    if (settings.value !== null) request.settings = settings.value
    return request
  }

  return {
    scopeOptions: computed(() => scopeOptions.value),
    selectedScopeId,
    settings,
    rows,
    effectiveScope,
    isLoading,
    hasResult,
    requestError,
    selectorError,
    freshness,
    isPending,
    isProvisional,
    isRecovering,
    recoveryFailure,

    /**
     * Opening the screen loads the selector facts and asks for the backend's default calculation.
     * The two run together so a selector failure still leaves a usable default result, and the
     * first request carries no scope or settings at all — the defaults applied are the backend's.
     */
    async open(): Promise<void> {
      await Promise.all([loadSelectorOptions(), requestProfit({})])
    },

    /** Manual reload. Keeps the current scope and settings; search and sort are not touched. */
    async reload(): Promise<void> {
      await loadSelectorOptions()
      await requestProfit(currentRequest())
    },

    async selectScope(scopeOptionId: string): Promise<void> {
      selectedScopeId.value = scopeOptionId
      await requestProfit(currentRequest())
    },

    async applySettings(next: EffectiveSettings): Promise<void> {
      settings.value = next
      await requestProfit(currentRequest())
    },

    /**
     * An explicit retry after recovery stopped: one new bounded cycle whose first step asks for the
     * calculation again, which is what makes the backend recheck its own freshness and admit what
     * is still needed. The retry's own request is inside the new budget, so a recalculation that
     * never answers ends the same bounded way instead of leaving the page waiting silently. It
     * starts no refresh of its own, so no second account writer can appear, and it assumes nothing
     * about whether earlier work was cancelled.
     */
    async retryRefresh(): Promise<void> {
      abandonRecovery()
      await runRecovery(true)
    }
  }
}

/**
 * Whether this outstanding work names a task whose status resource can be read. A task the backend
 * reports as `UNKNOWN`, or work it admitted without a task at all, is not something to poll.
 */
function isFollowable(work: OutstandingRefresh): boolean {
  return work.taskStatusUrl !== null && work.taskState !== 'UNKNOWN' && work.taskState !== 'NONE'
}

/** The backend's own bounded recovery item, where it supplied one. */
function firstFailureOf(envelope: CalculationFreshness): string | null {
  const failure = envelope.failures.at(0)
  return failure === undefined ? null : `${failure.code}: ${failure.message}`
}

function describeFailure(cause: unknown): string {
  if (cause instanceof ApiRequestError) return `${cause.code}: ${cause.message}`
  return cause instanceof Error ? cause.message : String(cause)
}
