/**
 * Request state for the Ectoplasm Salvage calculation (`CURRENT_ARCHITECTURE.md` 5.15).
 *
 * It holds what the backend last answered and which of the three presentable situations the screen
 * is in. It decides nothing about salvage economics: no price, profit, net cost, yield or Luck cost
 * is derived, rounded or defaulted here — the response is kept as received and handed to the view.
 *
 * Close to `useAccountRead`, with one addition the account screens do not need: this calculation
 * costs the backend a live Trading Post request, so a reload asked for while one is already in
 * flight is *suppressed* rather than superseded. Nothing is retried automatically either; a failed
 * calculation waits for the user.
 */
import { onScopeDispose, ref, shallowRef, type Ref } from 'vue'
import { ApiRequestError } from '@/api/http'
import type { EctoSalvage } from '@/api/types'

/**
 * `loading` and `failed` are kept apart from `loaded` on purpose: a failed calculation must never be
 * presentable as a result, available or unavailable. The initial value is `loading` because the
 * screen issues its calculation on mount and nothing is rendered before that.
 */
export type EctoSalvagePhase = 'loading' | 'loaded' | 'failed'

/** The backend's own sanitized failure, or a transport failure this client names itself. */
export interface EctoSalvageFailure {
  code: string
  message: string
}

export interface EctoSalvageState {
  phase: Ref<EctoSalvagePhase>
  /** The last successful answer, or null. Never holds data while `phase` is not `loaded`. */
  data: Ref<EctoSalvage | null>
  failure: Ref<EctoSalvageFailure | null>
  /**
   * Requests one fresh calculation. Does nothing while one is already in flight, so a double click
   * or a repeated key press cannot put two live price lookups on the backend for one screen.
   */
  load(): Promise<void>
}

export function useEctoSalvage(calculate: () => Promise<EctoSalvage>): EctoSalvageState {
  const phase = ref<EctoSalvagePhase>('loading')
  const data = shallowRef<EctoSalvage | null>(null)
  const failure = ref<EctoSalvageFailure | null>(null)

  /** Identifies the newest calculation; an answer carrying a superseded id is discarded. */
  let newestRequestId = 0
  let inFlight = false
  let isDisposed = false

  async function load(): Promise<void> {
    if (inFlight) return

    const requestId = ++newestRequestId
    inFlight = true
    phase.value = 'loading'
    failure.value = null
    // Dropped while the new calculation is in flight, so a failure cannot leave the previous answer
    // on screen as if it had just been calculated, and a reload cannot show stale prices as current.
    data.value = null

    try {
      const calculated = await calculate()
      if (isStale(requestId)) return
      data.value = calculated
      phase.value = 'loaded'
    } catch (cause) {
      if (isStale(requestId)) return
      failure.value = describeFailure(cause)
      phase.value = 'failed'
    } finally {
      // Only the newest request may reopen the gate: a late answer must not admit a reload that
      // would then race an answer still on its way.
      if (requestId === newestRequestId) inFlight = false
    }
  }

  /** True once the owning screen is gone, or once a newer calculation has superseded this answer. */
  function isStale(requestId: number): boolean {
    return isDisposed || requestId !== newestRequestId
  }

  onScopeDispose(() => {
    isDisposed = true
  })

  return { phase, data, failure, load }
}

function describeFailure(cause: unknown): EctoSalvageFailure {
  if (cause instanceof ApiRequestError) return { code: cause.code, message: cause.message }
  return { code: 'CLIENT_ERROR', message: cause instanceof Error ? cause.message : String(cause) }
}
