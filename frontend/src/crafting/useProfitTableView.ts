import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import type { CraftingRow } from '@/api/types'
import { describeRowState } from './rowState'

/**
 * Search, display filters, sort, display limit and selection over the rows the backend returned.
 *
 * All of it is presentation: rows are filtered, ordered, cut off and pointed at, never recalculated.
 * `totalSellValueCopper` and `totalProfitCopper` in particular are sorted by the values the backend
 * supplied, never by a product of a count and a per-craft figure. A row the backend could not
 * calculate sorts last in either direction rather than being treated as zero (DOMAIN_SPEC 21).
 *
 * Nothing here reaches the backend. Changing any of these values cannot issue a calculation, cannot
 * change what was asked for, and cannot change DOMAIN_SPEC 28's per-recipe simulation cap — the
 * whole result set stays loaded, and a hidden row is hidden, never dropped or reinterpreted
 * (DOMAIN_SPEC 2.1.1).
 *
 * The sortable keys are exactly the columns the comparison table offers
 * (`FRONTEND_UX_GUIDELINES.md` 4); the supplementary values live in the selected-result detail,
 * which is not sorted.
 */
export type SortKey =
  | 'outputName'
  | 'disciplines'
  | 'craftableCount'
  | 'matsSellValueCopper'
  | 'profitCopper'
  | 'totalSellValueCopper'
  | 'totalProfitCopper'

export type SortDirection = 'asc' | 'desc'

/** Why a selected recipe is not among the displayed rows; null when it is, or nothing is selected. */
export type SelectionHiddenReason = 'filtered' | 'limited' | null

/** DOMAIN_SPEC 2.1.1's initial maximum displayed recipe count. */
export const INITIAL_MAX_DISPLAYED = 250

/** The smallest maximum that still displays something; a smaller one is refused, not applied. */
export const MIN_MAX_DISPLAYED = 1

/** The one `craft.BlockedReason` name the "not allowed" filter matches, and nothing else. */
const RECIPE_NOT_ALLOWED = 'RECIPE_NOT_ALLOWED'

export interface ProfitTableView {
  searchText: Ref<string>
  sortKey: Ref<SortKey>
  sortDirection: Ref<SortDirection>

  /** DOMAIN_SPEC 2.1.1's required filter, the only one enabled initially. */
  hideZeroCraftable: Ref<boolean>
  hideNotAllowed: Ref<boolean>
  hideNonPositiveProfit: Ref<boolean>
  /** The maximum number of rows displayed while `showAll` is off; always a whole number >= 1. */
  maxDisplayed: Ref<number>
  /** "Show all": removes the display limit for the full matching set, keeping `maxDisplayed`. */
  showAll: Ref<boolean>

  /** Every row the search and the display filters keep, in the current sort order — not cut off. */
  matchingRows: ComputedRef<CraftingRow[]>
  /** The rows actually displayed: `matchingRows` cut off at the maximum unless Show all is on. */
  visibleRows: ComputedRef<CraftingRow[]>
  /** How many matching rows the display limit is currently holding back. */
  hiddenByLimitCount: ComputedRef<number>

  /** The selected recipe's identity — never a row number, which sorting and filtering would move. */
  selectedRecipeId: Ref<number | null>
  /** The selected recipe as the *current* result set supplies it, or null when it is not in it. */
  selectedRow: ComputedRef<CraftingRow | null>
  /** Why the selected recipe is outside the displayed list, so the detail can say which it is. */
  selectionHiddenReason: ComputedRef<SelectionHiddenReason>

  toggleSort(key: SortKey): void
  select(recipeId: number): void
  /** Applies a typed maximum. Returns false — and changes nothing — for anything unusable. */
  setMaxDisplayed(value: number): boolean
}

