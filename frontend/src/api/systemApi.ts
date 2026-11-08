import { getJson } from './http'

export type SharedHealth = 'OK' | 'RUNNING' | 'PROBLEM' | 'NOT_RUN'

/**
 * Generic shared-system status (STORY-STATUS-001): coarse health plus the requesting account's own
 * freshness. Detailed diagnostics are owner-only and arrive from a separate request, so every
 * detailed member is optional and may be absent.
 */
export interface GlobalSystemStatus {
  globalHealth?: SharedHealth
  priceHealth?: SharedHealth
  accountLastRefreshedAt?: string | null
  accountRefreshScope?: string | null
  // Owner-only details; never present in the generic response.
  running?: boolean
  lastCheckedAt?: string | null
  lastChangedAt?: string | null
  lastRecipeSyncAt?: string | null
  lastGraphRebuildAt?: string | null
  lastFailure?: string | null
  cachedPriceItems?: number | null
  stalePriceItems?: number | null
  newestPriceFetchedAt?: string | null
  priceCacheError?: string | null
}

export type OwnerSystemDetails = Required<Omit<GlobalSystemStatus,
  'globalHealth' | 'priceHealth' | 'accountLastRefreshedAt' | 'accountRefreshScope'>>

export const systemApi = {
  readStatus(): Promise<GlobalSystemStatus> {
    return getJson<GlobalSystemStatus>('/system/status')
  },
  /** Owner-only; a non-owner or disconnected caller receives an ordinary 401/403 backend error. */
  readOwnerDetails(): Promise<OwnerSystemDetails> {
    return getJson<OwnerSystemDetails>('/system/status/details')
  }
}
