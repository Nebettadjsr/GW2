import { getJson, postJson } from './http'
import type {
  CraftingProfitRequest,
  CraftingProfitResolutionRequest,
  CraftingProfitResolutionResponse,
  CraftingProfitResponse,
  SelectorOptions
} from './types'

/**
 * The three backend routes the Crafting Profit screen uses (`CURRENT_ARCHITECTURE.md` 5.5, 5.10 and
 * 5.13).
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
  }
}
