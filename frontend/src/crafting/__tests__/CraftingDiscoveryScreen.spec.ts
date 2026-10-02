import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { ApiRequestError } from '@/api/http'
import type { CraftingDiscoveryResponse } from '@/api/types'
import CraftingDiscoveryScreen from '../CraftingDiscoveryScreen.vue'
import DiscoveryTable from '../DiscoveryTable.vue'
import {
  DEFAULT_SETTINGS,
  DISCOVERY_SETTINGS,
  FakeCraftingApi,
  deferred,
  discoveryRows,
  echoedDiscoveryResponse,
  lessProfitableRow,
  lossRow,
  noResultRow,
  priceUnavailableRow,
  profitableRow,
  unnamedRow,
  zeroProfitRow
} from './fixtures'

/**
 * The Crafting Discovery page over its own two routes (STORY-WEB-012, `DOMAIN_SPEC.md` 2.2.2).
 *
 * These tests are about what the page *sends* and what it *shows*. Nothing here recomputes a domain
 * value: the fixtures' totals are deliberately not the products their parts would give, so a screen
 * that derived anything would disagree with them. The association rules for the selected recipe's
 * fresh detail are `discoveryResolutionAssociation.spec.ts`'s.
 */

/** The scope the selector's first character discipline produces, and its option id. */
const FIRST_SCOPE = { discipline: 'Armorsmith', characterName: 'Nbt Anch', rating: 500 }
const FIRST_SCOPE_ID = 'Armorsmith|500|Nbt Anch'
const SECOND_SCOPE_ID = 'Chef|400|Sat Anat'

/** Discovery's five submittable settings, as its own echo reports them. */
const DISCOVERY_SETTINGS_REQUEST = {
  useOwnMats: DISCOVERY_SETTINGS.useOwnMats,
  allowBuying: DISCOVERY_SETTINGS.allowBuying,
    listingSell: DISCOVERY_SETTINGS.listingSell,
  listingBuy: DISCOVERY_SETTINGS.listingBuy
}

async function openScreen(api: FakeCraftingApi): Promise<VueWrapper> {
  const wrapper = mount(CraftingDiscoveryScreen, { props: { api } })
  await flushPromises()
  return wrapper
}

function withRows(api: FakeCraftingApi, rows = discoveryRows): void {
  api.discoveryHandler = (request) => Promise.resolve(echoedDiscoveryResponse(request, rows))
}

function recipeNames(wrapper: VueWrapper): string[] {
  return wrapper.findAll('[data-test="discovery-row"] .recipe-name').map((element) => element.text())
}

function cells(wrapper: VueWrapper, test: string): string[] {
  return wrapper.findAll(`[data-test="${test}"]`).map((element) => element.text())
}

function normalized(wrapper: VueWrapper, selector: string): string {
  return wrapper.find(selector).text().replace(/\s+/g, ' ').trim()
}

/** Selects a recipe by name, never by its position in the current ordering. */
async function selectRecipe(wrapper: VueWrapper, recipeName: string): Promise<void> {
  const index = recipeNames(wrapper).indexOf(recipeName)
  if (index < 0) {
    throw new Error(`No row for "${recipeName}" — rows: ${recipeNames(wrapper).join(', ')}`)
  }
  await wrapper.findAll('[data-test="discovery-select-row"]')[index]?.trigger('click')
  await flushPromises()
}


function isChecked(wrapper: VueWrapper, test: string): boolean {
  return (wrapper.find(`[data-test="${test}"]`).element as HTMLInputElement).checked
}

function selectValue(wrapper: VueWrapper, test: string): string {
  return (wrapper.find(`[data-test="${test}"]`).element as HTMLSelectElement).value
}

