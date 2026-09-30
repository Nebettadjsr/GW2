import { API_BASE_PATH, ApiRequestError, getJson, postJson } from './http'
import type { PriceRefreshVariant, SyncTaskAccepted, SyncTaskStatus } from './types'

/**
 * The three synchronization triggers and the one shared task-status route
 * (`CURRENT_ARCHITECTURE.md` 5.7–5.9).
 *
 * An interface rather than bare functions so a test can supply controlled responses without
 * stubbing the global `fetch`. Nothing here orchestrates synchronization: each trigger posts one
 * request and returns what the backend accepted.
 */
export interface SyncApi {
  startAccountSync(): Promise<SyncTaskAccepted>
  startProfitDataRefresh(): Promise<SyncTaskAccepted>
  startGlobalSync(): Promise<SyncTaskAccepted>
  startPriceRefresh(variant: PriceRefreshVariant): Promise<SyncTaskAccepted>
  /** Reads one task's state from the location the acceptance advertised. */
  readTaskStatus(statusUrl: string): Promise<SyncTaskStatus>
}

/** Code used when the backend advertised a status location this client will not follow. */
export const UNUSABLE_STATUS_URL_CODE = 'UNUSABLE_STATUS_URL'

export const syncApi: SyncApi = {
  /** The two sync triggers take no parameters; the backend rejects a body carrying any field. */
  startAccountSync(): Promise<SyncTaskAccepted> {
    return postJson<SyncTaskAccepted>('/sync/account', {})
  },

  startProfitDataRefresh(): Promise<SyncTaskAccepted> {
    return postJson<SyncTaskAccepted>('/sync/account/crafting-profit', {})
  },

  startGlobalSync(): Promise<SyncTaskAccepted> {
    return postJson<SyncTaskAccepted>('/sync/global', {})
  },

  /** The variant is required and there are exactly two; no combined refresh exists. */
  startPriceRefresh(variant: PriceRefreshVariant): Promise<SyncTaskAccepted> {
    return postJson<SyncTaskAccepted>('/prices/refresh', { variant })
  },

  async readTaskStatus(statusUrl: string): Promise<SyncTaskStatus> {
    return getJson<SyncTaskStatus>(backendPathOf(statusUrl))
  }
}

/**
 * The advertised status location, reduced to the sub-path `getJson` prefixes with the API base.
 *
 * A location that does not sit under that base is refused instead of fetched: the browser calls
 * only the origin and base path it was served through, and a status location is backend-supplied
 * data, not a reason to contact somewhere else.
 */
function backendPathOf(statusUrl: string): string {
  const basePrefix = `${API_BASE_PATH}/`
  if (!statusUrl.startsWith(basePrefix)) {
    throw new ApiRequestError(
      `The backend advertised a task status location outside ${basePrefix}: ${statusUrl}`,
      UNUSABLE_STATUS_URL_CODE,
      null
    )
  }
  return statusUrl.slice(API_BASE_PATH.length)
}
