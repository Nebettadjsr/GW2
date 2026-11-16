import type { InjectionKey, Ref } from 'vue'
import type { EffectiveSettings, ResolutionNode } from '@/api/types'

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

/** Injection key: the confirmed effective settings a resolution tree was calculated with. */
export const RESOLUTION_SETTINGS_KEY: InjectionKey<Ref<EffectiveSettings | null>> = Symbol('resolutionSettings')

/**
 * Small finite keep-target note for one material, naming each source. It describes a fixed target
 * from the confirmed effective settings and never claims current stock.
 */
export function keepTargetNote(settings: EffectiveSettings | null | undefined, itemId: number): string | null {
  if (settings === null || settings === undefined || settings.materialProtectionEnabled !== true) return null
  const parts: string[] = []
  const bank = settings.protectedBankQuantities?.[String(itemId)]
  const materials = settings.protectedMaterialQuantities?.[String(itemId)]
  if (typeof bank === 'number' && bank > 0) parts.push(`Keep ${bank.toLocaleString('en-US')} in bank`)
  if (typeof materials === 'number' && materials > 0) parts.push(`Keep ${materials.toLocaleString('en-US')} in material storage`)
  return parts.length === 0 ? null : parts.join(' · ')
}
