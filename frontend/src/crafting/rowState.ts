import type { CraftingRow } from '@/api/types'

/**
 * The words for a Discovery row's supplied availability/blocking state, used only by the Discovery
 * search (`useDiscoveryTableView.matchesSearch`), so that searching for what a state is called still
 * finds its rows. Neither comparison table nor the Discovery detail shows these words: no row-state
 * label is rendered (`DOMAIN_SPEC.md` 2.1.1).
 *
 * It reads `resultAvailable`, `blockedReason` and `craftableCount` and rewords them. It decides
 * nothing about crafting, and an unrecognized code is searchable as itself.
 */
export interface RowState {
  /** Two or three words naming the state. */
  label: string
}

/** `craft.BlockedReason`'s not-blocked value. */
const NOT_BLOCKED = 'NONE'

/** The `craft.BlockedReason` names DOMAIN_SPEC 42 lists, each with the words searched for. */
const BLOCKED_LABELS: Record<string, string> = {
  NO_RECIPE: 'No recipe',
  BUYING_DISABLED: 'Buying is off',
  DAILY_LIMIT: 'Daily limit',
  CYCLE_DETECTED: 'Recipe loop',
  PRICE_UNAVAILABLE: 'Price missing',
  RECIPE_NOT_ALLOWED: 'Recipe not allowed',
  INSUFFICIENT_BUDGET: 'Over the buy limit',
  MATERIAL_LIMIT: 'Too many materials',
  WORK_LIMIT: 'Incomplete',
  VALUE_RANGE: 'Value out of range',
  WALLET_UNAVAILABLE: 'Wallet unknown',
  INSUFFICIENT_CURRENCY: 'Not enough currency'
}

export function describeRowState(row: CraftingRow): RowState {
  if (!row.resultAvailable) return { label: 'No result' }

  const code = row.blockedReason
  if (code === null) return { label: 'State not reported' }

  if (code === NOT_BLOCKED) {
    return { label: row.craftableCount === 0 ? 'None craftable' : 'Not blocked' }
  }

  return { label: BLOCKED_LABELS[code] ?? code }
}
