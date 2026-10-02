import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import type { CraftingRow } from '@/api/types'
import { describeRowState } from './rowState'

/**
 * Search, sort and selection over the Discovery candidates the backend returned.
 *
 * All of it is presentation: rows are searched, ordered and pointed at, never recalculated and never
 * removed from the result set. In particular there is **no** profit filter. A discovery candidate with
 * zero or negative immediate profit stays listed, because profit is informational for Discovery and
 * not an eligibility rule (`DOMAIN_SPEC.md` 37) — Crafting Profit's own "hide profit ≤ 0" default is
 * that feature's, and importing it here would silently delete candidates the backend offered.
 *
 * Direct ingredient names are supplied on each row; recursive match IDs arrive separately from the
 * debounced cached-graph search and do not affect Discovery eligibility/acquisition calculations.
 *
 * The sortable keys are the columns the comparison list offers. Level sorting is one of them in both
 * directions and opens on highest first, because a recipe near the current rating is the one likely to
 * be useful for progression (`DOMAIN_SPEC.md` 38) — that is an ordering of one supplied value, not a
 * combined XP/profit score, of which this module computes none.
 */
export type DiscoverySortKey =
  | 'outputName'
  /** The recipe's own required level, which `web.dto.CraftingRowDto` carries as `minRating`. */
  | 'minRating'
  | 'buyCostCopper'
  | 'totalSellValueCopper'
  | 'totalProfitCopper'

export type SortDirection = 'asc' | 'desc'

export interface DiscoveryTableView {
  searchText: Ref<string>
  sortKey: Ref<DiscoverySortKey>
  sortDirection: Ref<SortDirection>

  /** Every row the search keeps, in the current sort order. */
  matchingRows: ComputedRef<CraftingRow[]>

  /** The selected recipe's identity — never a row number, which sorting and searching would move. */
  selectedRecipeId: Ref<number | null>
  /** The selected recipe as the *current* result set supplies it, or null when it is not in it. */
  selectedRow: ComputedRef<CraftingRow | null>
  /** True when the selected recipe is in the result set but the search is holding its row back. */
  selectionHiddenBySearch: ComputedRef<boolean>

  toggleSort(key: DiscoverySortKey): void
  select(recipeId: number): void
}

export function useDiscoveryTableView(
  rows: Ref<readonly CraftingRow[]>,
  recursiveMatchIds: Ref<ReadonlySet<number>> = ref(new Set())
): DiscoveryTableView {
  const searchText = ref('')
  const sortKey = ref<DiscoverySortKey>('minRating')
  const sortDirection = ref<SortDirection>('desc')
  const selectedRecipeId = ref<number | null>(null)

  const matchingRows = computed<CraftingRow[]>(() => {
    const matching = rows.value.filter((row) =>
      matchesSearch(row, searchText.value) || recursiveMatchIds.value.has(row.recipeId)
    )
    return matching.sort((left, right) => compareRows(left, right, sortKey.value, sortDirection.value))
  })

  /**
   * Read from the current rows rather than copied when the row was clicked, so a replacement
   * calculation cannot leave an earlier answer's numbers on screen under the newer result.
   */
  const selectedRow = computed<CraftingRow | null>(() =>
    selectedRecipeId.value === null
      ? null
      : (rows.value.find((row) => row.recipeId === selectedRecipeId.value) ?? null)
  )

  const selectionHiddenBySearch = computed<boolean>(() => {
    if (selectedRow.value === null) return false
    return !matchingRows.value.some((row) => row.recipeId === selectedRecipeId.value)
  })

  // A recipe the newest result set no longer contains stops being selected, rather than lying
  // dormant and silently reappearing if some later calculation happens to contain it again.
  watch(rows, (current) => {
    if (selectedRecipeId.value === null) return
    if (!current.some((row) => row.recipeId === selectedRecipeId.value)) selectedRecipeId.value = null
  })

  function toggleSort(key: DiscoverySortKey): void {
    if (sortKey.value === key) {
      sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
      return
    }
    sortKey.value = key
    sortDirection.value = defaultDirectionFor(key)
  }

  function select(recipeId: number): void {
    selectedRecipeId.value = recipeId
  }

  return {
    searchText,
    sortKey,
    sortDirection,
    matchingRows,
    selectedRecipeId,
    selectedRow,
    selectionHiddenBySearch,
    toggleSort,
    select
  }
}

function matchesSearch(row: CraftingRow, searchText: string): boolean {
  const needle = searchText.trim().toLowerCase()
  if (needle === '') return true

  return [
    row.outputName ?? '',
    ...(row.ingredientNames ?? []),
    row.disciplines,
    row.blockedReason ?? '',
    // The words the selected-result detail states for this row, so a search for what a state is
    // called finds it even though the comparison list carries no state column.
    describeRowState(row).label,
    String(row.recipeId),
    String(row.outputItemId),
    String(row.minRating)
  ]
    .join(' ')
    .toLowerCase()
    .includes(needle)
}

/**
 * Orders by one supplied value. A row the backend could not calculate has no value for the money and
 * count keys and sorts last in either direction rather than being treated as zero
 * (`DOMAIN_SPEC.md` 21); the recipe id breaks every tie, so the order is stable.
 */
function compareRows(
  left: CraftingRow,
  right: CraftingRow,
  key: DiscoverySortKey,
  direction: SortDirection
): number {
  const leftValue = sortValue(left, key)
  const rightValue = sortValue(right, key)

  if (leftValue === null && rightValue === null) return left.recipeId - right.recipeId
  if (leftValue === null) return 1
  if (rightValue === null) return -1

  const ordering =
    typeof leftValue === 'string' && typeof rightValue === 'string'
      ? leftValue.localeCompare(rightValue)
      : Number(leftValue) - Number(rightValue)

  if (ordering !== 0) return direction === 'asc' ? ordering : -ordering
  return left.recipeId - right.recipeId
}

function sortValue(row: CraftingRow, key: DiscoverySortKey): string | number | null {
  return key === 'outputName' ? row.outputName : row[key]
}

function defaultDirectionFor(key: DiscoverySortKey): SortDirection {
  return key === 'outputName' ? 'asc' : 'desc'
}
