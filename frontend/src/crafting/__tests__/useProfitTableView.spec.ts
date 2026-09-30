import { describe, expect, it } from 'vitest'
import { nextTick, shallowRef, type Ref } from 'vue'
import type { CraftingRow } from '@/api/types'
import { INITIAL_MAX_DISPLAYED, useProfitTableView, type ProfitTableView } from '../useProfitTableView'
import {
  allRows,
  lessProfitableRow,
  lossRow,
  noResultCarryingNotAllowedRow,
  noResultRow,
  notAllowedRow,
  priceUnavailableRow,
  profitableRow,
  zeroProfitRow
} from './fixtures'

function viewOf(rows: readonly CraftingRow[] = allRows) {
  const view = useProfitTableView(shallowRef(rows))
  // Most of these cases are about something other than DOMAIN_SPEC 2.1.1's three default filters,
  // which would otherwise remove the zero-count, not-allowed and unprofitable rows before they got
  // to look at them. That all three open enabled is covered by the defaults case below.
  view.hideZeroCraftable.value = false
  view.hideNotAllowed.value = false
  view.hideNonPositiveProfit.value = false
  return view
}

/** The view exactly as a freshly opened screen has it, defaults included. */
function freshViewOf(rows: readonly CraftingRow[]): ProfitTableView {
  return useProfitTableView(shallowRef(rows))
}

function idsOf(view: ProfitTableView): number[] {
  return view.visibleRows.value.map((row) => row.recipeId)
}

function replaceableRows(rows: readonly CraftingRow[] = allRows): Ref<readonly CraftingRow[]> {
  return shallowRef(rows)
}

