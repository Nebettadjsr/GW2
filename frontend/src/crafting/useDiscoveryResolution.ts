import type { CraftingApi } from '@/api/craftingApi'
import type {
  CraftingDiscoveryResolutionResponse,
  EffectiveDiscoveryScope,
  EffectiveDiscoverySettings
} from '@/api/types'
import { settingsRequestOf } from './useCraftingDiscovery'
import { useResolutionDetail, type ResolutionDetailState } from './useResolutionDetail'

/**
 * Crafting Discovery's selected-recipe detail: the Discovery route's request body and identity check,
 * over the shared association rules of `TARGET_ARCHITECTURE.md` 13.4 (`useResolutionDetail`).
 *
 * The request carries the effective one-character scope and settings the table response echoed, so
 * the detail uses the same character for eligibility and inventory.
 *
 * Nothing here calculates: the response is held as it arrived and read by the view.
 */

/** The effective inputs of the Discovery calculation on screen, as its response echoed them. */
export interface DiscoveryCalculationInputs {
  scope: EffectiveDiscoveryScope
  settings: EffectiveDiscoverySettings
}

export type DiscoveryResolutionState = ResolutionDetailState<
  CraftingDiscoveryResolutionResponse,
  DiscoveryCalculationInputs
>

export function useDiscoveryResolution(api: CraftingApi): DiscoveryResolutionState {
  return useResolutionDetail<CraftingDiscoveryResolutionResponse, DiscoveryCalculationInputs>(
    (recipeId, inputs) =>
      api.resolveDiscoveryDetail({
        recipeId,
        calculation: {
          scope: {
            discipline: inputs.scope.discipline,
            characterName: inputs.scope.characterName,
            rating: inputs.scope.rating
          },
          settings: settingsRequestOf(inputs.settings)
        }
      }),
    (response, recipeId, inputs) =>
      response.recipeId === recipeId &&
      discoveryCalculationKey(response.calculation) === discoveryCalculationKey(inputs)
  )
}

/**
 * A stable key for the *calculation* a Discovery detail belongs to. Built from the backend's echoed
 * scope and settings only: sorting and searching are not part of it, so
 * re-ordering the list does not invalidate a detail (13.4). `allowDailyCrafts` is included
 * because the echo reports it, and a route that changed the value it fixes would be calculating
 * something else.
 */
export function discoveryCalculationKey(inputs: DiscoveryCalculationInputs | null): string {
  if (inputs === null) return ''
  const { scope, settings } = inputs
  return JSON.stringify([
    scope.discipline,
    scope.characterName,
    scope.rating,
    settings.useOwnMats,
    settings.allowBuying,
    settings.listingSell,
    settings.listingBuy,
    settings.allowDailyCrafts
  ])
}
