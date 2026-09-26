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
