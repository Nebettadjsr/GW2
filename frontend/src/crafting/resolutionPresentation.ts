import type { ResolutionNode } from '@/api/types'

/** The item's name, or its ID when the backend supplied none. */
export function nodeLabel(node: ResolutionNode): string {
  return node.itemName ?? `Item #${node.itemId}`
}

/**
 * The two item-specific acquisition outcomes called out in the crafting detail contract. Raw
 * Resolver state/reason codes stay in the API for diagnostics and are not player-facing labels.
 */
export function nodePlayerStatus(node: ResolutionNode): 'Not craftable' | 'Not available on TP' | null {
  if (node.states.includes('PRICE_UNAVAILABLE') || node.blockedReasons.includes('PRICE_UNAVAILABLE')) {
    return 'Not available on TP'
  }
  if (node.recipeId === null && node.blockedReasons.some((code) =>
    ['NO_RECIPE', 'RECIPE_NOT_ALLOWED'].includes(code)
  )) {
    return 'Not craftable'
  }
  return null
}

/**
 * How this node's output was actually produced, relative to the recipe the browser asked about.
 * Only supplied identities are compared; nothing is inferred about why they differ.
 */
export function describeRootSourcing(root: ResolutionNode, requestedRecipeId: number): string {
  if (root.recipeId === null) {
    return (
      'No recipe was selected for this requirement, so the output shown here was not crafted by ' +
      `the requested recipe ${requestedRecipeId}.`
    )
  }
  if (root.recipeId === requestedRecipeId) {
    return `The requested recipe ${requestedRecipeId} is the recipe selected for this requirement.`
  }
  return (
    `Recipe ${root.recipeId} was selected for this requirement, not the requested recipe ` +
    `${requestedRecipeId}.`
  )
}
