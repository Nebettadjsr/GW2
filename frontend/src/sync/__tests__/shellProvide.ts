import { afterEach } from 'vitest'
import { computed, effectScope, type EffectScope } from 'vue'
import { SYNC_OPERATIONS_KEY, type SharedSyncOperations } from '../provideSyncOperations'
import { useSyncOperations } from '../useSyncOperations'
import { FakeSyncApi } from './syncFixtures'

const scopes: EffectScope[] = []

// Every shell created by a test is owned by its own effect scope and stopped after that test, so
// its status timers cannot outlive it or leak into the next test.
afterEach(() => { scopes.splice(0).forEach((scope) => scope.stop()) })

/**
 * The application shell's shared synchronization context for tests that mount a single screen:
 * the same injection key and state the shell provides, backed by a controlled sync API.
 */
export function shellProvide(syncApi = new FakeSyncApi()): Record<symbol, SharedSyncOperations> {
  const scope = effectScope(true)
  scopes.push(scope)
  const shared = scope.run((): SharedSyncOperations => {
    const state = useSyncOperations(syncApi)
    return {
      ...state,
      trackedTaskCount: computed(() => state.operations.filter((o) => o.isTracking).length)
    }
  })!
  return { [SYNC_OPERATIONS_KEY as symbol]: shared }
}