describe('CraftingDiscoveryScreen', () => {
  it('uses one selected character as both the discovery scope and material source', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    expect(wrapper.findAll('[data-test="discovery-scope-selector"]')).toHaveLength(1)
    expect(wrapper.find('[data-test="discovery-inventory-selector"]').exists()).toBe(false)
    expect(api.discoveryRequests[0]).toEqual({ scope: FIRST_SCOPE })
    expect(api.discoveryRequests[0]).not.toHaveProperty('inventoryCharacterName')
  })

  it('has no cumulative budget or craft-count presentation and retains one-craft finances', async () => {
    const api = new FakeCraftingApi()
    withRows(api, [{ ...profitableRow, outputCount: 2, totalSellValueCopper: 600, totalProfitCopper: 510 }])
    const wrapper = await openScreen(api)
    await selectRecipe(wrapper, profitableRow.outputName ?? '')

    expect(wrapper.find('[data-test="discovery-setting-maxBuyCopper"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="discovery-craftable-column"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="discovery-detail-totals"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="discovery-detail-calculation"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="discovery-detail-output-quantity"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="discovery-detail-output-revenue"]').text()).toBe('6s 0c')
    expect(wrapper.find('[data-test="discovery-detail-profit"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('Crafts possible')
    expect(wrapper.text()).not.toContain('FOR ALL')
  })

  it('offers the shared TP quantity warning with calculated recipe values', async () => {
    const wrapper = await openScreen(new FakeCraftingApi())

    const trigger = wrapper.find('[data-test="tp-price-disclaimer-trigger"]')
    expect(trigger.attributes('aria-label')).toBe('Trading Post price warning')
    expect(wrapper.find('[data-test="tp-price-disclaimer-dialog"]').exists()).toBe(true)
  })

  // ---------------------------------------------- the scope inputs (acceptance criterion 2)

  it('buildsIndividualCharacterDisciplineChoicesFromTheSelectorFactsWithNoAllEntry', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreen(api)

    const options = wrapper.find('[data-test="discovery-scope-selector"]').findAll('option')
    // One entry per synced character discipline, each naming the rating the backend reported for it.
    expect(options.map((option) => option.text())).toEqual([
      'Armorsmith lvl 500 — Nbt Anch',
      'Chef lvl 400 — Sat Anat'
    ])
    expect(options.map((option) => option.attributes('value'))).toEqual([
      FIRST_SCOPE_ID,
      SECOND_SCOPE_ID
    ])
    // No All reading and no generic-discipline entry: Discovery has neither.
    expect(options.some((option) => option.text() === 'All')).toBe(false)
    expect(options.some((option) => option.attributes('value') === 'ALL')).toBe(false)
    expect(selectValue(wrapper, 'discovery-scope-selector')).toBe(FIRST_SCOPE_ID)
  })


  // ---------------------------------------- the Discovery contract (acceptance criterion 3)

  it('takesItsControlStateFromDiscoverysOwnEchoAndNotFromProfitsDefaults', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreen(api)

    // Discovery keeps its own buying default while sharing the player-facing price modes.
    expect(isChecked(wrapper, 'discovery-setting-allowBuying')).toBe(true)
    expect(DEFAULT_SETTINGS.allowBuying).toBe(false)
    expect(selectValue(wrapper, 'discovery-setting-listingSell')).toBe('instant')
    expect(selectValue(wrapper, 'discovery-setting-listingBuy')).toBe('instant')

  })

  it('offersOnlyTheFourSettingsAndNoDailyOrBudgetControl', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreen(api)

    // Neither Profit-only setting is reachable, and the fixed daily value is not offered as a choice.
    expect(wrapper.find('[data-test="discovery-setting-allowDailyCrafts"]').exists()).toBe(false)
    const controls = wrapper
      .find('[data-test="discovery-settings-form"]')
      .findAll('input, select')
      .map((element) => element.attributes('data-test'))
    expect(controls).toEqual([
      'discovery-setting-useOwnMats',
      'discovery-setting-allowBuying',
      'discovery-setting-listingSell',
      'discovery-setting-listingBuy'
    ])
  })

  it('submitsChangedSettingsAsExactlyThoseFourFields', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="discovery-setting-useOwnMats"]').setValue(false)
    await flushPromises()

    expect(api.discoveryRequests.at(1)).toEqual({
      scope: FIRST_SCOPE,
      settings: { ...DISCOVERY_SETTINGS_REQUEST, useOwnMats: false }
    })
    // The fixed daily value is not echoed back as an input the route has no field for.
    expect(Object.keys(api.discoveryRequests[1]?.settings ?? {})).not.toContain(
      'allowDailyCrafts'
    )
    expect(isChecked(wrapper, 'discovery-setting-useOwnMats')).toBe(false)

    await wrapper.find('[data-test="discovery-setting-listingBuy"]').setValue('listing')
    await flushPromises()

    expect(api.discoveryRequests.at(2)?.settings).toEqual({
      ...DISCOVERY_SETTINGS_REQUEST,
      useOwnMats: false,
      listingBuy: true
    })
  })

  it('keepsEveryCandidateTheBackendReturnedIncludingLossesAndOffersNoProfitFilter', async () => {
    const api = new FakeCraftingApi()
    withRows(api, [...discoveryRows, zeroProfitRow])

    const wrapper = await openScreen(api)

    // Nothing is reclassified or removed here: a loss and a break-even are discovery candidates.
    expect(recipeNames(wrapper)).toHaveLength(discoveryRows.length + 1)
    expect(recipeNames(wrapper)).toContain(lossRow.outputName)
    expect(recipeNames(wrapper)).toContain(zeroProfitRow.outputName)
    // Crafting Profit's own display filters are not imported as Discovery eligibility.
    expect(wrapper.find('[data-test="filter-non-positive-profit"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="filter-not-allowed"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="filter-zero-craftable"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="show-all"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="discovery-utility-region"]').exists()).toBe(false)
  })

  // ------------------------------------- the comparison list (acceptance criteria 1 and 4)

  it('comparesTheColumnsDiscoveryAsksForWithEachMoneyBasisStated', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreen(api)

    const headers = wrapper
      .findAll('[data-test="discovery-table"] thead th')
      .map((th) => th.text().replace(/[▲▼]/g, '').replace(/\s+/g, ' ').trim())
    expect(headers).toEqual([
      'Recipe',
      'Level recipe minimum',
      'Materials to buy',
      'Sell value',
      'Profit'
    ])
  })

  it('displaysTheSuppliedEconomicsUnchangedWithoutDerivingOrZeroingAnything', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreen(api)

    // Highest recipe level first, which is the order the list opens in.
    expect(recipeNames(wrapper)).toEqual([
      priceUnavailableRow.outputName,
      lossRow.outputName,
      lessProfitableRow.outputName,
      profitableRow.outputName,
      noResultRow.outputName
    ])
    expect(cells(wrapper, 'discovery-level')).toEqual(['400', '225', '150', '75', '0'])
    // 2222 is not 5 × 380 and 1650 is not 4 × 450: these are the backend's own totals.
    expect(cells(wrapper, 'discovery-sell-value')).toEqual([
      '—',
      '16s 50c',
      '17s 77c',
      '22s 22c',
      '—'
    ])
    expect(cells(wrapper, 'discovery-buy-cost')).toEqual(['—', '80s 0c', '90c', '2s 50c', '—'])
    // One attempt per candidate; the backend's one-attempt profit stays signed.
    expect(cells(wrapper, 'discovery-profit')).toEqual(['—', '-10s 0c', '+6s 0c', '+9s 0c', '—'])
    expect(wrapper.find('[data-test="discovery-craftable"]').exists()).toBe(false)
    // A value the backend did not supply stays missing rather than becoming zero.
    expect(cells(wrapper, 'discovery-profit')).not.toContain('0c')
    // No composite score and no derived shopping total anywhere in the list.
    const table = normalized(wrapper, '[data-test="discovery-table"]')
    expect(table).not.toContain('score')
    expect(table).not.toContain('XP')
  })

  it('keepsBlockedAndUnavailableRowsListedAndDistinguishable', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreen(api)

    // Neither row is dropped, and neither reads as an ordinary poor result.
    expect(cells(wrapper, 'discovery-row-diagnostic')).toEqual(['Price missing', 'No result'])
    expect(normalized(wrapper, '[data-test="discovery-table"]')).not.toContain('PRICE_UNAVAILABLE')

    await selectRecipe(wrapper, priceUnavailableRow.outputName as string)
    expect(wrapper.find('[data-test="discovery-detail-status"]').text()).toBe('Price missing')
    expect(wrapper.find('[data-test="discovery-detail-state-code"]').text()).toBe('PRICE_UNAVAILABLE')

    await selectRecipe(wrapper, noResultRow.outputName as string)
    expect(wrapper.find('[data-test="discovery-detail-status"]').text()).toBe('No result')
    // Absent lists stay "not supplied", which is not "nothing needs to be bought".
    expect(wrapper.find('[data-test="discovery-missing-items"]').exists()).toBe(false)
  })

  it('statesTheProfitBasisAndKeepsDisplayedPricesGrossWithTheFeeNote', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await selectRecipe(wrapper, profitableRow.outputName as string)

    expect(wrapper.find('[data-test="discovery-detail-profit"]').text()).toBe('+9s 0c')
    expect(wrapper.find('[data-test="discovery-detail-calculation"]').text()).toContain('Output sell value')
    expect(wrapper.find('[data-test="discovery-detail-calculation"]').text()).toContain('after 15% TP fees')
    const quote = wrapper.find('[data-test="discovery-detail-output-quote"]')
    expect(quote.findAll('dd').map((value) => value.text())).toEqual(['3s 60c', '4s 20c'])
  })

  it('showsALossWithItsSignAndBothSuppliedMaterialListsUnderTheirOwnBases', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await selectRecipe(wrapper, lossRow.outputName as string)

    expect(wrapper.find('[data-test="discovery-detail-profit"]').text()).toBe('-10s 0c')
    expect(wrapper.find('[data-test="discovery-detail-status"]').exists()).toBe(false)

    // Both supplied lists, each with the quantity the backend gave for its own basis. Read per part,
    // so an assertion cannot pass on a neighbouring line's text.
    const materialLines = (test: string): string[][] =>
      wrapper
        .findAll(`[data-test="${test}"]`)
        .map((item) => [
          item.find('.material-name').text().replace(/\s+/g, ' ').trim(),
          item.find('.material-quantity').text(),
          item.find('.material-price').text(),
          item.find('.material-total').text()
        ])

    expect(materialLines('discovery-missing-item')).toEqual([
      ['Silver Ore', '×2', 'Price / item: 24c', 'Total: 48c']
    ])
  })


  it('identifiesARecipeByItsItemIdWhenTheBackendSuppliedNoName', async () => {
    const api = new FakeCraftingApi()
    withRows(api, [unnamedRow])
    const wrapper = await openScreen(api)

    expect(recipeNames(wrapper)).toEqual(['Item #2303'])

    await selectRecipe(wrapper, 'Item #2303')
    // No wiki address is guessed from an id.
    expect(wrapper.find('[data-test="discovery-detail-wiki"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="discovery-detail-wiki-absent"]').exists()).toBe(false)
  })

  // ----------------------------------------------- sorting and search (criteria 4 and 5)

  it('sortsByRecipeLevelInBothDirectionsWithoutAskingTheBackendAgain', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    // Opens on highest level first, and the column says so to assistive technology.
    const levelHeader = () => wrapper.findAll('[data-test="discovery-table"] thead th').at(1)
    expect(levelHeader()?.attributes('aria-sort')).toBe('descending')

    await wrapper.find('[data-test="discovery-sort-minRating"]').trigger('click')

    expect(cells(wrapper, 'discovery-level')).toEqual(['0', '75', '150', '225', '400'])
    expect(levelHeader()?.attributes('aria-sort')).toBe('ascending')

    await wrapper.find('[data-test="discovery-sort-minRating"]').trigger('click')

    expect(cells(wrapper, 'discovery-level')).toEqual(['400', '225', '150', '75', '0'])
    expect(api.discoveryRequests).toHaveLength(1)
  })

  it('sortsBySuppliedOutputSellValueAndBySuppliedProfit', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="discovery-sort-totalSellValueCopper"]').trigger('click')

    // Supplied 2222, 1777, 1650, then the two the backend supplied none for. A browser deriving
    // revenue × count (1900, 1500, 1800) would put the loss second.
    expect(recipeNames(wrapper)).toEqual([
      profitableRow.outputName,
      lessProfitableRow.outputName,
      lossRow.outputName,
      priceUnavailableRow.outputName,
      noResultRow.outputName
    ])

    await wrapper.find('[data-test="discovery-sort-totalProfitCopper"]').trigger('click')

    expect(recipeNames(wrapper)).toEqual([
      profitableRow.outputName,
      lessProfitableRow.outputName,
      lossRow.outputName,
      priceUnavailableRow.outputName,
      noResultRow.outputName
    ])

    await wrapper.find('[data-test="discovery-sort-totalProfitCopper"]').trigger('click')

    // Ascending puts the loss first — and a row with no supplied profit still sorts last, not as 0.
    expect(recipeNames(wrapper)).toEqual([
      lossRow.outputName,
      lessProfitableRow.outputName,
      profitableRow.outputName,
      priceUnavailableRow.outputName,
      noResultRow.outputName
    ])
    expect(api.discoveryRequests).toHaveLength(1)
  })

  it('narrowsTheListByTextSearchWithoutRemovingAnythingFromTheResult', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="discovery-search"]').setValue('Soup')

    expect(recipeNames(wrapper)).toEqual([lessProfitableRow.outputName])
    expect(normalized(wrapper, '[data-test="discovery-summary"]')).toContain('Showing 1 of 5')

    // A recipe level and the words a state is called are both searchable.
    await wrapper.find('[data-test="discovery-search"]').setValue('225')
    expect(recipeNames(wrapper)).toEqual([lossRow.outputName])
    await wrapper.find('[data-test="discovery-search"]').setValue('Price missing')
    expect(recipeNames(wrapper)).toEqual([priceUnavailableRow.outputName])

    await wrapper.find('[data-test="discovery-search"]').setValue('no such recipe')

    const noMatches = wrapper.find('[data-test="discovery-no-matches"]')
    expect(noMatches.text()).toContain('The calculation returned 5 recipes and still holds all of them')
    expect(api.discoveryRequests).toHaveLength(1)
  })

  it('matchesDirectIngredientNamesWithoutRepeatingRowsOrRecalculating', async () => {
    const api = new FakeCraftingApi()
    withRows(api, [
      { ...profitableRow, ingredientNames: ['Fresh Cabbage', 'Onion'] },
      lessProfitableRow,
      { ...lossRow, outputName: 'Cabbage Tart', ingredientNames: ['Cabbage'] }
    ])
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="discovery-search"]').setValue('cAbBaGe')

    expect(recipeNames(wrapper)).toEqual(['Cabbage Tart', profitableRow.outputName])
    expect(api.discoveryRequests).toHaveLength(1)
  })

  it('showsIndirectIngredientMatchesFromTheCachedSearchWithoutRecalculating', async () => {
    const api = new FakeCraftingApi()
    api.ingredientSearchHandler = () => Promise.resolve([profitableRow.recipeId])
    withRows(api, [profitableRow, lessProfitableRow])
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="discovery-search"]').setValue('cabbage')
    await new Promise((resolve) => setTimeout(resolve, 300))

    expect(recipeNames(wrapper)).toEqual([profitableRow.outputName])
    expect(api.ingredientSearchRequests).toEqual(['cabbage'])
    expect(api.discoveryRequests).toHaveLength(1)
  })

  // ------------------------------------------------------- selection (criterion 5)

  it('selectsByRecipeFromAWholeRowAndFromAKeyboardOperableControl', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    expect(wrapper.find('[data-test="discovery-detail-placeholder"]').text()).toContain(
      'Choose a recipe'
    )

    // An ordinary button, so Tab reaches it and Enter or Space activates it.
    const control = wrapper.findAll('[data-test="discovery-select-row"]')[0]
    expect(control?.element.tagName).toBe('BUTTON')
    expect(control?.attributes('tabindex')).toBeUndefined()

    await selectRecipe(wrapper, lossRow.outputName as string)

    expect(wrapper.find('[data-test="discovery-detail-name"]').text()).toBe(lossRow.outputName)
    const selected = wrapper
      .findAll('[data-test="discovery-row"]')
      .filter((row) => row.classes('selected'))
    expect(selected).toHaveLength(1)
    expect(selected[0]?.attributes('aria-current')).toBe('true')
    expect(selected[0]?.text()).toContain('Selected')

    // A plain cell selects the same recipe, and one click is one selection.
    const table = wrapper.findComponent(DiscoveryTable)
    await wrapper.findAll('[data-test="discovery-row"]')[2]?.find('[data-test="discovery-level"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="discovery-detail-name"]').text()).toBe(
      lessProfitableRow.outputName
    )
    expect(table.emitted('select')).toEqual([[lossRow.recipeId], [lessProfitableRow.recipeId]])
  })

  it('keepsTheSelectedRecipeWhenSortingOrSearchingMovesItsRow', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)
    await selectRecipe(wrapper, lossRow.outputName as string)

    await wrapper.find('[data-test="discovery-sort-outputName"]').trigger('click')

    expect(wrapper.find('[data-test="discovery-detail-name"]').text()).toBe(lossRow.outputName)
    const selected = wrapper
      .findAll('[data-test="discovery-row"]')
      .filter((row) => row.classes('selected'))
    expect(selected[0]?.text()).toContain(lossRow.outputName as string)

    // Held back by the search, the detail stays reachable and says why its row is not listed.
    await wrapper.find('[data-test="discovery-search"]').setValue('Soup')
    expect(recipeNames(wrapper)).toEqual([lessProfitableRow.outputName])
    expect(wrapper.find('[data-test="discovery-detail-name"]').text()).toBe(lossRow.outputName)
    expect(wrapper.find('[data-test="discovery-detail-hidden"]').text()).toContain(
      'hidden by the current search'
    )
  })

  it('keepsSelectionScopeSearchAndSortThroughAnExplicitReload', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="discovery-scope-selector"]').setValue(SECOND_SCOPE_ID)
    await flushPromises()
    await wrapper.find('[data-test="discovery-setting-allowBuying"]').setValue(false)
    await flushPromises()
    await wrapper.find('[data-test="discovery-search"]').setValue('o')
    await wrapper.find('[data-test="discovery-sort-outputName"]').trigger('click')
    await selectRecipe(wrapper, lessProfitableRow.outputName as string)
    const orderBeforeReload = recipeNames(wrapper)

    await wrapper.find('[data-test="discovery-reload"]').trigger('click')
    await flushPromises()

    expect(api.discoveryRequests.at(3)).toEqual({
      scope: { discipline: 'Chef', characterName: 'Sat Anat', rating: 400 },
      settings: { ...DISCOVERY_SETTINGS_REQUEST, allowBuying: false }
    })
    expect(selectValue(wrapper, 'discovery-scope-selector')).toBe(SECOND_SCOPE_ID)
    expect((wrapper.find('[data-test="discovery-search"]').element as HTMLInputElement).value).toBe('o')
    expect(wrapper.find('[data-test="discovery-sort-outputName"]').text()).toContain('▲')
    expect(recipeNames(wrapper)).toEqual(orderBeforeReload)
    expect(wrapper.find('[data-test="discovery-detail-name"]').text()).toBe(
      lessProfitableRow.outputName
    )
  })

  it('clearsTheSelectionWhenTheNewResultSetNoLongerContainsThatRecipe', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)
    await selectRecipe(wrapper, lossRow.outputName as string)

    withRows(api, [profitableRow])
    await wrapper.find('[data-test="discovery-reload"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="discovery-detail-name"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="discovery-detail-placeholder"]').text()).toContain(
      'Choose a recipe'
    )
    expect(
      wrapper.findAll('[data-test="discovery-row"]').filter((row) => row.classes('selected'))
    ).toHaveLength(0)
  })

  it('showsTheReplacementCalculationsOwnNumbersUnderTheSameSelectedRecipe', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)
    await selectRecipe(wrapper, profitableRow.outputName as string)
    expect(wrapper.find('[data-test="discovery-detail-profit"]').text()).toBe('+9s 0c')

    withRows(api, [{ ...profitableRow, totalProfitCopper: 111 }, lessProfitableRow])
    await wrapper.find('[data-test="discovery-reload"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="discovery-detail-name"]').text()).toBe(profitableRow.outputName)
    expect(wrapper.find('[data-test="discovery-detail-profit"]').text()).toBe('+1s 11c')
  })

  // ------------------------------------------------- table states (criterion 5)

  it('tellsLoadingEmptySuccessFailureAndRetryApart', async () => {
    const slow = new FakeCraftingApi()
    const pending = deferred<CraftingDiscoveryResponse>()
    slow.discoveryHandler = () => pending.promise
    const loading = mount(CraftingDiscoveryScreen, { props: { api: slow } })
    await flushPromises()
    expect(loading.find('[data-test="discovery-loading"]').exists()).toBe(true)
    expect(loading.find('[data-test="discovery-table"]').exists()).toBe(false)
    expect(loading.find('[data-test="discovery-detail-placeholder"]').text()).toContain(
      'once the calculation has answered'
    )
    pending.resolve(echoedDiscoveryResponse(slow.discoveryRequests[0]!))
    await flushPromises()
    expect(loading.find('[data-test="discovery-table"]').exists()).toBe(true)

    const empty = new FakeCraftingApi()
    withRows(empty, [])
    const emptyWrapper = await openScreen(empty)
    // An empty success is the backend's own answer, not a failure and not "nothing was asked".
    expect(emptyWrapper.find('[data-test="discovery-empty"]').text()).toContain(
      'no discoverable recipes for this character and discipline'
    )
    expect(emptyWrapper.find('[data-test="discovery-table"]').exists()).toBe(false)
    expect(emptyWrapper.find('[data-test="discovery-request-error"]').exists()).toBe(false)
    expect(emptyWrapper.find('[data-test="discovery-detail-placeholder"]').text()).toContain(
      'nothing left to discover'
    )

    const failing = new FakeCraftingApi()
    failing.discoveryHandler = () =>
      Promise.reject(new ApiRequestError('The data store is unavailable.', 'DATA_STORE_UNAVAILABLE', 503))
    const failed = await openScreen(failing)
    expect(failed.find('[data-test="discovery-request-error"]').text()).toContain(
      'DATA_STORE_UNAVAILABLE'
    )
    expect(failed.find('[data-test="discovery-table"]').exists()).toBe(false)
    expect(failed.find('[data-test="discovery-empty"]').exists()).toBe(false)
    expect(failed.find('[data-test="discovery-detail-placeholder"]').text()).toContain(
      'No result was loaded'
    )

    // Retry is its own control, and a successful retry replaces the failure with the result.
    withRows(failing)
    await failed.find('[data-test="discovery-request-retry"]').trigger('click')
    await flushPromises()
    expect(failed.find('[data-test="discovery-request-error"]').exists()).toBe(false)
    expect(failed.findAll('[data-test="discovery-row"]')).toHaveLength(discoveryRows.length)
  })

  it('discardsALateAnswerThatBelongsToASupersededSelection', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)
    const slowSecondScope = deferred<CraftingDiscoveryResponse>()
    api.discoveryHandler = (request, callIndex) =>
      callIndex === 1
        ? slowSecondScope.promise
        : Promise.resolve(echoedDiscoveryResponse(request, [profitableRow]))

    await wrapper.find('[data-test="discovery-scope-selector"]').setValue(SECOND_SCOPE_ID)
    await flushPromises()
    await wrapper.find('[data-test="discovery-scope-selector"]').setValue(FIRST_SCOPE_ID)
    await flushPromises()

    slowSecondScope.resolve(
      echoedDiscoveryResponse(api.discoveryRequests[1]!, [lessProfitableRow, lossRow])
    )
    await flushPromises()

    // The newest selection's answer stands; the superseded one never reaches the screen.
    expect(recipeNames(wrapper)).toEqual([profitableRow.outputName])
    expect(normalized(wrapper, '[data-test="discovery-effective-scope"]')).toBe(
      '· Armorsmith lvl 500 — Nbt Anch'
    )
  })

  // --------------------------------------- page structure and icons (criteria 1 and 8)

  it('presentsOneTitledPageWithGroupedControlsAComparisonListAndItsOwnDetailArea', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreen(api)

    expect(wrapper.find('[data-page-heading]').text()).toBe('Crafting Discovery')

    // The corrected Profit grouping: a Calculation subgroup and a Displayed results subgroup in one
    // controls panel, with the search in the second — not a section of the results region.
    const calculation = wrapper.find('[data-test="discovery-calculation-controls"]')
    const display = wrapper.find('[data-test="discovery-display-controls"]')
    expect(calculation.find('legend').text()).toBe('Calculation')
    expect(display.find('legend').text()).toBe('Displayed results')
    expect(calculation.element.parentElement?.contains(display.element)).toBe(true)
    expect(display.element.contains(wrapper.find('[data-test="discovery-search"]').element)).toBe(true)
    expect(
      wrapper.find('[data-test="discovery-results-region"]').element.contains(display.element)
    ).toBe(false)

    expect(calculation.find('[data-test="discovery-scope-selector"]').exists()).toBe(true)
    expect(calculation.find('[data-test="discovery-settings-form"]').exists()).toBe(true)

    // The detail area is its own region beside the list, not a column of it.
    const detail = wrapper.find('[data-test="discovery-detail"]')
    expect(detail.exists()).toBe(true)
    expect(
      wrapper.find('[data-test="discovery-results-region"]').element.contains(detail.element)
    ).toBe(false)
    // Focusable, because it becomes its own scroll container on a wide viewport.
    expect(detail.attributes('tabindex')).toBe('0')
  })

  it('rendersIconsThroughTheSharedComponentUsingOnlyTheBackendsOwnUrl', async () => {
    const api = new FakeCraftingApi()
    withRows(api, [
      { ...profitableRow, iconUrl: '/api/items/1101/icon/abc.png' },
      lessProfitableRow
    ])

    const wrapper = await openScreen(api)

    // One shared icon per row, the supplied URL used verbatim and no URL built from an id.
    const icons = wrapper.findAll('[data-test="discovery-row"] [data-test="item-icon"]')
    expect(icons).toHaveLength(2)
    const images = wrapper.findAll('[data-test="discovery-row"] [data-test="item-icon-image"]')
    expect(images.map((image) => image.attributes('src'))).toEqual(['/api/items/1101/icon/abc.png'])
    expect(images[0]?.attributes('loading')).toBe('lazy')
    // The row without retained metadata gets the shared inline fallback, which asks for nothing.
    expect(
      wrapper.findAll('[data-test="discovery-row"] [data-test="item-icon-fallback"]')
    ).toHaveLength(1)
    // Decorative, so the selection control's accessible name stays the recipe alone.
    expect(images[0]?.attributes('alt')).toBe('')
    expect(images[0]?.attributes('aria-hidden')).toBe('true')
  })
})
