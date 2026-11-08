import { startAccountViewRefresh, type SyncApi } from '@/api/syncApi'
import type { SyncTaskAccepted } from '@/api/types'

/**
 * Manual System Status actions and source-specific automatic refreshes share task tracking.
 */
export type AccountViewOperation = 'BANK_SYNC' | 'MATERIALS_SYNC' | 'CHARACTERS_SYNC'
export type OwnerOperationKey = 'FULL_ITEM_CATALOG' | 'FULL_RECIPE_CATALOG' | 'FULL_TP_CATALOG'
export type SyncOperationKey = 'ACCOUNT_SYNC' | 'GLOBAL_SYNC' | AccountViewOperation | OwnerOperationKey

export interface SyncOperation {
  key: SyncOperationKey
  /**
   * What the user reads on the button: what this operation does, in their terms, distinguishing the
   * two syncs and each price variant. Never the backend key
   * (`FRONTEND_UX_GUIDELINES.md` 5, 6).
   */
  label: string
  /**
   * Owner-only full-catalog operation. Its task is accepted only when the backend reports exactly
   * this key as the operation, never repeated after a narrower run, and its state is cleared when
   * the account or owner authorization changes.
   */
  ownerOnly?: boolean
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
    key: 'FULL_ITEM_CATALOG',
    label: 'Refresh full item catalog',
    ownerOnly: true,
    submit: (api) => api.startFullCatalog('items')
  },
  {
    key: 'FULL_RECIPE_CATALOG',
    label: 'Refresh full recipe catalog',
    ownerOnly: true,
    submit: (api) => api.startFullCatalog('recipes')
  },
  {
    key: 'FULL_TP_CATALOG',
    label: 'Refresh all Trading Post prices',
    ownerOnly: true,
    submit: (api) => api.startFullCatalog('trading-post')
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
