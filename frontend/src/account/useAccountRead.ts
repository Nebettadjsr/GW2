/**
 * Read state for one account-read route (`CURRENT_ARCHITECTURE.md` 5.12).
 *
 * It holds what the backend last answered and which of the four presentable situations the screen
 * is in. It decides nothing about inventory: no slot, stack, category, count or label is derived,
 * reordered or defaulted here — the response is kept as received and handed to the view.
 */
import { onScopeDispose, ref, shallowRef, type Ref } from 'vue'
import { ApiRequestError } from '@/api/http'

/**
 * `loading` and `failed` are kept apart from `loaded` on purpose: a failed read must never be
 * presentable as an inventory, empty or otherwise. The initial value is `loading` because both
 * screens issue their read on mount and nothing is rendered before that.
 */
export type AccountReadPhase = 'loading' | 'loaded' | 'failed'

/** The backend's own sanitized failure, or a transport failure this client names itself. */
export interface AccountReadFailure {
  code: string
  message: string
}

export interface AccountReadState<T> {
  phase: Ref<AccountReadPhase>
  /** The last successful response, or null. Never holds data while `phase` is not `loaded`. */
  data: Ref<T | null>
  failure: Ref<AccountReadFailure | null>
  /** Repeats this one read. Nothing else is requested and nothing is synchronized. */
  load(): Promise<void>
}

export function useAccountRead<T>(read: () => Promise<T>): AccountReadState<T> {
  const phase = ref<AccountReadPhase>('loading')
  const data = shallowRef<T | null>(null)
  const failure = ref<AccountReadFailure | null>(null)

  /** Identifies the newest read; an answer carrying a superseded id is discarded. */
  let newestRequestId = 0
  let isDisposed = false

  async function load(): Promise<void> {
    const requestId = ++newestRequestId
    phase.value = 'loading'
    failure.value = null
    // Dropped while the new read is in flight, so a failure cannot leave the previous answer on
    // screen as if it had just been loaded, and a reload cannot show stale data as current.
    data.value = null

    try {
      const loaded = await read()
      if (isStale(requestId)) return
      data.value = loaded
      phase.value = 'loaded'
    } catch (cause) {
      if (isStale(requestId)) return
      failure.value = describeFailure(cause)
      phase.value = 'failed'
    }
  }

  /** True once the owning screen is gone, or once a newer read has superseded this answer. */
  function isStale(requestId: number): boolean {
    return isDisposed || requestId !== newestRequestId
  }

  onScopeDispose(() => {
    isDisposed = true
  })

  return { phase, data, failure, load }
}

function describeFailure(cause: unknown): AccountReadFailure {
  if (cause instanceof ApiRequestError) return { code: cause.code, message: cause.message }
  return { code: 'CLIENT_ERROR', message: cause instanceof Error ? cause.message : String(cause) }
}
