import { ref, shallowRef, type Ref } from 'vue'
import { ApiRequestError } from '@/api/http'
import { RECIPE_NOT_IN_CALCULATION, type ResolutionDetailView } from '@/api/types'

/**
 * The association rules a selected recipe's freshly calculated detail obeys
 * (`TARGET_ARCHITECTURE.md` 13.1, 13.2 and 13.4), independent of which feature asked.
 *
 * Detail is **lazy**: one request per selected recipe, never one per table row. A detail is associated
 * with the feature, the recipe ID, the effective calculation inputs the *table response echoed* and a
 * local request generation. Every generation-invalidating event bumps the counter, so an answer that
 * arrives after it is dropped instead of being displayed: that covers a changed selection (including
 * A → B → A, where A's first answer belongs to a dead generation), changed calculation inputs, a
 * started table reload, and leaving the view while the page is kept alive. An accepted answer must
 * additionally echo back the recipe ID and the inputs that were asked for.
 *
 * Profit and Discovery share this because 13.4 is one contract, not two: the routes differ only in
 * what a request body looks like and in which echoed inputs make an answer this selection's answer,
 * which is exactly what the two callbacks supply. Nothing here calculates, and nothing here knows a
 * route, a scope shape or a setting.
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

/** Wording for a response that is not an answer to what was asked; one sentence, one place. */
export const MISMATCHED_ECHO =
  'The backend answered for a different recipe or different calculation inputs.'

export interface ResolutionDetailState<Detail extends ResolutionDetailView, Inputs> {
  phase: Ref<ResolutionPhase>
  /** The accepted response, or null in every other phase — an error never leaves an old tree up. */
  detail: Ref<Detail | null>
  /** The backend's own code and message for `failed`/`absent`; null otherwise. */
  failure: Ref<string | null>
  /** The recipe the current phase is about, so the region can never look attached to another row. */
  requestedRecipeId: Ref<number | null>
  /** How many requests this state has issued; lets a test prove selection stays lazy. */
  requestCount: Ref<number>

  request(recipeId: number, inputs: Inputs): Promise<void>
  /** Drops the active detail and invalidates every answer still in flight. */
  invalidate(): void
}

/**
 * @param send          issues the route's own request for one recipe and the table's effective inputs
 * @param echoesRequest whether a received response echoes back that recipe and those inputs
 */
export function useResolutionDetail<Detail extends ResolutionDetailView, Inputs>(
  send: (recipeId: number, inputs: Inputs) => Promise<Detail>,
  echoesRequest: (response: Detail, recipeId: number, inputs: Inputs) => boolean
): ResolutionDetailState<Detail, Inputs> {
  const phase = ref<ResolutionPhase>('idle')
  // Shallow because a response is held as it arrived and never edited in place. The cast is Vue's
  // reactivity typing meeting a type parameter: `shallowRef`'s own overloads cannot narrow `Detail`.
  const detail = shallowRef<Detail | null>(null) as Ref<Detail | null>

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

  async function request(recipeId: number, inputs: Inputs): Promise<void> {
    // Invalidating first is what makes A → B → A safe: the earlier A is already a dead generation.
    invalidate()
    const generation = activeGeneration
    requestCount.value += 1
    requestedRecipeId.value = recipeId
    phase.value = 'loading'

    try {
      const response = await send(recipeId, inputs)
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
  function accept(response: Detail, recipeId: number, inputs: Inputs): void {
    if (!echoesRequest(response, recipeId, inputs)) {
      failure.value = MISMATCHED_ECHO
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

/** The backend's own code and message where there is one; otherwise whatever failed locally. */
export function describeFailure(cause: unknown): string {
  if (cause instanceof ApiRequestError) return `${cause.code}: ${cause.message}`
  return cause instanceof Error ? cause.message : String(cause)
}
