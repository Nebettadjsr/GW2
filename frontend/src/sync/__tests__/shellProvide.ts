import { computed } from 'vue'
import { SYNC_OPERATIONS_KEY, type SharedSyncOperations } from '../provideSyncOperations'
import { useSyncOperations } from '../useSyncOperations'
import { FakeSyncApi } from './syncFixtures'

/**
 * The application shell's shared synchronization context for tests that mount a single screen:
 * the same injection key and state the shell provides, backed by a controlled sync API.
 */
export function shellProvide(syncApi = new FakeSyncApi()): Record<symbol, SharedSyncOperations> {
  const state = useSyncOperations(syncApi)
  const shared: SharedSyncOperations = {
    ...state,
    trackedTaskCount: computed(() => state.operations.filter((o) => o.isTracking).length)
  }
  return { [SYNC_OPERATIONS_KEY as symbol]: shared }
}
