import type { ResolutionNode } from '@/api/types'

/**
 * User-oriented wording for the codes a resolution node carries
 * (`TARGET_ARCHITECTURE.md` 13.3/14, `DOMAIN_SPEC.md` 42, `FRONTEND_UX_GUIDELINES.md` 5).
 *
 * Every function here rewords a code the backend supplied. None of them decides anything about
 * crafting: no state is derived from a price or a quantity, no code is translated into a different
 * meaning, and a code this client has no wording for is shown as itself and never toned as success.
 *
 * The blocked-reason wording is `rowState.ts`'s, adjusted to the one thing a node says differently.
 * A row's reason names why the *next* craft could not complete, so it is worded as "further crafting
 * is blocked"; a node's reason names why *this requirement* could not be resolved, which is a
 * statement about that requirement and not about the crafts the row already counted.
 *
 * Each sentence takes the node's own item label, so a missing-price explanation names the item it is
 * about rather than only saying "Price missing" (`DOMAIN_SPEC.md` 2.1.1). That label is `nodeLabel`'s
 * — the backend's name for *this* node's item, or its ID when no name was supplied. No item is ever
 * carried over from another node or from a row's own reason, which names none.
 */

/** A code and the words shown for it; `known` is false when this client has no wording for it. */
export interface CodeLabel {
  code: string
  label: string
  known: boolean
}

/** `craft.AcquisitionMethod` names — how a requirement was actually supplied. */
const METHODS: Record<string, string> = {
  INVENTORY: 'From stock',
  CRAFT: 'Crafted',
  BUY: 'Bought'
}

/**
 * `craft.ResolutionState` names, with the sentence the detail shows under the node. Each sentence
 * receives the node's own item label so it can name the item it is about.
 */
const STATES: Record<string, { label: string; explanation: (item: string) => string }> = {
  BLOCKED: {
    label: 'Blocked',
    explanation: () => 'The calculation could not fully resolve this requirement.'
  },
  PRICE_UNAVAILABLE: {
    label: 'Price missing',
    explanation: (item) =>
      `No purchase price is available for ${item}. A cost shown as missing is unknown, not zero.`
  },
  DAILY_LIMIT: {
    label: 'Daily limit',
    explanation: () => 'This item is limited to a daily amount.'
  },
  UNVALUED_NONTRADEABLE: {
    label: 'Not tradable, valued at zero',
    explanation: () =>
      'This item cannot be traded, so the calculation established a value of 0 copper for it. ' +
      'That is a known zero rather than a missing price, and the item is still a real requirement ' +
      'of this craft.'
  }
}

/**
 * `craft.BlockedReason` names as DOMAIN_SPEC 42 lists them, worded about the requirement this node
 * describes. `because` completes "Blocked because …".
 */
const BLOCKED_REASONS: Record<string, { label: string; because: (item: string) => string }> = {
  NO_RECIPE: { label: 'No recipe', because: () => 'this item has no usable recipe' },
  BUYING_DISABLED: {
    label: 'Buying is off',
    because: () =>
      'it would have to be bought to resolve this requirement, and buying is switched off'
  },
  DAILY_LIMIT: { label: 'Daily limit', because: () => 'this item is limited to a daily amount' },
  CYCLE_DETECTED: { label: 'Recipe loop', because: () => 'its recipe ends up depending on itself' },
  PRICE_UNAVAILABLE: {
    label: 'Price missing',
    because: (item) => `no price is available for ${item}`
  },
  RECIPE_NOT_ALLOWED: {
    label: 'Recipe not allowed',
    because: () => 'its recipe is not allowed by the current settings'
  },
  INSUFFICIENT_BUDGET: {
    label: 'Over the buy limit',
    because: () => 'buying it costs more than the maximum buy setting allows'
  },
  NON_TRADEABLE_MATERIAL: {
    label: 'Non-Trading-Post material',
    because: () =>
      'it cannot be traded on the Trading Post and “Allow non-Trading-Post materials” is switched ' +
      'off, so no path consuming it was used'
  }
}

/** Shown in place of a wording this client does not have, so the raw code stays readable. */
export function unknownCodeLabel(code: string): CodeLabel {
  return { code, label: `${code} (not recognized)`, known: false }
}

export function methodLabels(methods: string[]): CodeLabel[] {
  return methods.map((code) => {
    const known = METHODS[code]
    return known === undefined ? unknownCodeLabel(code) : { code, label: known, known: true }
  })
}

export function stateLabels(states: string[]): CodeLabel[] {
  return states.map((code) => {
    const known = STATES[code]
    return known === undefined ? unknownCodeLabel(code) : { code, label: known.label, known: true }
  })
}

/**
 * One sentence per supplied state, in the supplied order; unknown codes say so rather than vanish.
 * `itemLabel` is this node's own item, which the missing-price sentence names.
 */
export function stateExplanations(states: string[], itemLabel: string): string[] {
  return states.map((code) => {
    const known = STATES[code]
    if (known !== undefined) return known.explanation(itemLabel)
    return `The backend reported a state this page does not recognize: ${code}.`
  })
}

export function blockedReasonLabels(reasons: string[]): CodeLabel[] {
  return reasons.map((code) => {
    const known = BLOCKED_REASONS[code]
    return known === undefined ? unknownCodeLabel(code) : { code, label: known.label, known: true }
  })
}

/**
 * The blocked reasons as one sentence, or null when the node reported none. Every supplied reason is
 * named — none is dropped for brevity — and an unrecognized one is quoted as itself. `itemLabel` is
 * this node's own item, which the missing-price cause names.
 */
export function blockedExplanation(reasons: string[], itemLabel: string): string | null {
  if (reasons.length === 0) return null
  const causes = reasons.map((code) => {
    const known = BLOCKED_REASONS[code]
    return known === undefined
      ? `the backend reported ${code}, which this page does not recognize`
      : known.because(itemLabel)
  })
  return `Blocked because ${joinClauses(causes)}.`
}

/** How a node's own requirement was sourced, in the words of the supplied methods alone. */
export function methodSummary(node: ResolutionNode): string {
  const labels = methodLabels(node.methods)
  if (labels.length === 0) return 'Nothing supplied this requirement.'
  return labels.map((entry) => entry.label).join(' · ')
}

/** The item's name, or its ID when the backend supplied none (`FRONTEND_UX_GUIDELINES.md` 5). */
export function nodeLabel(node: ResolutionNode): string {
  return node.itemName ?? `Item #${node.itemId}`
}

/**
 * How this node's output was actually produced, relative to the recipe the browser asked about.
 *
 * Only supplied identities are compared; nothing is inferred about why they differ. The point is
 * that a root sourced from stock or from a different recipe must never read as an execution of the
 * requested recipe (`TARGET_ARCHITECTURE.md` 13.3).
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

function joinClauses(clauses: string[]): string {
  if (clauses.length === 1) return clauses[0] ?? ''
  return `${clauses.slice(0, -1).join(', ')} and ${clauses[clauses.length - 1]}`
}
