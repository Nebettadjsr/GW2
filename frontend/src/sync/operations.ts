import type { SyncApi } from '@/api/syncApi'
import type { SyncTaskAccepted } from '@/api/types'

/**
 * The backend's own operation keys (`CURRENT_ARCHITECTURE.md` 5.7–5.9).
 *
 * Statuses are kept apart by these keys, the two price variants included, because that is exactly
 * how the backend admits and reports tasks. No key is combined and none is invented here.
 */
export type SyncOperationKey =
  | 'ACCOUNT_SYNC'
  | 'GLOBAL_SYNC'
  | 'PRICE_REFRESH_PROFIT'
  | 'PRICE_REFRESH_DISCOVERY'

export interface SyncOperation {
  key: SyncOperationKey
  /**
   * What the user reads on the button: what this operation does, in their terms, distinguishing the
   * two syncs and each price variant. Never the backend key
   * (`FRONTEND_UX_GUIDELINES.md` 5, 6).
   */
  label: string
  /** One sentence on what the backend does, from the flows in `CURRENT_ARCHITECTURE.md` 5.7–5.9. */
  description: string
  submit(api: SyncApi): Promise<SyncTaskAccepted>
}

/** One entry per trigger the backend offers; the synchronization page renders these in order. */
export const SYNC_OPERATIONS: readonly SyncOperation[] = [
  {
    key: 'ACCOUNT_SYNC',
    label: 'Synchronize account',
    description:
      'Reads this account’s bank, material storage, unlocked recipes and characters from Guild ' +
      'Wars 2 and stores them.',
    submit: (api) => api.startAccountSync()
  },
  {
    key: 'GLOBAL_SYNC',
    label: 'Synchronize game data',
    description:
      'Updates the tradeable items and recipes shared by every calculation, then rebuilds the ' +
      'crafting graph. Independent of the account synchronization.',
    submit: (api) => api.startGlobalSync()
  },
  {
    key: 'PRICE_REFRESH_PROFIT',
    label: 'Refresh Trading Post prices for Crafting Profit',
    description:
      'Fetches current Trading Post quotes for the items the Crafting Profit calculation needs. ' +
      'Quotes younger than ten minutes are left as they are, so a completed refresh need not have ' +
      'fetched anything.',
    submit: (api) => api.startPriceRefresh('PROFIT')
  },
  {
    key: 'PRICE_REFRESH_DISCOVERY',
    label: 'Refresh Trading Post prices for Crafting Discovery',
    description:
      'The same refresh for the items the Crafting Discovery calculation needs. It is a separate ' +
      'operation from the Crafting Profit refresh and can run alongside it.',
    submit: (api) => api.startPriceRefresh('DISCOVERY')
  }
]
