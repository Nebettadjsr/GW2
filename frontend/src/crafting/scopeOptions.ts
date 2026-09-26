import type { ScopeRequest, SelectorOptions } from '@/api/types'

/**
 * One entry of Crafting Profit's sole Discipline selector (DOMAIN_SPEC 2.2.1): All, a generic
 * discipline, or one synced character discipline.
 *
 * Every entry is built from facts the backend supplied; no discipline list, character or rating is
 * invented here, and no eligibility rule is applied.
 */
export interface ScopeOption {
  /** Stable identity of this entry, used as the `<select>` value and to re-find it after a reload. */
  id: string
  label: string
  request: ScopeRequest
}

export const ALL_SCOPE_KIND = 'ALL'
const DISCIPLINE_SCOPE_KIND = 'DISCIPLINE'
const CHARACTER_DISCIPLINE_SCOPE_KIND = 'CHARACTER_DISCIPLINE'

/**
 * Builds the selector entries in the order the backend reported them: All first, then the generic
 * disciplines, then one entry per synced character discipline.
 */
export function buildScopeOptions(options: SelectorOptions): ScopeOption[] {
  const allOption: ScopeOption = {
    id: ALL_SCOPE_KIND,
    label: 'All',
    request: { kind: ALL_SCOPE_KIND }
  }

  const disciplineOptions = options.disciplines.map<ScopeOption>((discipline) => ({
    id: `${DISCIPLINE_SCOPE_KIND}|${discipline}`,
    label: discipline,
    request: { kind: DISCIPLINE_SCOPE_KIND, discipline }
  }))

  const characterOptions = options.characterOptions.map<ScopeOption>((option) => ({
    id: `${CHARACTER_DISCIPLINE_SCOPE_KIND}|${option.discipline}|${option.rating}|${option.characterName}`,
    label: `${option.discipline} lvl ${option.rating} — ${option.characterName}`,
    request: {
      kind: CHARACTER_DISCIPLINE_SCOPE_KIND,
      discipline: option.discipline,
      characterName: option.characterName,
      rating: option.rating
    }
  }))

  return [allOption, ...disciplineOptions, ...characterOptions]
}

/**
 * The entry to preselect, taken from the backend's own `defaultScopeKind` rather than a default
 * repeated here. Falls back to the first entry if the backend ever names a kind it did not offer.
 */
export function defaultScopeOptionId(options: SelectorOptions, scopeOptions: ScopeOption[]): string | null {
  const preferred = scopeOptions.find((option) => option.request.kind === options.defaultScopeKind)
  return preferred?.id ?? scopeOptions[0]?.id ?? null
}
