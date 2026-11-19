import type { CraftingRow, MissingItem } from '@/api/types'

/**
 * How a recipe and a material are identified on screen.
 *
 * The backend supplies no name for an item outside its loaded item set (`web.CraftingRowMapper`).
 * The item id is then the identification, which `FRONTEND_UX_GUIDELINES.md` 5 allows explicitly —
 * no name is invented and nothing is fetched from the GW2 API to fill the gap.
 */
export function recipeLabel(row: CraftingRow): string {
  return row.outputName ?? `Item #${row.outputItemId}`
}

export function materialLabel(item: MissingItem): string {
  return item.itemName ?? `Item #${item.itemId}`
}

/** The official wiki, the only external destination this page links to. */
const WIKI_ARTICLE_BASE = 'https://wiki.guildwars2.com/wiki/'

/**
 * A wiki address for an item, or null when the backend gave no name to build one from.
 *
 * The wiki is keyed by article title, so the item's name is the only reliable target there is: an
 * item ID would produce a guess at a page that need not exist. When there is no name the link is
 * omitted rather than pointed somewhere speculative — the ID is still on screen as the item's
 * identification (`FRONTEND_UX_GUIDELINES.md` 5).
 *
 * The name is a backend-supplied string, so it is percent-encoded into the path rather than
 * concatenated: no part of it can become a separate path segment, a query or another host.
 */
export function wikiUrl(itemName: string | null): string | null {
  const name = itemName?.trim() ?? ''
  if (name === '') return null
  return `${WIKI_ARTICLE_BASE}${encodeURIComponent(name)}`
}

/** Whether the backend marked this row's recipe as a Mystic Forge recipe (its ids are negative, not game ids). */
export function isForgeRow(row: CraftingRow): boolean {
  return row.expectedYield?.source === 'MYSTIC_FORGE'
}

/** Source text: "Mystic Forge" instead of a blank discipline for Forge rows; normal labels unchanged. */
export function sourceLabel(row: CraftingRow): string {
  return isForgeRow(row) ? 'Mystic Forge' : row.disciplines
}

/** Concise, backend-only explanation of a variable/estimated result, or null for exact rows. */
export function yieldSummary(row: CraftingRow): string | null {
  const y = row.expectedYield
  if (y === null || y === undefined) return null
  const parts: string[] = []
  if (y.expectedOutputPerCraft !== null) {
    const range = y.minOutput !== null && y.maxOutput !== null ? ` (min ${y.minOutput}, max ${y.maxOutput})` : ''
    parts.push(`expected ${y.expectedOutputPerCraft} per craft${range}`)
  }
  if (y.expectedSellableUnitsTotal !== null) {
    const guaranteed = y.guaranteedSellableUnitsTotal !== null ? `, guaranteed ${y.guaranteedSellableUnitsTotal}` : ''
    parts.push(`expected ${y.expectedSellableUnitsTotal} total${guaranteed}`)
  }
  if (parts.length === 0 && y.estimated) parts.push('conservative minimum-yield input plan')
  return parts.length === 0 ? null : parts.join(' · ')
}

/** Hint for an estimated row: only a variable-output root's profit uses the expected mean. */
export function estimatedHint(row: CraftingRow): string {
  return row.expectedYield?.expectedOutputPerCraft != null
    ? 'Estimated: variable Mystic Forge output; profit uses the expected mean.'
    : 'Conservative minimum-yield input plan.'
}

/** Whether the backend limited this row's calculation by its work budget. */
export function isWorkLimited(row: CraftingRow): boolean {
  return row.resultAvailable && row.blockedReason === 'WORK_LIMIT'
}

/** Concise explanation of a work-limited row; uses only the backend's own craft count. */
export function incompleteNote(row: CraftingRow): string | null {
  if (!isWorkLimited(row)) return null
  const n = row.craftableCount ?? 0
  return n > 0
    ? `Incomplete calculation: at least ${n} ${n === 1 ? 'craft' : 'crafts'} calculated; this is not a proven maximum.`
    : 'Incomplete calculation: no craft was accepted, so profit is unknown. The output quote is still the actual quote.'
}
