import type { CraftingRow } from '@/api/types'

/**
 * Puts a row's supplied availability/blocking state into user-oriented words
 * (`FRONTEND_UX_GUIDELINES.md` 5, `TARGET_ARCHITECTURE.md` 14).
 *
 * It reads three values the backend supplied — `resultAvailable`, `blockedReason` and
 * `craftableCount` — and rewords them. It decides nothing about crafting: no state is derived from
 * prices, materials or any other field, no code is translated into a different meaning, and an
 * unrecognized code is shown as itself rather than quietly becoming "ready". The raw code stays
 * available for the detail region's diagnostic disclosure.
 *
 * The "further crafting" wording follows the domain's own definition of the field: the reason names
 * why the *next* craft could not complete, and the crafts already counted remain valid
 * (`craft.CraftResult`).
 */
/** One of the shared `.status--*` treatments, so a row state looks like every other status. */
export type RowStateTone = 'success' | 'caution' | 'idle' | 'unknown'

export interface RowState {
  /** Two or three words naming the state, for the selected-result detail's own status line. */
  label: string
  /** One sentence for the selected-result detail. */
  explanation: string
  tone: RowStateTone
  /** The backend's own code, for secondary disclosure; null when the row carried none. */
  code: string | null
}

/** `craft.BlockedReason`'s not-blocked value. */
const NOT_BLOCKED = 'NONE'

/** The `craft.BlockedReason` names DOMAIN_SPEC 42 lists, each with the words shown to the user. */
const BLOCKED_REASONS: Record<string, { label: string; because: string }> = {
  NO_RECIPE: { label: 'No recipe', because: 'a required material has no usable recipe' },
  BUYING_DISABLED: {
    label: 'Buying is off',
    because: 'a required material would have to be bought, and buying is switched off'
  },
  DAILY_LIMIT: { label: 'Daily limit', because: 'a required material is limited to a daily amount' },
  CYCLE_DETECTED: { label: 'Recipe loop', because: 'the recipe ends up depending on itself' },
  PRICE_UNAVAILABLE: { label: 'Price missing', because: 'a required price is not available' },
  RECIPE_NOT_ALLOWED: {
    label: 'Recipe not allowed',
    because: 'a required recipe is not allowed by the current settings'
  },
  INSUFFICIENT_BUDGET: {
    label: 'Over the buy limit',
    because: 'a required purchase costs more than the maximum buy setting allows'
  }
}

export function describeRowState(row: CraftingRow): RowState {
  if (!row.resultAvailable) {
    return {
      label: 'No result',
      explanation:
        'The backend reported this recipe but calculated no result for it, so none of its values exist.',
      tone: 'unknown',
      code: null
    }
  }

  const code = row.blockedReason
  if (code === null) {
    return {
      label: 'State not reported',
      explanation: 'The calculation reported no state for this recipe, so nothing is established about it.',
      tone: 'unknown',
      code: null
    }
  }

  if (code === NOT_BLOCKED) {
    return row.craftableCount === 0
      ? {
          label: 'None craftable',
          explanation: 'Nothing blocked the calculation, and it counted no craft that could be completed.',
          tone: 'idle',
          code
        }
      : {
          label: 'Not blocked',
          explanation: 'Nothing blocked the calculation for this recipe.',
          tone: 'success',
          code
        }
  }

  const known = BLOCKED_REASONS[code]
  if (known === undefined) {
    return {
      label: code,
      explanation: `The backend reported a state this page does not recognize: ${code}.`,
      tone: 'unknown',
      code
    }
  }

  return { label: known.label, explanation: blockedExplanation(known.because, row.craftableCount), tone: 'caution', code }
}

function blockedExplanation(because: string, craftableCount: number | null): string {
  if (craftableCount !== null && craftableCount > 0) {
    const crafts = craftableCount === 1 ? '1 craft' : `${craftableCount} crafts`
    return `Further crafting is blocked because ${because}. The ${crafts} already counted stay valid.`
  }
  return `Crafting is blocked because ${because}.`
}

/**
 * The ordinary restrictions on further crafting, which DOMAIN_SPEC 2.1.1 places in the selected
 * result's details instead of repeating beside every row. `describeRowState` still words each of
 * them in full; they simply carry no comparison-table label of their own.
 */
const DETAIL_ONLY_REASONS: ReadonlySet<string> = new Set([
  'BUYING_DISABLED',
  'NO_RECIPE',
  'DAILY_LIMIT',
  'RECIPE_NOT_ALLOWED',
  'INSUFFICIENT_BUDGET'
])

/** A few words beside one row, with the shared status treatment its tone names. */
export interface RowDiagnostic {
  label: string
  tone: RowStateTone
}

/**
 * The comparison table's minimal row diagnostic, or null when the row needs none
 * (`DOMAIN_SPEC.md` 2.1.1, `FRONTEND_UX_GUIDELINES.md` 4/5).
 *
 * This is not a state column brought back under another name. It is empty for an unblocked row and
 * for every ordinary restriction above, which is most rows; it appears only where the row's own
 * numbers cannot carry the meaning on their own:
 *
 * - `CYCLE_DETECTED` keeps a row-level diagnostic because DOMAIN_SPEC 2.1.1 says to retain one until
 *   the Product Owner asks for its removal. That retention is **temporary presentation technical
 *   debt**, recorded as such rather than as a settled design.
 * - A missing price and a recipe with no calculated result would otherwise be indistinguishable from
 *   an ordinary poor result, and removing the column must not imply success (2.1.1).
 * - A code this client has no wording for stays visible rather than reading as success. The raw code
 *   itself is secondary technical information and stays in the detail's disclosure.
 *
 * It decides nothing: every branch reads one supplied field, and no state is inferred from a price,
 * a count or a cost.
 */
export function rowDiagnostic(row: CraftingRow): RowDiagnostic | null {
  if (!row.resultAvailable) return { label: 'No result', tone: 'unknown' }

  const code = row.blockedReason
  if (code === null) return { label: 'State not reported', tone: 'unknown' }
  if (code === NOT_BLOCKED) return null
  if (DETAIL_ONLY_REASONS.has(code)) return null

  if (code === 'CYCLE_DETECTED') return { label: 'Recipe loop', tone: 'caution' }
  if (code === 'PRICE_UNAVAILABLE') return { label: 'Price missing', tone: 'caution' }

  return { label: 'Unrecognized state', tone: 'unknown' }
}
