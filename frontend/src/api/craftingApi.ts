import { getJson, postJson } from './http'
import type {
  CraftingDiscoveryRequest,
  CraftingDiscoveryResolutionRequest,
  CraftingDiscoveryResolutionResponse,
  CraftingDiscoveryResponse,
  CraftingProfitRequest,
  CraftingProfitResolutionRequest,
  CraftingProfitResolutionResponse,
  CraftingProfitResponse,
  SelectorOptions
} from './types'

/**
 * The backend crafting routes the browser uses: the shared selector options
 * (`CURRENT_ARCHITECTURE.md` 5.10), Crafting Profit's table and fresh detail (5.5, 5.13) and Crafting
 * Discovery's own table and fresh detail (5.6, 5.13).
 *
 * The two features' calculation routes stay separate members because they are separate contracts:
 * Discovery requires an individual scope, has its own settings defaults, accepts neither the
 * Profit-only non-Trading-Post setting nor the fixed daily setting, and carries a separate inventory
 * character. Nothing here merges or translates between them.
 *
 * An interface rather than bare functions so a test can supply controlled responses without
 * stubbing the global `fetch`.
 */
export interface CraftingApi {
  loadSelectorOptions(): Promise<SelectorOptions>
  calculateProfit(request: CraftingProfitRequest): Promise<CraftingProfitResponse>
  resolveProfitDetail(
    request: CraftingProfitResolutionRequest
  ): Promise<CraftingProfitResolutionResponse>
  calculateDiscovery(request: CraftingDiscoveryRequest): Promise<CraftingDiscoveryResponse>
  resolveDiscoveryDetail(
    request: CraftingDiscoveryResolutionRequest
  ): Promise<CraftingDiscoveryResolutionResponse>
}

export const craftingApi: CraftingApi = {
  loadSelectorOptions(): Promise<SelectorOptions> {
    return getJson<SelectorOptions>('/crafting/selector-options')
  },

  /** An empty `request` asks the backend for its own documented default scope and settings. */
  calculateProfit(request: CraftingProfitRequest): Promise<CraftingProfitResponse> {
    return postJson<CraftingProfitResponse>('/crafting/profit', request)
  },

  /**
   * Detail for one selected recipe. A fresh calculation the backend completes synchronously with
   * HTTP 200 — the execution policy `STORY-API-008` measured and fixed — so there is no task, no
   * status resource and nothing to poll here.
   */
  resolveProfitDetail(
    request: CraftingProfitResolutionRequest
  ): Promise<CraftingProfitResolutionResponse> {
    return postJson<CraftingProfitResolutionResponse>('/crafting/profit/resolution', request)
  },

  /**
   * The still-missing discoverable recipes for one character+discipline, with the calculation for
   * each. The body is required and carries a complete scope — this route has no default scope to fall
   * back on — so the caller never posts an empty body here.
   */
  calculateDiscovery(request: CraftingDiscoveryRequest): Promise<CraftingDiscoveryResponse> {
    return postJson<CraftingDiscoveryResponse>('/crafting/discovery', request)
  },

  /** Detail for one selected Discovery recipe; synchronous and complete, exactly like Profit's. */
  resolveDiscoveryDetail(
    request: CraftingDiscoveryResolutionRequest
  ): Promise<CraftingDiscoveryResolutionResponse> {
    return postJson<CraftingDiscoveryResolutionResponse>('/crafting/discovery/resolution', request)
  }
}
