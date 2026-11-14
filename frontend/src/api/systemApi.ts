import { getAttachment, getJson } from './http'

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

/** One saved own-account source; counts are null (never zero) unless state is CURRENT or STALE. */
export interface SavedSourceOverview {
  state: 'CURRENT' | 'STALE' | 'NOT_LOADED' | 'UNAVAILABLE'
  count: number | null
  total?: number | null
  completedAt?: string | null
}

/** Members are absent when the connected key does not permit that source. */
export interface AccountOverview {
  characters?: SavedSourceOverview
  recipes?: SavedSourceOverview
  materials?: SavedSourceOverview
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
  accountOverview?: AccountOverview | null
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
  'globalHealth' | 'priceHealth' | 'accountLastRefreshedAt' | 'accountRefreshScope' | 'accountOverview' | OwnerSummaryKey>>
  & Pick<GlobalSystemStatus, OwnerSummaryKey>

export interface OperationsPercentile { upperBoundMs: number; overflow: boolean; display: string }
export interface OperationsRoute {
  group: string; requests: number; http2xx3xx: number; http4xx: number; http5xx: number
  failedIncomplete: number; meanMs: number; maxMs: number
  p50: OperationsPercentile | null; p95: OperationsPercentile | null
}
export interface ForegroundActivity {
  state: 'COMPLETE' | 'PARTIAL_STARTUP' | 'TRUNCATED' | 'UNAVAILABLE'
  exactCount: number | null; lowerBound: number | null
}
export interface OperationsBucket {
  startUtc: string; observedSeconds: number; completeness: 'COMPLETE' | 'PARTIAL'
  foregroundAccounts: ForegroundActivity | null
  /** All route groups merged from raw counts; null when the bucket recorded no groups. */
  overall: OperationsRoute | null
  routeGroups: OperationsRoute[]
}
export interface OperationsSummary {
  generatedAt: string
  foregroundAccounts: ForegroundActivity
  databaseSize: { state: 'AVAILABLE' | 'STALE' | 'UNAVAILABLE'; bytes: number | null; measuredAt: string | null }
  historyAvailable: boolean; retentionTruncated: boolean
  minutes: OperationsBucket[]
  latestMinute: OperationsBucket | null
  oracleConsole: { configured: boolean; url: string | null }
}
/** Server description of one downloadable UTC day or week; the id is opaque and only echoed back. */
export interface OperationsPeriod {
  selectionId: string; kind: 'DAY' | 'WEEK'; fromUtc: string; toUtc: string
  state: 'OPEN' | 'CLOSED'; coverage: 'FULL' | 'PARTIAL'; truncated: boolean; pruned: boolean
  retainedBytes: number; filename: string
}
export interface OperationsPeriods { available: boolean; periods: OperationsPeriod[] }

export const systemApi = {
  /** Owner-only summary; read on explicit request, never polled. */
  readOperationsSummary(): Promise<OperationsSummary> {
    return getJson<OperationsSummary>('/system/operations/summary')
  },
  readOperationsPeriods(): Promise<OperationsPeriods> {
    return getJson<OperationsPeriods>('/system/operations/periods')
  },
  /** Sends only the opaque selection id; the file name comes from the server's attachment header. */
  downloadOperationsPeriod(selectionId: string, signal?: AbortSignal): Promise<{ text: string; filename: string | null }> {
    return getAttachment(`/system/operations/download?${new URLSearchParams({ selection: selectionId })}`, 'text/csv', signal)
  },
  readStatus(): Promise<GlobalSystemStatus> {
    return getJson<GlobalSystemStatus>('/system/status')
  },
  /** Owner-only; a non-owner or disconnected caller receives an ordinary 401/403 backend error. */
  readOwnerDetails(): Promise<OwnerSystemDetails> {
    return getJson<OwnerSystemDetails>('/system/status/details')
  }
}
