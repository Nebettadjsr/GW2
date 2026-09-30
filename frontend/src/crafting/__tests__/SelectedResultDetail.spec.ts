import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { CraftingProfitResolutionResponse, CraftingRow, EffectiveSettings } from '@/api/types'
import SelectedResultDetail from '../SelectedResultDetail.vue'
import type { SelectionHiddenReason } from '../useProfitTableView'
import type { ResolutionPhase } from '../useResolutionDetail'
import {
  DEFAULT_SETTINGS,
  budgetBlockedRow,
  lessProfitableRow,
  lossRow,
  movedReasonRows,
  noResultRow,
  noneCraftableRow,
  priceUnavailableRow,
  profitableRow,
  resolutionResponse,
  unnamedRow
} from './fixtures'

/** The resolution props of a panel whose detail has not been asked for; the default for most tests. */
const NO_RESOLUTION = {
  resolutionPhase: 'idle' as ResolutionPhase,
  resolutionDetail: null,
  resolutionFailure: null,
  resolutionRecipeId: null
}

function detailOf(
  row: CraftingRow | null,
  hiddenReason: SelectionHiddenReason = null,
  settings: EffectiveSettings | null = null
) {
  return mount(SelectedResultDetail, {
    props: { row, hiddenReason, settings, placeholder: 'Choose a recipe.', ...NO_RESOLUTION }
  })
}

function withPurchaseDetails(
  row: CraftingRow,
  prices: Record<number, { unit: number | null; total: number | null }>
): CraftingRow {
  return {
    ...row,
    missingToBuy: row.missingToBuy?.map((item) => ({
      ...item,
      purchaseUnitPriceCopper: prices[item.itemId]?.unit ?? null,
      totalPurchaseCostCopper: prices[item.itemId]?.total ?? null
    })) as CraftingRow['missingToBuy']
  } as CraftingRow
}

function selectedResultNormalText(detail: ReturnType<typeof detailOf>): string {
  const copy = detail.find('[data-test="selected-detail"]').element.cloneNode(true) as HTMLElement
  copy.querySelector('[data-test="detail-diagnostics"]')?.remove()
  return copy.textContent ?? ''
}

function detailWithResolution(
  row: CraftingRow,
  resolutionPhase: ResolutionPhase,
  resolutionDetail: CraftingProfitResolutionResponse | null = null,
  resolutionFailure: string | null = null
) {
  return mount(SelectedResultDetail, {
    props: {
      row,
      hiddenReason: null,
      settings: null,
      placeholder: 'Choose a recipe.',
      resolutionPhase,
      resolutionDetail,
      resolutionFailure,
      resolutionRecipeId: row.recipeId
    }
  })
}

/** A response for `recipeId`, echoing the inputs the default fixtures use. */
function responseFor(recipeId: number, overrides: Partial<CraftingProfitResolutionResponse> = {}) {
  return resolutionResponse({ recipeId, calculation: {} }, overrides)
}