describe('useProfitTableView', () => {
  it('sortsByTheSuppliedTotalProfitNotByAnyDerivedValue', () => {
    // profitableRow has the lower per-craft profit but the higher supplied total; a frontend that
    // multiplied count by per-craft profit would order these two the other way round.
    const view = viewOf([lessProfitableRow, profitableRow])

    expect(view.visibleRows.value.map((row) => row.recipeId)).toEqual([
      profitableRow.recipeId,
      lessProfitableRow.recipeId
    ])
  })

  it('sortsByTheSuppliedTotalSellValueNotByRevenueTimesCount', () => {
    // lessProfitableRow's revenue x count (1500) is below profitableRow's (1900), while its supplied
    // total sell value (1777) is *below* profitableRow's (2222) too - so a derived ordering and a
    // supplied one agree here. lossRow is the discriminator: 4 x 450 = 1800 derived, 1650 supplied,
    // which puts it above lessProfitableRow only if the browser worked the value out for itself.
    const view = viewOf([lessProfitableRow, lossRow, profitableRow])

    view.toggleSort('totalSellValueCopper')
    expect(view.sortDirection.value).toBe('desc')
    expect(idsOf(view)).toEqual([profitableRow.recipeId, lessProfitableRow.recipeId, lossRow.recipeId])
  })

  it('sortsByTheSuppliedOwnMaterialsValueOnItsOwnPerCraftBasis', () => {
    const view = viewOf([lessProfitableRow, lossRow, profitableRow])

    view.toggleSort('matsSellValueCopper')

    // 700, 120, 40 - each row's own per-craft figure, never multiplied up into a total.
    expect(idsOf(view)).toEqual([lossRow.recipeId, profitableRow.recipeId, lessProfitableRow.recipeId])
  })

  it('keepsRowsWithoutASuppliedTotalSellValueLastInEitherDirection', () => {
    const view = viewOf()

    view.toggleSort('totalSellValueCopper')
    const descending = idsOf(view)
    view.toggleSort('totalSellValueCopper')
    const ascending = idsOf(view)

    expect(descending.slice(-2)).toEqual([priceUnavailableRow.recipeId, noResultRow.recipeId])
    expect(ascending.slice(-2)).toEqual([priceUnavailableRow.recipeId, noResultRow.recipeId])
  })

  it('keepsRowsWithoutASuppliedValueLastInEitherDirection', () => {
    const view = viewOf()

    const descending = view.visibleRows.value.map((row) => row.recipeId)
    view.toggleSort('totalProfitCopper')
    const ascending = view.visibleRows.value.map((row) => row.recipeId)

    expect(descending.slice(-2)).toEqual([priceUnavailableRow.recipeId, noResultRow.recipeId])
    expect(ascending.slice(-2)).toEqual([priceUnavailableRow.recipeId, noResultRow.recipeId])
    expect(ascending.slice(0, 2)).toEqual([lessProfitableRow.recipeId, profitableRow.recipeId])
  })

  it('togglesDirectionOnTheActiveColumnAndResetsItOnAnother', () => {
    const view = viewOf()

    view.toggleSort('totalProfitCopper')
    expect(view.sortDirection.value).toBe('asc')

    view.toggleSort('outputName')
    expect(view.sortKey.value).toBe('outputName')
    expect(view.sortDirection.value).toBe('asc')
    expect(view.visibleRows.value.map((row) => row.outputName)).toEqual([
      'Bowl of Soup',
      'Iron Ingot',
      'Mystic Curio',
      'Unknown Trinket'
    ])
  })

  it('searchesRecipeNameDisciplineIdsAndReportedState', () => {
    const view = viewOf()

    view.searchText.value = 'soup'
    expect(view.visibleRows.value.map((row) => row.recipeId)).toEqual([lessProfitableRow.recipeId])

    view.searchText.value = 'PRICE_UNAVAILABLE'
    expect(view.visibleRows.value.map((row) => row.recipeId)).toEqual([priceUnavailableRow.recipeId])

    view.searchText.value = '1404'
    expect(view.visibleRows.value.map((row) => row.recipeId)).toEqual([noResultRow.recipeId])

    view.searchText.value = '   '
    expect(view.visibleRows.value).toHaveLength(allRows.length)
  })

  it('searchesTheSuppliedBackendStateCodeWithoutAddingGenericStateLabels', () => {
    const view = viewOf()

    view.searchText.value = 'PRICE_UNAVAILABLE'

    expect(view.visibleRows.value.map((row) => row.recipeId)).toEqual([priceUnavailableRow.recipeId])

    view.searchText.value = 'price missing'
    expect(view.visibleRows.value).toHaveLength(0)
  })

  it('keepsTheSelectionThroughSortingAndFiltering', () => {
    const view = viewOf()
    view.select(lessProfitableRow.recipeId)

    view.toggleSort('outputName')
    expect(view.selectedRow.value?.recipeId).toBe(lessProfitableRow.recipeId)
    expect(view.selectionHiddenReason.value).toBeNull()

    // Filtered out of the table, but still the selected recipe of the loaded result set.
    view.searchText.value = 'Iron'
    expect(view.selectedRow.value?.recipeId).toBe(lessProfitableRow.recipeId)
    expect(view.selectionHiddenReason.value).toBe('filtered')
  })

  it('readsTheSelectedRowFromTheCurrentResultSetRatherThanACopy', () => {
    const rows = replaceableRows([profitableRow])
    const view = useProfitTableView(rows)
    view.select(profitableRow.recipeId)
    expect(view.selectedRow.value?.totalProfitCopper).toBe(profitableRow.totalProfitCopper)

    rows.value = [{ ...profitableRow, totalProfitCopper: 4_242, craftableCount: 1 }]

    expect(view.selectedRow.value?.totalProfitCopper).toBe(4_242)
    expect(view.selectedRow.value?.craftableCount).toBe(1)
  })

  it('clearsASelectionTheReplacementCalculationNoLongerContains', async () => {
    const rows = replaceableRows([profitableRow, lessProfitableRow])
    const view = useProfitTableView(rows)
    view.select(lessProfitableRow.recipeId)

    rows.value = [profitableRow, lossRow]
    await nextTick()

    expect(view.selectedRecipeId.value).toBeNull()
    expect(view.selectedRow.value).toBeNull()

    // And it stays cleared rather than reappearing when a later answer contains that recipe again.
    rows.value = [profitableRow, lessProfitableRow]
    await nextTick()
    expect(view.selectedRow.value).toBeNull()
  })

  it('startsWithAllThreeFiltersOnAndA250Maximum', () => {
    const view = freshViewOf([...allRows, notAllowedRow, zeroProfitRow])

    expect(view.hideZeroCraftable.value).toBe(true)
    expect(view.hideNotAllowed.value).toBe(true)
    expect(view.hideNonPositiveProfit.value).toBe(true)
    expect(view.maxDisplayed.value).toBe(INITIAL_MAX_DISPLAYED)
    expect(INITIAL_MAX_DISPLAYED).toBe(250)
    expect(view.showAll.value).toBe(false)

    // One row per filter is removed, and none of the three touches an unsupplied value:
    // priceUnavailableRow is the only supplied count of 0 and noResultRow's count is null, while
    // both rows' null profit is not "0 or less" and neither is a recipe reported as not allowed.
    expect(idsOf(view)).not.toContain(priceUnavailableRow.recipeId)
    expect(idsOf(view)).not.toContain(notAllowedRow.recipeId)
    expect(idsOf(view)).not.toContain(zeroProfitRow.recipeId)
    expect(idsOf(view)).toContain(noResultRow.recipeId)

    // Each filter is reversible on its own: switching one off brings back only what it hid.
    view.hideNotAllowed.value = false
    expect(idsOf(view)).toContain(notAllowedRow.recipeId)
    expect(idsOf(view)).not.toContain(zeroProfitRow.recipeId)
    expect(idsOf(view)).not.toContain(priceUnavailableRow.recipeId)
  })

  it('matchesTheZeroCountFilterOnZeroExactly', () => {
    // Not supplied and, were the backend ever to report one, a value below zero are both their own
    // answer; only a reported count of exactly 0 is the one DOMAIN_SPEC 2.1.1 hides.
    const belowZero = { ...profitableRow, recipeId: 31, craftableCount: -1 }
    const view = freshViewOf([noResultRow, belowZero, priceUnavailableRow])

    expect(view.hideZeroCraftable.value).toBe(true)
    expect(idsOf(view)).toEqual(expect.arrayContaining([noResultRow.recipeId, belowZero.recipeId]))
    expect(idsOf(view)).not.toContain(priceUnavailableRow.recipeId)
  })

  it('hidesOnlyTheNotAllowedStateAndNeverATechnicalFailure', () => {
    const view = viewOf([profitableRow, notAllowedRow, priceUnavailableRow, noResultCarryingNotAllowedRow])

    view.hideNotAllowed.value = true

    // The blocked-but-different state and both unavailable results stay; only the reported
    // RECIPE_NOT_ALLOWED of a row the backend actually calculated goes.
    expect(idsOf(view)).toEqual(
      expect.arrayContaining([
        profitableRow.recipeId,
        priceUnavailableRow.recipeId,
        noResultCarryingNotAllowedRow.recipeId
      ])
    )
    expect(idsOf(view)).not.toContain(notAllowedRow.recipeId)
  })

  it('hidesZeroAndNegativeProfitButKeepsAnUnsuppliedOne', () => {
    const view = viewOf([profitableRow, zeroProfitRow, lossRow, priceUnavailableRow])

    view.hideNonPositiveProfit.value = true

    // priceUnavailableRow's profitCopper is null: not supplied is not "0 or less".
    expect(idsOf(view)).toEqual([profitableRow.recipeId, priceUnavailableRow.recipeId])
  })

  it('combinesTheThreeFiltersWithTheSearch', () => {
    const view = viewOf([profitableRow, zeroProfitRow, notAllowedRow, priceUnavailableRow, noResultRow])

    view.hideZeroCraftable.value = true
    view.hideNotAllowed.value = true
    view.hideNonPositiveProfit.value = true
    expect(idsOf(view)).toEqual([profitableRow.recipeId, noResultRow.recipeId])

    view.searchText.value = 'Iron'
    expect(idsOf(view)).toEqual([profitableRow.recipeId])

    view.searchText.value = 'nothing matches this'
    expect(view.matchingRows.value).toHaveLength(0)
    expect(view.visibleRows.value).toHaveLength(0)
  })

  it('limitsTheDisplayedRowsAfterSearchingFilteringAndSortingNotBefore', () => {
    const view = viewOf([profitableRow, lessProfitableRow, lossRow, zeroProfitRow])
    view.hideNonPositiveProfit.value = true
    // Matching, in the default descending total-profit order: 900, 600.
    expect(idsOf(view)).toEqual([profitableRow.recipeId, lessProfitableRow.recipeId])

    view.setMaxDisplayed(1)

    expect(idsOf(view)).toEqual([profitableRow.recipeId])
    expect(view.matchingRows.value).toHaveLength(2)
    expect(view.hiddenByLimitCount.value).toBe(1)

    // Show all reveals the rest of *this* matching set, not the rows the filter removed.
    view.showAll.value = true
    expect(idsOf(view)).toEqual([profitableRow.recipeId, lessProfitableRow.recipeId])
    expect(view.hiddenByLimitCount.value).toBe(0)
    expect(view.maxDisplayed.value).toBe(1)
  })

  it('refusesAMaximumThatCouldNotDisplayAnything', () => {
    const view = viewOf()

    expect(view.setMaxDisplayed(0)).toBe(false)
    expect(view.setMaxDisplayed(-5)).toBe(false)
    expect(view.setMaxDisplayed(Number.NaN)).toBe(false)
    expect(view.maxDisplayed.value).toBe(INITIAL_MAX_DISPLAYED)

    expect(view.setMaxDisplayed(3)).toBe(true)
    expect(view.maxDisplayed.value).toBe(3)
    expect(view.setMaxDisplayed(2.7)).toBe(true)
    expect(view.maxDisplayed.value).toBe(2)
  })

  it('tellsAFilteredOutSelectionApartFromOneBeyondTheDisplayMaximum', () => {
    const view = viewOf([profitableRow, lessProfitableRow, notAllowedRow])
    view.select(lessProfitableRow.recipeId)
    expect(view.selectionHiddenReason.value).toBeNull()

    view.setMaxDisplayed(1)
    expect(view.selectionHiddenReason.value).toBe('limited')
    expect(view.selectedRow.value?.recipeId).toBe(lessProfitableRow.recipeId)

    view.showAll.value = true
    view.searchText.value = 'Iron'
    expect(view.selectionHiddenReason.value).toBe('filtered')
    expect(view.selectedRow.value?.recipeId).toBe(lessProfitableRow.recipeId)

    // A filter, not the search: the reason is the same and the selection still survives.
    view.searchText.value = ''
    view.select(notAllowedRow.recipeId)
    view.hideNotAllowed.value = true
    expect(view.selectionHiddenReason.value).toBe('filtered')
    expect(view.selectedRow.value?.recipeId).toBe(notAllowedRow.recipeId)
  })
})
