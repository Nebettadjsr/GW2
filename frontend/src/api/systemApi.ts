import { getJson } from './http'

export interface GlobalSystemStatus {
  running: boolean
  lastCheckedAt: string | null
  lastChangedAt: string | null
  lastRecipeSyncAt: string | null
  lastGraphRebuildAt: string | null
  lastFailure: string | null
  accountLastRefreshedAt: string | null
  accountRefreshScope: string | null
  cachedPriceItems: number | null
  stalePriceItems: number | null
  newestPriceFetchedAt: string | null
  priceCacheError: string | null
}

export const systemApi = {
  readStatus(): Promise<GlobalSystemStatus> {
    return getJson<GlobalSystemStatus>('/system/status')
  }
}
