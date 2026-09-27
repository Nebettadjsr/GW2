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

  it('statesTheBasisOfEachMoneyValueTheContractDefines', () => {
    const detail = detailOf(profitableRow)

    const perCraft = detail.find('[data-test="detail-per-craft"]').text()
    expect(perCraft).toContain('Output revenue')
    expect(perCraft).toContain('3s 80c')
    expect(detail.find('[data-test="detail-profit-per-craft"]').text()).toBe('+1s 0c')

    // buyCostCopper is a total for every craft counted, so it belongs under the totals heading.
    const totals = detail.find('[data-test="detail-totals"]').text()
    expect(totals).toContain('2s 50c')
    expect(detail.find('[data-test="detail-total-profit"]').text()).toBe('+9s 0c')
    expect(detail.text()).toContain('For all 5 crafts counted')
    expect(detail.text()).toContain('For one craft')
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
    expect(detail.find('[data-test="detail-totals"]').text()).toContain('Total sell value')
  })

  it('keepsAZeroTotalSellValueApartFromAnUnsuppliedOne', () => {
    expect(detailOf(noneCraftableRow).find('[data-test="detail-total-sell-value"]').text()).toBe('0c')
    expect(detailOf(noResultRow).find('[data-test="detail-total-sell-value"]').text()).toBe('—')
    expect(detailOf(priceUnavailableRow).find('[data-test="detail-total-sell-value"]').text()).toBe('—')
  })

  it('explainsEveryReasonMovedOutOfTheTableRightHereInsteadOfDroppingIt', () => {
    for (const row of movedReasonRows) {
      const detail = detailOf(row)
      const explanation = detail.find('[data-test="detail-status-explanation"]').text()

      // The crafts already counted are not negated by the reason the next one could not run.
      expect(explanation).toContain('Further crafting is blocked')
      expect(explanation).toContain('5 crafts already counted stay valid')
      expect(explanation).not.toContain(row.blockedReason as string)
      // The raw code is still there, as secondary technical information only.
      expect(detail.find('[data-test="detail-state-code"]').text()).toBe(row.blockedReason)
      // The supplied cost of buying materials is beside it; nothing missing is filled in.
      expect(detail.find('[data-test="detail-buy-cost"]').text()).toBe('2s 50c')
    }
  })

  it('namesTheConfiguredMaximumBuyOnlyForABudgetRestrictionAndOnlyWhenSupplied', () => {
    const withSettings = detailOf(budgetBlockedRow, null, DEFAULT_SETTINGS)

    expect(withSettings.find('[data-test="detail-budget-context"]').text()).toContain('1g 0s 0c')
    expect(withSettings.find('[data-test="detail-buy-cost"]').text()).toBe('95s 0c')
    expect(withSettings.find('[data-test="detail-status-explanation"]').text()).toContain(
      '3 crafts already counted stay valid'
    )

    // No echoed settings, so no figure is stated - the browser has no maximum of its own to offer.
    expect(detailOf(budgetBlockedRow, null, null).find('[data-test="detail-budget-context"]').exists()).toBe(
      false
    )
    // And it is not repeated under a restriction the maximum buy has nothing to do with.
    expect(
      detailOf(priceUnavailableRow, null, DEFAULT_SETTINGS).find('[data-test="detail-budget-context"]').exists()
    ).toBe(false)
  })

  it('marksALossBySignAndToneTogether', () => {
    const detail = detailOf(lossRow)

    const total = detail.find('[data-test="detail-total-profit"]')
    expect(total.text()).toBe('-10s 0c')
    expect(total.classes()).toContain('money--loss')
    expect(detail.find('[data-test="detail-profit-per-craft"]').classes()).toContain('money--loss')
  })

  it('keepsNullApartFromZeroOnAnUnavailableResult', () => {
    const detail = detailOf(noResultRow)

    expect(detail.find('[data-test="detail-total-profit"]').text()).toBe('—')
    expect(detail.find('[data-test="detail-buy-cost"]').text()).toBe('—')
    expect(detail.find('[data-test="detail-total-profit"]').classes()).toContain('money--none')
    expect(detail.find('[data-test="detail-status"]').text()).toBe('No result')
    expect(detail.find('[data-test="detail-output-quote"]').text()).toBe('No quote supplied')
  })

  it('explainsABlockedStateInWordsAndKeepsTheCodeInTheDisclosure', () => {
    const detail = detailOf(priceUnavailableRow)

    expect(detail.find('[data-test="detail-status-explanation"]').text()).toContain(
      'a required price is not available'
    )
    expect(detail.find('[data-test="detail-status-explanation"]').text()).not.toContain('PRICE_UNAVAILABLE')
    expect(detail.find('[data-test="detail-state-code"]').text()).toBe('PRICE_UNAVAILABLE')
    expect(detail.find('[data-test="detail-diagnostics"]').attributes('open')).toBeUndefined()
  })

  it('separatesTheTwoSuppliedMaterialListsByTheirBasis', () => {
    const detail = detailOf(lossRow)

    const forAll = detail.findAll('[data-test="missing-item"]').map((item) => item.text())
    expect(forAll).toHaveLength(2)
    expect(forAll[0]).toContain('Silver Ore')
    expect(forAll[0]).toContain('×8')
    // No name supplied for this material: its id identifies it, and nothing is invented.
    expect(forAll[1]).toContain('Item #56')
    expect(forAll[1]).toContain('No price supplied')

    const forOne = detail.findAll('[data-test="missing-one-item"]').map((item) => item.text())
    expect(forOne).toHaveLength(1)
    expect(forOne[0]).toContain('×2')
    // Each list is under its own basis heading, and no total is produced from either.
    expect(detail.text()).toContain('For all 4 crafts counted')
    expect(detail.text()).toContain('For one further craft')
    expect(detail.text()).not.toContain('no shopping total is worked out here')
  })

  it('keepsAnEmptyMaterialListApartFromAnUnsuppliedOne', () => {
    expect(detailOf(profitableRow).find('[data-test="missing-all-none"]').text()).toBe(
      'Nothing needs to be bought.'
    )
    expect(detailOf(noResultRow).find('[data-test="missing-all-none"]').text()).toBe(
      'Not supplied for this recipe.'
    )
  })

  it('identifiesARecipeWithNoSuppliedNameByItsItemId', () => {
    expect(detailOf(unnamedRow).find('[data-test="detail-name"]').text()).toBe('Item #2303')
  })

  it('emphasizesBuyCostAsACostRatherThanAnOutcome', () => {
    const buyCost = detailOf(profitableRow).find('[data-test="detail-buy-cost"]')

    // The word "cost" is on the label, so the treatment is never the only thing saying so.
    expect(detailOf(profitableRow).find('[data-test="detail-totals"]').text()).toContain(
      'Cost of materials to buy'
    )
    expect(buyCost.classes()).toContain('money--cost')
    // A cost is not a gain or a loss: it must not borrow the signed profit treatment.
    expect(buyCost.classes()).not.toContain('money--gain')
    expect(buyCost.text()).toBe('2s 50c')
  })

  it('namesTheTradingPostQuoteWithTheConciseItemLabel', () => {
    // The recipe produces 2 per craft, so per item and per craft are a distinction worth keeping —
    // the label carries it, and the output quantity stays with the crafting values (DOMAIN_SPEC
    // 2.1.1), instead of a paragraph explaining both.
    const detail = detailOf(lessProfitableRow)

    expect(detail.find('[data-test="detail-quote-heading"]').text()).toBe('Trading Post price / item')
    const quote = detail.find('[data-test="detail-output-quote"]').text()
    expect(quote).toContain('Instant buy')
    expect(quote).toContain('4s 70c')
    expect(quote).toContain('Instant sell')
    expect(detail.find('[data-test="detail-quote-basis"]').exists()).toBe(false)

    const perCraft = detail.find('[data-test="detail-per-craft"]').text()
    expect(perCraft).toContain('Output quantity')
    expect(perCraft).toContain('2')
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

    // The table's own values are untouched by the fresh answer, and both are on screen at once.
    expect(detail.find('[data-test="detail-total-profit"]').text()).toBe('+9s 0c')
    expect(detail.find('[data-test="detail-buy-cost"]').text()).toBe('2s 50c')
    expect(detail.find('[data-test="resolution-total-profit"]').text()).toBe('+15s 0c')
    expect(detail.find('[data-test="resolution-buy-cost"]').text()).toBe('40s 0c')
    expect(detail.find('[data-test="resolution-row-basis"]').text()).toContain(
      'has not been changed by this'
    )
    expect(detail.text()).toContain('For all 7 crafts this fresh calculation counted')
  })

  it('keepsTheBackendsResolutionLiteralsInTheTechnicalDisclosure', () => {
    const detail = detailWithResolution(profitableRow, 'ready', responseFor(profitableRow.recipeId))

    expect(detail.find('[data-test="detail-consistency"]').text()).toBe('FRESH_CALCULATION')
    expect(detail.find('[data-test="detail-tree-basis"]').text()).toBe('SINGLE_OUTPUT_REQUIREMENT')
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
