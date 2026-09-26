import { ref, shallowRef, type Ref } from 'vue'
import type { CraftingApi } from '@/api/craftingApi'
import { ApiRequestError } from '@/api/http'
import {
  RECIPE_NOT_IN_CALCULATION,
  type CraftingProfitResolutionResponse,
  type EffectiveScope,
  type EffectiveSettings,
  type ResolutionCalculation,
  type ScopeRequest
} from '@/api/types'

/**
 * The selected recipe's freshly calculated resolution detail, and the association rules that decide
 * which answer may be displayed (`TARGET_ARCHITECTURE.md` 13.1, 13.2 and 13.4).
 *
 * Detail is **lazy**: one request per selected recipe, never one per table row. The request carries
 * the effective scope and settings the *table response echoed*, so the detail is calculated with the
 * inputs the list on screen was calculated with, rather than asking the backend for its defaults a
 * second time. Nothing else is sent — no row number, price, material list or context identifier —
 * because this is a fresh calculation and not retrieval of the earlier one.
 *
 * A detail is associated with the feature, the recipe ID, those effective inputs and a local request
 * generation. Every generation-invalidating event bumps the counter, so an answer that arrives after
 * it is dropped instead of being displayed: that covers a changed selection (including A → B → A,
 * where A's first answer belongs to a dead generation), changed calculation inputs, a started table
 * reload, and leaving the view while the page is kept alive. An accepted answer must additionally
 * echo back the recipe ID and the inputs that were asked for.
 *
 * Nothing here calculates: the response is held as it arrived and read by the view.
 */

/**
 * Which situation the detail region is in. The three unsuccessful ones are deliberately separate:
 * a recipe the fresh calculation does not contain, a calculation that produced no result, and a
 * request that failed are different facts and get different wording.
 */
export type ResolutionPhase =
  /** Nothing is selected, or the detail was invalidated and not asked for again. */
  | 'idle'
  /** A request is in flight for the current selection. */
  | 'loading'
  /** HTTP 200 with a tree. The tree itself may still report blocked requirements. */
  | 'ready'
  /** HTTP 200 reporting that this calculation produced no result to explain; there is no tree. */
  | 'unavailable'
  /** HTTP 404: the recipe is not in the fresh calculation's visible candidate set. */
  | 'absent'
  /** The request failed, or answered for something other than what was asked. */
  | 'failed'

/** The effective inputs of the calculation on screen, as the table response echoed them. */
export interface CalculationInputs {
  scope: EffectiveScope
  settings: EffectiveSettings
}

export interface ProfitResolutionState {
  phase: Ref<ResolutionPhase>
  /** The accepted response, or null in every other phase — an error never leaves an old tree up. */
  detail: Ref<CraftingProfitResolutionResponse | null>
  /** The backend's own code and message for `failed`/`absent`; null otherwise. */
  failure: Ref<string | null>
  /** The recipe the current phase is about, so the region can never look attached to another row. */
  requestedRecipeId: Ref<number | null>
  /** How many requests this state has issued; lets a test prove selection stays lazy. */
  requestCount: Ref<number>

  request(recipeId: number, inputs: CalculationInputs): Promise<void>
  /** Drops the active detail and invalidates every answer still in flight. */
  invalidate(): void
}

export function useProfitResolution(api: CraftingApi): ProfitResolutionState {
  const phase = ref<ResolutionPhase>('idle')
  const detail = shallowRef<CraftingProfitResolutionResponse | null>(null)
  const failure = ref<string | null>(null)
  const requestedRecipeId = ref<number | null>(null)
  const requestCount = ref(0)

  /**
   * The only generation an answer may be displayed for. Every invalidating event increments it, so
   * a late answer is compared against a number that has already moved on.
   */
  let activeGeneration = 0

  function invalidate(): void {
    activeGeneration += 1
    phase.value = 'idle'
    detail.value = null
    failure.value = null
    requestedRecipeId.value = null
  }

  async function request(recipeId: number, inputs: CalculationInputs): Promise<void> {
    // Invalidating first is what makes A → B → A safe: the earlier A is already a dead generation.
    invalidate()
    const generation = activeGeneration
    requestCount.value += 1
    requestedRecipeId.value = recipeId
    phase.value = 'loading'

    try {
      const response = await api.resolveProfitDetail({
        recipeId,
        calculation: { scope: scopeRequestOf(inputs.scope), settings: inputs.settings }
      })
      if (generation !== activeGeneration) return
      accept(response, recipeId, inputs)
    } catch (cause) {
      if (generation !== activeGeneration) return
      reject(cause)
    }
  }

  /**
   * An answer is only this selection's answer if it says so itself. A response echoing another
   * recipe or other inputs is a mismatch, not a detail to display — and the tree stays cleared.
   */
  function accept(
    response: CraftingProfitResolutionResponse,
    recipeId: number,
    inputs: CalculationInputs
  ): void {
    if (!echoesRequest(response, recipeId, inputs)) {
      failure.value = 'The backend answered for a different recipe or different calculation inputs.'
      phase.value = 'failed'
      return
    }

    if (response.treeStatus === TREE_STATUS_AVAILABLE && response.tree !== null) {
      detail.value = response
      phase.value = 'ready'
      return
    }
    // No tree to show: keep the fact, not a fabricated empty one.
    detail.value = response
    phase.value = 'unavailable'
  }

  function reject(cause: unknown): void {
    if (cause instanceof ApiRequestError && cause.code === RECIPE_NOT_IN_CALCULATION) {
      failure.value = cause.message
      phase.value = 'absent'
      return
    }
    failure.value = describeFailure(cause)
    phase.value = 'failed'
  }

  return { phase, detail, failure, requestedRecipeId, requestCount, request, invalidate }
}

const TREE_STATUS_AVAILABLE = 'AVAILABLE'

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

function echoesRequest(
  response: CraftingProfitResolutionResponse,
  recipeId: number,
  inputs: CalculationInputs
): boolean {
  return response.recipeId === recipeId && sameCalculation(response.calculation, inputs)
}

function sameCalculation(echoed: ResolutionCalculation, inputs: CalculationInputs): boolean {
  return calculationKey(echoed) === calculationKey(inputs)
}

function describeFailure(cause: unknown): string {
  if (cause instanceof ApiRequestError) return `${cause.code}: ${cause.message}`
  return cause instanceof Error ? cause.message : String(cause)
}
