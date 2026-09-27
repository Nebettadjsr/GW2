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
 * The request carries the effective scope, inventory character and settings the *Discovery table
 * response echoed*, so the detail is calculated with the inputs the list on screen was calculated
 * with. The inventory character is part of the calculation's identity: the same recipe resolved
 * against another character's owned materials is a different calculation, so changing it invalidates
 * the detail on screen and an answer echoing the previous one is refused.
 *
 * Nothing here calculates: the response is held as it arrived and read by the view.
 */

/** The effective inputs of the Discovery calculation on screen, as its response echoed them. */
export interface DiscoveryCalculationInputs {
  scope: EffectiveDiscoveryScope
  /** Null when the backend reported it used the unfiltered owned pool. */
  inventoryCharacterName: string | null
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
          // Left out rather than sent as a null, which is how the request contract carries "not
          // supplied" and what keeps the backend's own unfiltered-pool fallback in place.
          ...(inputs.inventoryCharacterName === null
            ? {}
            : { inventoryCharacterName: inputs.inventoryCharacterName }),
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
 * scope, inventory character and settings only: sorting and searching are not part of it, so
 * re-ordering the list does not invalidate a detail (13.4). `dailyBuyInsteadOfCraft` is included
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
    inputs.inventoryCharacterName,
    settings.useOwnMats,
    settings.allowBuying,
    settings.maxBuyCopper,
    settings.listingSell,
    settings.listingBuy,
    settings.dailyBuyInsteadOfCraft
  ])
}