export function useProfitTableView(rows: Ref<readonly CraftingRow[]>): ProfitTableView {
  const searchText = ref('')
  const sortKey = ref<SortKey>('totalProfitCopper')
  const sortDirection = ref<SortDirection>('desc')
  const selectedRecipeId = ref<number | null>(null)

  const hideZeroCraftable = ref(true)
  const hideNotAllowed = ref(false)
  const hideNonPositiveProfit = ref(false)
  const maxDisplayed = ref(INITIAL_MAX_DISPLAYED)
  const showAll = ref(false)

  /**
   * Search and filters run before the sort, and the sort before the cut-off, so Show all reveals the
   * rest of *this* matching set rather than an unrelated unfiltered one.
   */
  const matchingRows = computed<CraftingRow[]>(() => {
    const matching = rows.value.filter(
      (row) => matchesSearch(row, searchText.value) && matchesDisplayFilters(row)
    )
    return matching.sort((left, right) => compareRows(left, right, sortKey.value, sortDirection.value))
  })

  const visibleRows = computed<CraftingRow[]>(() =>
    showAll.value ? matchingRows.value : matchingRows.value.slice(0, maxDisplayed.value)
  )

  const hiddenByLimitCount = computed<number>(() => matchingRows.value.length - visibleRows.value.length)

  /**
   * Each test reads one supplied field and treats "not supplied" as its own answer. A count the
   * backend did not supply is not a count of zero, a recipe with no calculated result is not a
   * recipe reported as not allowed, and an absent profit is not a profit of zero or less — so a
   * filter only ever hides a row the backend positively described that way (DOMAIN_SPEC 2.1.1).
   */
  function matchesDisplayFilters(row: CraftingRow): boolean {
    if (hideZeroCraftable.value && row.craftableCount === 0) return false
    if (hideNotAllowed.value && row.resultAvailable && row.blockedReason === RECIPE_NOT_ALLOWED) return false
    if (hideNonPositiveProfit.value && row.profitCopper !== null && row.profitCopper <= 0) return false
    return true
  }

  /**
   * Read from the current rows rather than copied when the row was clicked, so a replacement
   * calculation cannot leave an earlier answer's numbers on screen under the newer result. A
   * superseded response never reaches `rows` at all, so it cannot reach the detail either.
   */
  const selectedRow = computed<CraftingRow | null>(() =>
    selectedRecipeId.value === null
      ? null
      : (rows.value.find((row) => row.recipeId === selectedRecipeId.value) ?? null)
  )

  /**
   * A selected recipe the display controls hide keeps its identity and its detail; the screen says
   * it is outside the displayed list instead of silently pairing the detail with another row. Only
   * a result set that no longer contains the recipe clears the selection (the watcher below).
   */
  const selectionHiddenReason = computed<SelectionHiddenReason>(() => {
    const recipeId = selectedRecipeId.value
    if (recipeId === null || selectedRow.value === null) return null
    if (visibleRows.value.some((row) => row.recipeId === recipeId)) return null
    return matchingRows.value.some((row) => row.recipeId === recipeId) ? 'limited' : 'filtered'
  })

  // A recipe the newest result set no longer contains stops being selected, rather than lying
  // dormant and silently reappearing if some later calculation happens to contain it again.
  watch(rows, (current) => {
    if (selectedRecipeId.value === null) return
    if (!current.some((row) => row.recipeId === selectedRecipeId.value)) selectedRecipeId.value = null
  })

  function toggleSort(key: SortKey): void {
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

  function setMaxDisplayed(value: number): boolean {
    if (!Number.isFinite(value)) return false
    const whole = Math.trunc(value)
    if (whole < MIN_MAX_DISPLAYED) return false
    maxDisplayed.value = whole
    return true
  }

  return {
    searchText,
    sortKey,
    sortDirection,
    hideZeroCraftable,
    hideNotAllowed,
    hideNonPositiveProfit,
    maxDisplayed,
    showAll,
    matchingRows,
    visibleRows,
    hiddenByLimitCount,
    selectedRecipeId,
    selectedRow,
    selectionHiddenReason,
    toggleSort,
    select,
    setMaxDisplayed
  }
}

function matchesSearch(row: CraftingRow, searchText: string): boolean {
  const needle = searchText.trim().toLowerCase()
  if (needle === '') return true

  return [
    row.outputName ?? '',
    row.disciplines,
    row.blockedReason ?? '',
    // The words the selected-result detail states for this row, so a search for what a state is
    // called finds it even though the comparison table no longer carries a State column.
    describeRowState(row).label,
    String(row.recipeId),
    String(row.outputItemId)
  ]
    .join(' ')
    .toLowerCase()
    .includes(needle)
}

function compareRows(left: CraftingRow, right: CraftingRow, key: SortKey, direction: SortDirection): number {
  const leftValue = sortValue(left, key)
  const rightValue = sortValue(right, key)

  // Rows without a backend value stay at the bottom in both directions.
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

function sortValue(row: CraftingRow, key: SortKey): string | number | null {
  switch (key) {
    case 'outputName':
      return row.outputName
    case 'disciplines':
      return row.disciplines
    default:
      return row[key]
  }
}

function defaultDirectionFor(key: SortKey): SortDirection {
  return key === 'outputName' || key === 'disciplines' ? 'asc' : 'desc'
}
