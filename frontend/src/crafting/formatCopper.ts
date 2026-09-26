/**
 * Presentation-only money formatting (DOMAIN_SPEC 3: copper is the base unit).
 *
 * This converts a backend-supplied copper amount into gold/silver/copper text. It performs no
 * profit, fee or valuation arithmetic, and it never substitutes zero for a value the backend did
 * not supply — a missing value stays visibly missing (DOMAIN_SPEC 21).
 */

const COPPER_PER_SILVER = 100
const COPPER_PER_GOLD = 10_000

/** Shown wherever the backend supplied no value. Deliberately not `0`. */
export const NO_VALUE = '—'

export function formatCopper(copper: number | null | undefined): string {
  if (copper === null || copper === undefined) {
    return NO_VALUE
  }

  const sign = copper < 0 ? '-' : ''
  const amount = Math.abs(copper)
  const gold = Math.floor(amount / COPPER_PER_GOLD)
  const silver = Math.floor((amount % COPPER_PER_GOLD) / COPPER_PER_SILVER)
  const remainder = amount % COPPER_PER_SILVER

  if (gold > 0) return `${sign}${gold}g ${silver}s ${remainder}c`
  if (silver > 0) return `${sign}${silver}s ${remainder}c`
  return `${sign}${remainder}c`
}

/** Integer display for non-money counts, with the same explicit "not supplied" marker. */
export function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? NO_VALUE : String(value)
}

/**
 * A result that may be a gain or a loss, with the sign always written out.
 *
 * The sign — not the color — is what distinguishes the two, so the same distinction survives a
 * monochrome rendering (`FRONTEND_UX_GUIDELINES.md` 5). A supplied zero stays `0c` and a value the
 * backend did not supply stays the missing marker; neither becomes the other.
 */
export function formatSignedCopper(copper: number | null | undefined): string {
  if (copper === null || copper === undefined) return NO_VALUE
  return copper > 0 ? `+${formatCopper(copper)}` : formatCopper(copper)
}

/** Which semantic treatment a money value carries. `none` covers both a supplied zero and no value. */
export type MoneyTone = 'gain' | 'loss' | 'none'

export function moneyTone(copper: number | null | undefined): MoneyTone {
  if (copper === null || copper === undefined || copper === 0) return 'none'
  return copper > 0 ? 'gain' : 'loss'
}