describe('SelectedResultDetail', () => {
  it('saysWhyNothingIsShownWhenNoResultIsSelected', () => {
    const detail = detailOf(null)

    expect(detail.find('[data-test="detail-placeholder"]').text()).toBe('Choose a recipe.')
    expect(detail.find('[data-test="detail-per-craft"]').exists()).toBe(false)
  })

  it('labelsTheCalculatedItemsAndSuppliedEconomicTotals', () => {
    const detail = detailOf(profitableRow)

    const calculation = detail.find('[data-test="detail-calculation"]').text()
    expect(calculation).toContain('1 item sell price')
    expect(calculation).toContain('3s 80c')
    expect(calculation).toContain('Items')
    expect(calculation).toContain('5')
    expect(calculation).toContain('Bought materials')
    expect(detail.find('[data-test="detail-buy-cost"]').text()).toBe('2s 50c')
    expect(detail.find('[data-test="detail-total-profit"]').text()).toBe('+9s 0c')
    expect(detail.text()).toContain('For all 5 crafts counted')
    expect(detail.text()).not.toContain('For one craft')
  })

  it('rendersTheSuppliedTotalRatherThanCountTimesPerCraftProfit', () => {
    // 5 crafts at 100 copper each would be 5s 0c; the backend supplied 900, and 900 is what shows.
    const detail = detailOf(profitableRow)

    expect(detail.find('[data-test="detail-total-profit"]').text()).toBe('+9s 0c')
    expect(detail.find('[data-test="detail-total-profit"]').text()).not.toBe('+5s 0c')
  })

  it('rendersTheSuppliedTotalSellValueRatherThanRevenueTimesCount', () => {
    // 5 crafts at 380 copper of revenue each would be 19s 0c; the backend supplied 2222.
    const detail = detailOf(profitableRow)

    expect(detail.find('[data-test="detail-total-sell-value"]').text()).toBe('22s 22c')
    expect(detail.find('[data-test="detail-total-sell-value"]').text()).not.toBe('19s 0c')
    expect(detail.find('[data-test="detail-calculation"]').text()).toContain('Total sell value')
  })

  it('marksOnlyTheProfitFiguresAsBeingAfterTradingPostFees', () => {
    const detail = detailOf(profitableRow)

    // Total profit carries the fee note; this compact view does not repeat per-craft profit.
    expect(detail.find('[data-test="detail-total-profit-fee-note"]').text()).toBe('after 15% TP fees')
    expect(detail.findAll('.value-note')).toHaveLength(1)

    // The gross values keep their own labels with no note: output revenue, the total sell value and
    // the market quotes are the backend's gross figures and are never described as net of a fee.
    expect(detail.find('[data-test="detail-total-sell-value"]').text()).toBe('22s 22c')
    expect(detail.find('[data-test="detail-quote-heading"]').text()).toBe('Trading Post price / item')
    expect(detail.find('[data-test="detail-output-quote"]').text()).not.toContain('TP fees')

    // The profits themselves are still the supplied values; the note describes them, it does not
    // license this component to deduct anything of its own.
    expect(detail.find('[data-test="detail-total-profit"]').text()).toBe('+9s 0c')
  })

  it('keepsAZeroTotalSellValueApartFromAnUnsuppliedOne', () => {
    expect(detailOf(noneCraftableRow).find('[data-test="detail-total-sell-value"]').text()).toBe('0c')
    expect(detailOf(noResultRow).find('[data-test="detail-total-sell-value"]').text()).toBe('—')
    expect(detailOf(priceUnavailableRow).find('[data-test="detail-total-sell-value"]').text()).toBe('—')
  })

  it('displaysOutputItemsRatherThanRecipeExecutionsForMultiOutputRecipes', () => {
    const detail = detailOf({ ...profitableRow, craftableCount: 41, outputCount: 2 })

    expect(detail.find('[data-test="detail-calculation"]').text()).toContain('Items 82')
  })

  it('keepsGenericRowStateExplanationsOutOfTheNormalSelectedResult', () => {
    const rows = [
      ...movedReasonRows,
      profitableRow,
      noneCraftableRow,
      budgetBlockedRow,
      priceUnavailableRow,
      noResultRow,
      { ...profitableRow, blockedReason: null },
      { ...profitableRow, blockedReason: 'SOME_STATE_ADDED_LATER' }
    ]

    for (const row of rows) {
      const detail = detailOf(row, null, DEFAULT_SETTINGS)
      expect(detail.find('[data-test="detail-status"]').exists()).toBe(false)
      expect(detail.find('[data-test="detail-status-explanation"]').exists()).toBe(false)
      expect(detail.find('[data-test="detail-budget-context"]').exists()).toBe(false)
      expect(detail.find('[data-test="detail-affected-item"]').exists()).toBe(false)
    }
  })

  it('keepsRawKnownUnknownAndMissingRowStatesOnlyInCollapsedTechnicalDetails', () => {
    for (const [row, raw] of [
      [priceUnavailableRow, 'PRICE_UNAVAILABLE'],
      [{ ...profitableRow, blockedReason: 'SOME_STATE_ADDED_LATER' }, 'SOME_STATE_ADDED_LATER'],
      [{ ...profitableRow, blockedReason: null }, '—']
    ] as const) {
      const detail = detailOf(row as CraftingRow)
      expect(detail.find('[data-test="detail-state-code"]').text()).toBe(raw)
      expect(detail.find('[data-test="detail-diagnostics"]').attributes('open')).toBeUndefined()
      expect(selectedResultNormalText(detail)).not.toContain(raw)
    }
  })

  it('marksALossBySignAndToneTogether', () => {
    const detail = detailOf(lossRow)

    const total = detail.find('[data-test="detail-total-profit"]')
    expect(total.text()).toBe('-10s 0c')
    expect(total.classes()).toContain('money--loss')
  })

  it('keepsNullApartFromZeroOnAnUnavailableResult', () => {
    const detail = detailOf(noResultRow)

    expect(detail.find('[data-test="detail-total-profit"]').text()).toBe('—')
    expect(detail.find('[data-test="detail-buy-cost"]').text()).toBe('—')
    expect(detail.find('[data-test="detail-total-profit"]').classes()).toContain('money--none')
    expect(detail.find('[data-test="detail-state-code"]').text()).toBe('—')
    expect(detail.find('[data-test="detail-output-quote"]').text()).toContain('No quote supplied')
  })

  it('keepsMaterialPriceInformationWhileRowStateIsTechnicalOnly', () => {
    const detail = detailOf(priceUnavailableRow)

    expect(detail.find('[data-test="detail-state-code"]').text()).toBe('PRICE_UNAVAILABLE')
    expect(detail.find('[data-test="detail-diagnostics"]').attributes('open')).toBeUndefined()

    expect(detail.find('[data-test="missing-item"]').text()).toContain('Charged Core')
    expect(detail.find('[data-test="missing-item"]').text()).toContain('Price / item: Unavailable')
  })

  it('showsOnlyTheSelectedPurchasePriceAndAuthoritativePurchaseTotal', () => {
    const detail = detailOf(withPurchaseDetails(lossRow, {
      55: { unit: 24, total: 192 },
      56: { unit: null, total: null }
    }))

    const forAll = detail.findAll('[data-test="missing-item"]').map((item) => item.text())
    expect(forAll).toHaveLength(2)
    expect(forAll[0]).toContain('Silver Ore')
    expect(forAll[0]).toContain('×8')
    expect(forAll[0]).toContain('Price / item: 24c')
    expect(forAll[0]).toContain('Total: 1s 92c')
    expect(forAll[0]).not.toContain('20c')
    expect(forAll[0]).not.toContain('Instant buy')
    expect(forAll[0]).not.toContain('Instant sell')
    // No name supplied for this material: its id identifies it, and nothing is invented.
    expect(forAll[1]).toContain('Item #56')
    expect(forAll[1]).toContain('Price / item: Unavailable')
    expect(forAll[1]).toContain('Total: Unavailable')

    // The list is under the basis the contract gives it; purchase totals come from the backend.
    expect(detail.text()).toContain('For all 4 crafts counted')

    // DOMAIN_SPEC 2.1.1 removes the separate one-further-craft section. The row still supplies
    // `missingToBuyOne` (2 × Silver Ore here), and none of it reaches the screen.
    expect(detail.find('[data-test="missing-one"]').exists()).toBe(false)
    expect(detail.find('[data-test="missing-one-item"]').exists()).toBe(false)
    expect(detail.find('[data-test="missing-one-none"]').exists()).toBe(false)
    expect(detail.text()).not.toContain('For one further craft')
    // The one-further-craft quantity of the same material was 2; only the counted-craft 8 is shown.
    expect(forAll[0]).not.toContain('×2')
  })

  it('rendersTheBackendSelectedPriceForInstantAndListingAcquisitionModes', () => {
    const instant = detailOf(withPurchaseDetails(lossRow, {
      55: { unit: 24, total: 192 }
    }), null, { ...DEFAULT_SETTINGS, listingBuy: false })
    const listing = detailOf(withPurchaseDetails(lossRow, {
      55: { unit: 20, total: 160 }
    }), null, { ...DEFAULT_SETTINGS, listingBuy: true })

    expect(instant.find('[data-test="missing-item"]').text()).toContain('Price / item: 24c')
    expect(instant.find('[data-test="missing-item"]').text()).toContain('Total: 1s 92c')
    expect(listing.find('[data-test="missing-item"]').text()).toContain('Price / item: 20c')
    expect(listing.find('[data-test="missing-item"]').text()).toContain('Total: 1s 60c')
  })

  it('keepsZeroPurchasePriceDistinctFromMissingPrice', () => {
    const zeroPriceRow = withPurchaseDetails(lossRow, {
      55: { unit: 0, total: 0 }
    })
    const unknownPriceRow = withPurchaseDetails(lossRow, {
      55: { unit: null, total: null }
    })

    expect(detailOf(zeroPriceRow).find('[data-test="missing-item"]').text())
      .toContain('Price / item: 0c')
    expect(detailOf(zeroPriceRow).find('[data-test="missing-item"]').text())
      .toContain('Total: 0c')
    expect(detailOf(unknownPriceRow).find('[data-test="missing-item"]').text())
      .toContain('Price / item: Unavailable')
    expect(detailOf(unknownPriceRow).find('[data-test="missing-item"]').text())
      .toContain('Total: Unavailable')
  })

  it('keepsAnEmptyMaterialListApartFromAnUnsuppliedOne', () => {
    expect(detailOf(profitableRow).find('[data-test="missing-all-none"]').text()).toBe(
      'Nothing needs to be bought.'
    )
    expect(detailOf(noResultRow).find('[data-test="missing-all-none"]').text()).toBe(
      'Not supplied for this recipe.'
    )
    // A row the field is absent from altogether is that same third answer, not an empty list.
    const withoutTheField = { ...lossRow } as Partial<CraftingRow>
    delete withoutTheField.missingToBuy
    expect(
      detailOf(withoutTheField as CraftingRow).find('[data-test="missing-all-none"]').text()
    ).toBe('Not supplied for this recipe.')

    // A blocked row still lists the purchase the backend reported for the crafts it counted.
    const blocked = detailOf(priceUnavailableRow)
    expect(blocked.findAll('[data-test="missing-item"]')).toHaveLength(1)
    expect(blocked.find('[data-test="missing-item"]').text()).toContain('Charged Core')
    expect(blocked.find('[data-test="missing-item"]').text()).toContain('Price / item: Unavailable')
  })

  it('identifiesARecipeWithNoSuppliedNameByItsItemId', () => {
    expect(detailOf(unnamedRow).find('[data-test="detail-name"]').text()).toBe('Item #2303')
  })

  it('emphasizesBuyCostAsACostRatherThanAnOutcome', () => {
    const buyCost = detailOf(profitableRow).find('[data-test="detail-buy-cost"]')

    // The word "cost" is on the label, so the treatment is never the only thing saying so.
    expect(detailOf(profitableRow).find('[data-test="detail-calculation"]').text()).toContain(
      'Bought materials'
    )
    expect(buyCost.classes()).toContain('calc-negative')
    // A cost is not a gain or a loss: it uses the expense treatment, not profit's money tone.
    expect(buyCost.classes()).not.toContain('money--gain')
    expect(buyCost.classes()).not.toContain('money--loss')
    expect(buyCost.text()).toBe('2s 50c')
  })

  it('namesTheTradingPostQuoteWithTheConciseItemLabel', () => {
    // The recipe produces 2 per craft, so per item and per craft are a distinction worth keeping —
    // the label carries it, and the output quantity stays with the crafting values (DOMAIN_SPEC
    // 2.1.1), instead of a paragraph explaining both.
    const detail = detailOf(lessProfitableRow)

    expect(detail.find('[data-test="detail-quote-heading"]').text()).toBe('Trading Post price / item')
    const quote = detail.find('[data-test="detail-output-quote"]').text()
      expect(quote).not.toContain('Instant buy')
    expect(quote).toContain('4s 70c')
    expect(quote).toContain('Instant sell')
    expect(detail.find('[data-test="detail-quote-basis"]').exists()).toBe(false)

    expect(detail.find('[data-test="detail-calculation"]').text()).toContain('Items')
    expect(detail.find('[data-test="detail-calculation"]').text()).toContain('6')
  })

  it('linksToTheWikiOnlyWhenTheBackendNamedTheItem', () => {
    const named = detailOf(profitableRow).find('[data-test="detail-wiki"]')
    expect(named.attributes('href')).toBe('https://wiki.guildwars2.com/wiki/Iron%20Ingot')
    expect(named.attributes('rel')).toBe('noopener noreferrer')

    // No name, no reliable target: the link is omitted rather than guessed from the item id.
    const unnamed = detailOf(unnamedRow)
    expect(unnamed.find('[data-test="detail-wiki"]').exists()).toBe(false)
    expect(unnamed.find('[data-test="detail-wiki-absent"]').text()).toContain(
      'the backend supplied no name for this item'
    )
  })

  it('keepsTheTableCalculationApartFromTheFreshExplanation', () => {
    const fresh = responseFor(profitableRow.recipeId, {
      row: { ...profitableRow, totalProfitCopper: 1_500, craftableCount: 7, buyCostCopper: 4_000 }
    })
    const detail = detailWithResolution(profitableRow, 'ready', fresh)

    // The table's own values are what the detail shows, and the fresh answer does not replace them
    // — nor does it print a second set beside them any more (`DOMAIN_SPEC.md` 2.1.1).
    expect(detail.find('[data-test="detail-total-profit"]').text()).toBe('+9s 0c')
    expect(detail.find('[data-test="detail-buy-cost"]').text()).toBe('2s 50c')
    expect(detail.find('[data-test="resolution-total-profit"]').exists()).toBe(false)
    expect(detail.find('[data-test="resolution-buy-cost"]').exists()).toBe(false)
    expect(detail.text()).not.toContain('For all 7 crafts this fresh calculation counted')

    // Its resolution represents the full selected-result quantity, without repeating a basis note.
    expect(detail.find('[data-test="resolution-tree"]').exists()).toBe(true)
    expect(detail.find('[data-test="resolution-basis"]').exists()).toBe(false)
  })

  it('keepsTheBackendsResolutionLiteralsInTheTechnicalDisclosure', () => {
    const detail = detailWithResolution(profitableRow, 'ready', responseFor(profitableRow.recipeId))

    expect(detail.find('[data-test="detail-consistency"]').text()).toBe('FRESH_CALCULATION')
    expect(detail.find('[data-test="detail-tree-basis"]').text()).toBe('SELECTED_RESULT_OUTPUT_QUANTITY')
    expect(detail.find('[data-test="detail-tree-status"]').text()).toBe('AVAILABLE')
    expect(detail.find('[data-test="detail-calculated-at"]').text()).toBe('2026-09-25T10:20:30Z')
    // Still secondary: the disclosure is closed, so none of it is in the primary workflow.
    expect(detail.find('[data-test="detail-diagnostics"]').attributes('open')).toBeUndefined()
  })

  it('namesTheDisplayControlThatKeepsTheSelectedRecipeOutOfTheList', () => {
    expect(detailOf(profitableRow, null).find('[data-test="detail-hidden"]').exists()).toBe(false)

    const filtered = detailOf(profitableRow, 'filtered').find('[data-test="detail-hidden"]').text()
    expect(filtered).toContain('not in the displayed list')
    expect(filtered).toContain('search or display filters')

    const limited = detailOf(profitableRow, 'limited').find('[data-test="detail-hidden"]').text()
    expect(limited).toContain('not in the displayed list')
    expect(limited).toContain('Show all')

    // Either way the recipe keeps its identity and its own values rather than being cleared.
    expect(detailOf(profitableRow, 'limited').find('[data-test="detail-name"]').text()).toBe(
      profitableRow.outputName
    )
  })
})
