import type { DiscoveryScopeRequest, SelectorOptions } from '@/api/types'

/**
 * Crafting Discovery's two selector controls, built from the selector route's facts alone
 * (`CURRENT_ARCHITECTURE.md` 5.10, `DOMAIN_SPEC.md` 2.2.2).
 *
 * Discovery is individual-character only (`CURRENT_ARCHITECTURE.md` 5.2/5.6): there is no All entry,
 * no generic-discipline entry and no default scope, because the route has none to fall back on. Each
 * entry carries the rating the backend reported for that character's discipline — never a rating
 * invented here, and never one carried over from another entry — because that value drives the
 * backend's `recipe.minRating <= rating` filter.
 *
 * Nothing here applies an eligibility rule. `characterOptions` is the service's own list in the
 * repository's own order; the `active` flag it also reports is deliberately not read, since no rule of
 * this client's may narrow what the backend offered.
 */
export interface DiscoveryScopeOption {
  /** Stable identity of this entry, used as the `<select>` value and to re-find it after a reload. */
  id: string
  label: string
  request: DiscoveryScopeRequest
}

/** One entry per synced character discipline, in the order the selector route reported them. */
export function buildDiscoveryScopeOptions(options: SelectorOptions): DiscoveryScopeOption[] {
  return options.characterOptions.map((option) => ({
    id: discoveryScopeOptionId(option.discipline, option.rating, option.characterName),
    // The same wording Crafting Profit's character entries use, so one character discipline is named
    // the same way everywhere in the application.
    label: `${option.discipline} lvl ${option.rating} — ${option.characterName}`,
    request: {
      discipline: option.discipline,
      characterName: option.characterName,
      rating: option.rating
    }
  }))
}

export function discoveryScopeOptionId(
  discipline: string,
  rating: number,
  characterName: string
): string {
  return `${discipline}|${rating}|${characterName}`
}

/**
 * The characters offerable for the separate `inventoryCharacterName` input: the distinct names of the
 * synced character disciplines above, first appearance first.
 *
 * This is the selector route's only source of character names, so the list covers characters that have
 * at least one crafting discipline. A character with none cannot be offered here — that is a limit of
 * the available facts, not a rule this page applies, and it is why "no character" stays a real choice
 * rather than this client substituting one.
 */
export function buildInventoryCharacterNames(options: SelectorOptions): string[] {
  const names: string[] = []
  for (const option of options.characterOptions) {
    if (!names.includes(option.characterName)) names.push(option.characterName)
  }
  return names
}
