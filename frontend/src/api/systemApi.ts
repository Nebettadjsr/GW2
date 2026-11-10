import { getJson } from './http'

/** Stored shared rows; figures are null (never zero) when state is UNAVAILABLE. */
export interface DatasetSummary {
  state: 'AVAILABLE' | 'UNAVAILABLE'
  storedRows: number | null
  timestampedRows: number | null
  unknownTimestampRows: number | null
  oldestFetchedAt: string | null
  newestFetchedAt: string | null
}

/** Aggregate counts only; never identities. */
export interface AccountUsageSummary {
  state: 'AVAILABLE' | 'UNAVAILABLE'
  totalAccounts: number | null
  activeLast60Minutes: number | null
}

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
  items?: DatasetSummary | null
  recipes?: DatasetSummary | null
  tradingPostPrices?: DatasetSummary | null
  accounts?: AccountUsageSummary | null
}

type OwnerSummaryKey = 'items' | 'recipes' | 'tradingPostPrices' | 'accounts'

/** Summaries stay optional: an older or partial backend answer renders as "not reported", never zero. */
export type OwnerSystemDetails = Required<Omit<GlobalSystemStatus,
  'globalHealth' | 'priceHealth' | 'accountLastRefreshedAt' | 'accountRefreshScope' | OwnerSummaryKey>>
  & Pick<GlobalSystemStatus, OwnerSummaryKey>

export const systemApi = {
  readStatus(): Promise<GlobalSystemStatus> {
    return getJson<GlobalSystemStatus>('/system/status')
  },
  /** Owner-only; a non-owner or disconnected caller receives an ordinary 401/403 backend error. */
  readOwnerDetails(): Promise<OwnerSystemDetails> {
    return getJson<OwnerSystemDetails>('/system/status/details')
  }
}
