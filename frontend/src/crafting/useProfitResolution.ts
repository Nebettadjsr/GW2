import type { CraftingApi } from '@/api/craftingApi'
import type {
  CraftingProfitResolutionResponse,
  EffectiveScope,
  EffectiveSettings,
  ResolutionCalculation,
  ScopeRequest
} from '@/api/types'
import { useResolutionDetail, type ResolutionDetailState } from './useResolutionDetail'

/**
 * Crafting Profit's selected-recipe detail: the Profit route's request body and identity check, over
 * the shared association rules of `TARGET_ARCHITECTURE.md` 13.4 (`useResolutionDetail`).
 *
 * The request carries the effective scope and settings the *table response echoed*, so the detail is
 * calculated with the inputs the list on screen was calculated with, rather than asking the backend
 * for its defaults a second time. Nothing else is sent — no row number, price, material list or
 * context identifier — because this is a fresh calculation and not retrieval of the earlier one.
 *
 * Nothing here calculates: the response is held as it arrived and read by the view.
 */

/** The effective inputs of the calculation on screen, as the table response echoed them. */
export interface CalculationInputs {
  scope: EffectiveScope
  settings: EffectiveSettings
}

export type ProfitResolutionState = ResolutionDetailState<
  CraftingProfitResolutionResponse,
  CalculationInputs
>

export function useProfitResolution(api: CraftingApi): ProfitResolutionState {
  return useResolutionDetail<CraftingProfitResolutionResponse, CalculationInputs>(
    (recipeId, inputs) =>
      api.resolveProfitDetail({
        recipeId,
        calculation: { scope: scopeRequestOf(inputs.scope), settings: inputs.settings }
      }),
    (response, recipeId, inputs) =>
      response.recipeId === recipeId && sameCalculation(response.calculation, inputs)
  )
}

/**
 * The echoed scope as a request scope. An effective member the backend reported as absent is left
 * out rather than sent as a null, which is how the table request contract carries "not supplied".
 */
export function scopeRequestOf(scope: EffectiveScope): ScopeRequest {
  const request: ScopeRequest = { kind: scope.kind, rating: scope.rating }
  if (scope.discipline !== null) request.discipline = scope.discipline
  if (scope.characterName !== null) request.characterName = scope.characterName
  return request
}

/**
 * A stable key for the *calculation* a detail belongs to. Deliberately built from the backend's
 * echoed scope and settings only: sorting, searching, the display filters and the display maximum
 * are not part of it, so re-ordering the table does not invalidate a detail (13.4).
 */
export function calculationKey(inputs: CalculationInputs | null): string {
  if (inputs === null) return ''
  const { scope, settings } = inputs
  return JSON.stringify([
    scope.kind,
    scope.discipline,
    scope.characterName,
    scope.rating,
    settings.useOwnMats,
    settings.allowBuying,
    settings.maxBuyCopper,
    settings.listingSell,
    settings.listingBuy,
    settings.dailyBuyInsteadOfCraft
  ])
}

function sameCalculation(echoed: ResolutionCalculation, inputs: CalculationInputs): boolean {
  return calculationKey(echoed) === calculationKey(inputs)
}
