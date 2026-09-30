import type { SyncApi } from '@/api/syncApi'
import type { SyncTaskAccepted } from '@/api/types'

/**
 * Operations available from System Status. Price refreshes are requested by the calculation that
 * needs them and use the same shared backend cache; they are not separate admin actions.
 */
export type SyncOperationKey = 'ACCOUNT_SYNC' | 'GLOBAL_SYNC'

export interface SyncOperation {
  key: SyncOperationKey
  /**
   * What the user reads on the button: what this operation does, in their terms, distinguishing the
   * two syncs and each price variant. Never the backend key
   * (`FRONTEND_UX_GUIDELINES.md` 5, 6).
   */
  label: string
  submit(api: SyncApi): Promise<SyncTaskAccepted>
}

/** Manual admin actions; normal feature workflows refresh their own required data. */
export const SYNC_OPERATIONS: readonly SyncOperation[] = [
  {
    key: 'ACCOUNT_SYNC',
    label: 'Synchronize account',
    submit: (api) => api.startAccountSync()
  },
  {
    key: 'GLOBAL_SYNC',
    label: 'Synchronize game data',
    submit: (api) => api.startGlobalSync()
  }
]
