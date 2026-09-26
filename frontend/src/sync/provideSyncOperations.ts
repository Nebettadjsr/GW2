/**
 * Where the synchronization tracking state lives: in the application shell, not in the page that
 * shows it (`FRONTEND_UX_GUIDELINES.md` 2).
 *
 * The shell creates exactly one instance and provides it; the synchronization page injects that
 * instance instead of creating its own. Leaving the page and coming back therefore finds the same
 * unfinished tasks still tracked, with no second trigger and no second polling loop, while the
 * timers are still cleared when the tracking itself is disposed with the shell.
 *
 * Nothing is stored outside the running page, so a browser reload has no tracked task to restore —
 * which is what the page says, rather than implying a persistence that does not exist.
 */
import { computed, inject, provide, type ComputedRef, type InjectionKey } from 'vue'
import { syncApi, type SyncApi } from '@/api/syncApi'
import { useSyncOperations, type SyncOperationsState } from './useSyncOperations'

export interface SharedSyncOperations extends SyncOperationsState {
  /** How many operations are still tracking an unfinished task of their own. */
  readonly trackedTaskCount: ComputedRef<number>
}

const SYNC_OPERATIONS_KEY: InjectionKey<SharedSyncOperations> = Symbol('syncOperations')

/** Called once, by the shell. The returned state is disposed with the calling scope. */
export function provideSyncOperations(api: SyncApi = syncApi): SharedSyncOperations {
  const state = useSyncOperations(api)
  const shared: SharedSyncOperations = {
    ...state,
    trackedTaskCount: computed(
      () => state.operations.filter((operation) => operation.isTracking).length
    )
  }
  provide(SYNC_OPERATIONS_KEY, shared)
  return shared
}

/**
 * The shell's tracking state. Missing provision is an error rather than a silent second instance:
 * that fallback would look like it worked and would quietly lose a task on the next navigation.
 */
export function useSharedSyncOperations(): SharedSyncOperations {
  const shared = inject(SYNC_OPERATIONS_KEY, null)
  if (shared === null) {
    throw new Error('Synchronization tracking must be provided by the application shell.')
  }
  return shared
}
