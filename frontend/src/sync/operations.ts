import { startAccountViewRefresh, type SyncApi } from '@/api/syncApi'
import type { SyncTaskAccepted } from '@/api/types'

/**
 * Manual System Status actions and source-specific automatic refreshes share task tracking.
 */
export type AccountViewOperation = 'BANK_SYNC' | 'MATERIALS_SYNC' | 'CHARACTERS_SYNC'
export type SyncOperationKey = 'ACCOUNT_SYNC' | 'GLOBAL_SYNC' | AccountViewOperation

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

/** Shell-owned tracking for manual actions and the visible view's automatic refresh. */
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
  },
  {
    key: 'BANK_SYNC',
    label: 'Refresh bank',
    submit: () => startAccountViewRefresh('bank')
  },
  {
    key: 'MATERIALS_SYNC',
    label: 'Refresh materials',
    submit: () => startAccountViewRefresh('materials')
  },
  {
    key: 'CHARACTERS_SYNC',
    label: 'Refresh characters',
    submit: () => startAccountViewRefresh('characters')
  }
]
