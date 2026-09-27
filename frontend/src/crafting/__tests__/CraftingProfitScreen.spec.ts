import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { ApiRequestError } from '@/api/http'
import type { CraftingProfitResponse, SelectorOptions } from '@/api/types'
import CraftingProfitScreen from '../CraftingProfitScreen.vue'
import CraftingProfitTable from '../CraftingProfitTable.vue'
import {
  DEFAULT_SETTINGS,
  FakeCraftingApi,
  allRows,
  cycleDetectedRow,
  deferred,
  echoedProfitResponse,
  lessProfitableRow,
  lossRow,
  movedReasonRows,
  noResultRow,
  nonTradeableMaterialRow,
  nonTradeableMaterialTree,
  notAllowedRow,
  priceUnavailableRow,
  profitResponse,
  profitableRow,
  resolutionResponse,
  selectorOptions,
  zeroProfitRow
} from './fixtures'

async function openScreen(api: FakeCraftingApi): Promise<VueWrapper> {
  const wrapper = mount(CraftingProfitScreen, { props: { api } })
  await flushPromises()
  return wrapper
}

/**
 * Opens the screen and switches DOMAIN_SPEC 2.1.1's three default filters off, so a test about
 * something else still sees every row the backend returned. The filters' own behavior — including
 * that all three are on to begin with — is covered by the display-control tests further down.
 */
async function openScreenListingEveryRow(api: FakeCraftingApi): Promise<VueWrapper> {
  const wrapper = await openScreen(api)
  await wrapper.find('[data-test="filter-zero-craftable"]').setValue(false)
  await wrapper.find('[data-test="filter-not-allowed"]').setValue(false)
  await wrapper.find('[data-test="filter-non-positive-profit"]').setValue(false)
  return wrapper
}

/** The minimal per-row diagnostics on screen. Most rows have none, so this is usually short. */
function rowDiagnostics(wrapper: VueWrapper): string[] {
  return wrapper.findAll('[data-test="row-diagnostic"]').map((element) => element.text())
}

function totalProfits(wrapper: VueWrapper): string[] {
  return wrapper.findAll('[data-test="total-profit"]').map((element) => element.text())
}

function totalSellValues(wrapper: VueWrapper): string[] {
  return wrapper.findAll('[data-test="total-sell-value"]').map((element) => element.text())
}

function recipeNames(wrapper: VueWrapper): string[] {
  return wrapper.findAll('[data-test="profit-row"] .recipe-name').map((element) => element.text())
}

/** Selects a row by the recipe it belongs to, never by its position in the current ordering. */
async function selectRecipe(wrapper: VueWrapper, recipeName: string): Promise<void> {
  const index = recipeNames(wrapper).indexOf(recipeName)
  if (index < 0) throw new Error(`No row for "${recipeName}" — rows: ${recipeNames(wrapper).join(', ')}`)
  await wrapper.findAll('[data-test="select-row"]')[index]?.trigger('click')
}

function detailTotalProfit(wrapper: VueWrapper): string {
  return wrapper.find('[data-test="detail-total-profit"]').text()
}

/**
 * Commits a typed maximum the way the control takes it: on the committed entry, not per keystroke.
 * The value is written straight onto the element because the control binds `:value` one way, which
 * is what lets it refuse an entry by putting the effective maximum back.
 */
async function typeMaximum(wrapper: VueWrapper, value: string): Promise<void> {
  const input = wrapper.find('[data-test="max-displayed"]')
  ;(input.element as HTMLInputElement).value = value
  await input.trigger('change')
}

function maximumInput(wrapper: VueWrapper): HTMLInputElement {
  return wrapper.find('[data-test="max-displayed"]').element as HTMLInputElement
}

function isChecked(wrapper: VueWrapper, test: string): boolean {
  return (wrapper.find(`[data-test="${test}"]`).element as HTMLInputElement).checked
}

describe('CraftingProfitScreen', () => {
  it('asksTheBackendForItsOwnDefaultsWhenOpened', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreenListingEveryRow(api)

    expect(api.profitRequests).toEqual([{}])
    expect(wrapper.findAll('[data-test="profit-row"]')).toHaveLength(allRows.length)
    expect(wrapper.find('[data-test="scope-selector"]').findAll('option').at(0)?.text()).toBe('All')
    expect((wrapper.find('[data-test="scope-selector"]').element as HTMLSelectElement).value).toBe('ALL')
  })

  it('takesTheSettingsControlStateFromTheResponseEcho', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () =>
      Promise.resolve(profitResponse(allRows, 'ALL', { ...DEFAULT_SETTINGS, allowBuying: true, maxBuyCopper: 250 }))

    const wrapper = await openScreen(api)

    expect((wrapper.find('[data-test="setting-allowBuying"]').element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.find('[data-test="setting-maxBuyCopper"]').element as HTMLInputElement).value).toBe('250')
  })

  it('requestsFreshResultsForAChangedScope', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="scope-selector"]').setValue('CHARACTER_DISCIPLINE|Armorsmith|500|Nbt Anch')
    await flushPromises()

    expect(api.profitRequests.at(1)).toEqual({
      scope: { kind: 'CHARACTER_DISCIPLINE', discipline: 'Armorsmith', characterName: 'Nbt Anch', rating: 500 },
      settings: DEFAULT_SETTINGS
    })
  })

  it('requestsFreshResultsForChangedSettingsWithinTheCurrentScope', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="scope-selector"]').setValue('DISCIPLINE|Chef')
    await flushPromises()
    await wrapper.find('[data-test="setting-allowBuying"]').setValue(true)
    await flushPromises()

    expect(api.profitRequests.at(2)).toEqual({
      scope: { kind: 'DISCIPLINE', discipline: 'Chef' },
      settings: { ...DEFAULT_SETTINGS, allowBuying: true }
    })
  })

  it('keepsScopeSettingsSearchAndSortAcrossAManualReload', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="scope-selector"]').setValue('DISCIPLINE|Chef')
    await flushPromises()
    await wrapper.find('[data-test="setting-useOwnMats"]').setValue(false)
    await flushPromises()
    await wrapper.find('[data-test="search"]').setValue('o')
    await wrapper.find('[data-test="sort-outputName"]').trigger('click')
    const sortedBeforeReload = wrapper.findAll('[data-test="profit-row"]').map((row) => row.text())

    await wrapper.find('[data-test="reload"]').trigger('click')
    await flushPromises()

    expect(api.profitRequests.at(3)).toEqual({
      scope: { kind: 'DISCIPLINE', discipline: 'Chef' },
      settings: { ...DEFAULT_SETTINGS, useOwnMats: false }
    })
    expect((wrapper.find('[data-test="search"]').element as HTMLInputElement).value).toBe('o')
    expect(wrapper.findAll('[data-test="profit-row"]').map((row) => row.text())).toEqual(sortedBeforeReload)
  })

  it('fallsBackToTheDefaultScopeWhenTheSelectedOptionIsNoLongerOffered', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="scope-selector"]').setValue('CHARACTER_DISCIPLINE|Chef|400|Sat Anat')
    await flushPromises()

    const withoutThatCharacter: SelectorOptions = {
      ...selectorOptions,
      characterOptionCount: 1,
      characterOptions: selectorOptions.characterOptions.slice(0, 1)
    }
    api.selectorHandler = () => Promise.resolve(withoutThatCharacter)
    await wrapper.find('[data-test="reload"]').trigger('click')
    await flushPromises()

    expect((wrapper.find('[data-test="scope-selector"]').element as HTMLSelectElement).value).toBe('ALL')
    expect(api.profitRequests.at(2)).toEqual({ scope: { kind: 'ALL' }, settings: DEFAULT_SETTINGS })
  })

  it('showsALoadingStateWhileTheRequestIsInFlight', async () => {
    const api = new FakeCraftingApi()
    const pending = deferred<ReturnType<typeof profitResponse>>()
    api.profitHandler = () => pending.promise

    const wrapper = mount(CraftingProfitScreen, { props: { api } })
    await flushPromises()

    expect(wrapper.find('[data-test="loading"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="profit-table"]').exists()).toBe(false)

    pending.resolve(profitResponse())
    await flushPromises()

    expect(wrapper.find('[data-test="loading"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="profit-table"]').exists()).toBe(true)
  })

  it('showsAnEmptyStateWhenTheBackendReturnsNoRows', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () => Promise.resolve(profitResponse([]))

    const wrapper = await openScreen(api)

    expect(wrapper.find('[data-test="empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="profit-table"]').exists()).toBe(false)
  })

  it('showsTheBackendErrorCodeAndNoRowsWhenTheRequestFails', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () =>
      Promise.reject(new ApiRequestError('The data store is unavailable.', 'DATA_STORE_UNAVAILABLE', 503))

    const wrapper = await openScreen(api)

    expect(wrapper.find('[data-test="request-error"]').text()).toContain('DATA_STORE_UNAVAILABLE')
    expect(wrapper.find('[data-test="profit-table"]').exists()).toBe(false)
  })

  it('reportsAFailedSelectorLoadWithoutLosingTheDefaultResults', async () => {
    const api = new FakeCraftingApi()
    api.selectorHandler = () => Promise.reject(new ApiRequestError('unavailable', 'DATA_STORE_UNAVAILABLE', 503))

    const wrapper = await openScreenListingEveryRow(api)

    expect(wrapper.find('[data-test="selector-error"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-test="profit-row"]')).toHaveLength(allRows.length)
  })

  it('discardsALateResponseThatBelongsToASupersededSelection', async () => {
    const api = new FakeCraftingApi()
    const slowFirstScope = deferred<ReturnType<typeof profitResponse>>()
    const wrapper = await openScreen(api)

    api.profitHandler = (_request, callIndex) =>
      callIndex === 1 ? slowFirstScope.promise : Promise.resolve(profitResponse([profitableRow], 'DISCIPLINE'))

    await wrapper.find('[data-test="scope-selector"]').setValue('DISCIPLINE|Chef')
    await flushPromises()
    await wrapper.find('[data-test="scope-selector"]').setValue('DISCIPLINE|Huntsman')
    await flushPromises()

    slowFirstScope.resolve(profitResponse([lessProfitableRow, noResultRow], 'DISCIPLINE'))
    await flushPromises()

    expect(wrapper.findAll('[data-test="profit-row"]')).toHaveLength(1)
    expect(wrapper.find('[data-test="profit-row"]').text()).toContain(profitableRow.outputName)
  })

  it('displaysTheSuppliedTotalProfitAndSortsByIt', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreen(api)

    // 900 for 5 crafts at 100 each, and 600 for 3 at 400: both are the backend's own totals.
    expect(totalProfits(wrapper).slice(0, 2)).toEqual(['+9s 0c', '+6s 0c'])
  })

  it('displaysTheSuppliedTotalSellValueAndOwnMaterialsValueWithoutWorkingEitherOut', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreen(api)

    // 5 x 380 would be 19s 0c and 3 x 500 would be 15s 0c; the backend supplied 2222 and 1777.
    expect(totalSellValues(wrapper).slice(0, 2)).toEqual(['22s 22c', '17s 77c'])
    // The own-materials figure is the supplied per-craft one, never scaled by the craftable count.
    expect(
      wrapper.findAll('[data-test="own-materials"]').slice(0, 2).map((cell) => cell.text())
    ).toEqual(['1s 20c', '40c'])
  })

  it('sortsByTheSuppliedTotalSellValueWhenThatColumnIsChosen', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () => Promise.resolve(profitResponse([lessProfitableRow, lossRow, profitableRow]))

    const wrapper = await openScreenListingEveryRow(api)
    await wrapper.find('[data-test="sort-totalSellValueCopper"]').trigger('click')

    // Supplied 2222, 1777, 1650. A browser deriving revenue x count would put lossRow (1800) second.
    expect(recipeNames(wrapper)).toEqual([
      profitableRow.outputName,
      lessProfitableRow.outputName,
      lossRow.outputName
    ])
  })

  it('movesTheOrdinaryRestrictionsOutOfTheRowsAndIntoTheSelectedResult', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () => Promise.resolve(profitResponse(movedReasonRows))

    const wrapper = await openScreenListingEveryRow(api)

    // Every one of the five is on screen as an ordinary row, with no label of its own anywhere.
    expect(recipeNames(wrapper)).toHaveLength(movedReasonRows.length)
    expect(rowDiagnostics(wrapper)).toEqual([])
    const tableText = wrapper.find('[data-test="profit-table"]').text()
    for (const row of movedReasonRows) {
      expect(tableText).not.toContain(row.blockedReason as string)
    }
    expect(tableText).not.toContain('Buying is off')
    expect(tableText).not.toContain('Recipe not allowed')

    // Choosing one states the reason in words, with its craftable count left standing.
    await selectRecipe(wrapper, 'Restricted Recipe 5')
    expect(wrapper.find('[data-test="detail-status-explanation"]').text()).toContain(
      'costs more than the maximum buy setting allows'
    )
    expect(wrapper.find('[data-test="detail-status-explanation"]').text()).toContain(
      '5 crafts already counted stay valid'
    )
    // The backend's echoed maximum buy, not a number this screen decided on.
    expect(wrapper.find('[data-test="detail-budget-context"]').text()).toContain('1g 0s 0c')
  })

  it('keepsTheTemporaryCycleDiagnosticOnItsRowAndTheRawCodeOutOfIt', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () =>
      Promise.resolve(profitResponse([profitableRow, cycleDetectedRow, ...movedReasonRows]))

    const wrapper = await openScreen(api)

    expect(rowDiagnostics(wrapper)).toEqual(['Recipe loop'])
    expect(wrapper.find('[data-test="profit-table"]').text()).not.toContain('CYCLE_DETECTED')

    await selectRecipe(wrapper, cycleDetectedRow.outputName as string)
    expect(wrapper.find('[data-test="detail-state-code"]').text()).toBe('CYCLE_DETECTED')
  })

  it('rendersUnavailableAndBlockedRowsWithoutSubstitutingZero', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreenListingEveryRow(api)

    // No general State column: the two ordinary rows carry no diagnostic at all, and the two whose
    // own numbers could not say what happened keep a minimal one (DOMAIN_SPEC 2.1.1).
    expect(rowDiagnostics(wrapper)).toEqual(['Price missing', 'No result'])
    expect(totalProfits(wrapper).slice(2)).toEqual(['—', '—'])
    expect(totalSellValues(wrapper).slice(2)).toEqual(['—', '—'])

    const unavailableRow = wrapper.findAll('[data-test="profit-row"]').at(3)
    expect(unavailableRow?.text()).toContain(noResultRow.outputName)
    expect(unavailableRow?.findAll('[data-test="craftable-count"]').at(0)?.text()).toBe('—')

    const blockedRow = wrapper.findAll('[data-test="profit-row"]').at(2)
    expect(blockedRow?.text()).toContain(priceUnavailableRow.outputName)
  })

  it('comparesOnlyTheScanningColumnsAndKeepsTheRestInTheDetail', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreenListingEveryRow(api)

    const headers = wrapper
      .findAll('[data-test="profit-table"] thead th')
      .map((th) => th.text().replace(/\s+/g, ' '))
    // DOMAIN_SPEC 2.1.1's required comparison content, with each money column stating whether it is
    // per craft or a total, and no general State column among them.
    expect(headers).toEqual([
      'Recipe',
      'Disciplines',
      'Craftable crafts',
      'Own materials cost, per craft',
      'Profit per craft',
      'Total sell value all crafts',
      'Total profit all crafts▼'
    ])
    expect(headers.some((header) => header.startsWith('State'))).toBe(false)

    // The supplementary values are out of the table, but still reachable once a row is chosen.
    const tableText = wrapper.find('[data-test="profit-table"]').text()
    expect(tableText).not.toContain('Charged Core')
    expect(tableText).not.toContain('Output TP')

    await selectRecipe(wrapper, priceUnavailableRow.outputName as string)
    expect(wrapper.find('[data-test="missing-all"]').text()).toContain('Charged Core')
    expect(wrapper.find('[data-test="detail-output-quote"]').exists()).toBe(true)
  })

  it('opensTheDetailForTheChosenRecipeWithAKeyboardOperableControl', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    expect(wrapper.find('[data-test="detail-placeholder"]').text()).toContain('Choose a recipe')

    const control = wrapper.findAll('[data-test="select-row"]')[0]
    // An ordinary button, so Tab reaches it and Enter/Space activate it without a custom widget.
    expect(control?.element.tagName).toBe('BUTTON')
    expect(control?.attributes('tabindex')).toBeUndefined()

    await selectRecipe(wrapper, profitableRow.outputName as string)

    expect(wrapper.find('[data-test="detail-placeholder"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="detail-name"]').text()).toBe(profitableRow.outputName)
    expect(detailTotalProfit(wrapper)).toBe('+9s 0c')
  })

  it('marksTheSelectedRowVisiblyAndForAssistiveTechnology', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await selectRecipe(wrapper, lessProfitableRow.outputName as string)

    const selected = wrapper.findAll('[data-test="profit-row"]').filter((row) => row.classes('selected'))
    expect(selected).toHaveLength(1)
    expect(selected[0]?.attributes('aria-current')).toBe('true')
    expect(selected[0]?.find('[data-test="select-row"]').attributes('aria-current')).toBe('true')
    expect(selected[0]?.text()).toContain('Selected')
  })

  it('keepsTheSelectedRecipeWhenSortingReordersTheRows', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await selectRecipe(wrapper, lessProfitableRow.outputName as string)
    const before = recipeNames(wrapper)

    await wrapper.find('[data-test="sort-outputName"]').trigger('click')

    expect(recipeNames(wrapper)).not.toEqual(before)
    expect(wrapper.find('[data-test="detail-name"]').text()).toBe(lessProfitableRow.outputName)
    const selected = wrapper.findAll('[data-test="profit-row"]').filter((row) => row.classes('selected'))
    expect(selected[0]?.text()).toContain(lessProfitableRow.outputName as string)
  })

  it('showsTheReplacementCalculationsValuesUnderTheSameSelectedRecipe', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)
    await selectRecipe(wrapper, profitableRow.outputName as string)
    expect(detailTotalProfit(wrapper)).toBe('+9s 0c')

    api.profitHandler = () =>
      Promise.resolve(profitResponse([{ ...profitableRow, totalProfitCopper: 111 }, lessProfitableRow]))
    await wrapper.find('[data-test="setting-allowBuying"]').setValue(true)
    await flushPromises()

    expect(wrapper.find('[data-test="detail-name"]').text()).toBe(profitableRow.outputName)
    expect(detailTotalProfit(wrapper)).toBe('+1s 11c')
  })

  it('clearsTheDetailWhenTheReplacementCalculationDropsTheSelectedRecipe', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)
    await selectRecipe(wrapper, lessProfitableRow.outputName as string)

    api.profitHandler = () => Promise.resolve(profitResponse([profitableRow]))
    await wrapper.find('[data-test="reload"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="detail-name"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="detail-placeholder"]').text()).toContain('Choose a recipe')
    expect(wrapper.findAll('[data-test="profit-row"]').filter((row) => row.classes('selected'))).toHaveLength(0)
  })

  it('doesNotLetASupersededResponseReplaceTheSelectedDetail', async () => {
    const api = new FakeCraftingApi()
    const slowFirstScope = deferred<ReturnType<typeof profitResponse>>()
    const wrapper = await openScreen(api)

    api.profitHandler = (_request, callIndex) =>
      callIndex === 1
        ? slowFirstScope.promise
        : Promise.resolve(profitResponse([{ ...profitableRow, totalProfitCopper: 111 }], 'DISCIPLINE'))

    await wrapper.find('[data-test="scope-selector"]').setValue('DISCIPLINE|Chef')
    await flushPromises()
    await wrapper.find('[data-test="scope-selector"]').setValue('DISCIPLINE|Huntsman')
    await flushPromises()
    await selectRecipe(wrapper, profitableRow.outputName as string)
    expect(detailTotalProfit(wrapper)).toBe('+1s 11c')

    slowFirstScope.resolve(profitResponse([{ ...profitableRow, totalProfitCopper: 999 }], 'DISCIPLINE'))
    await flushPromises()

    expect(detailTotalProfit(wrapper)).toBe('+1s 11c')
  })

  it('keepsSearchSortAndSelectionThroughAnExplicitReload', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="search"]').setValue('o')
    await wrapper.find('[data-test="sort-outputName"]').trigger('click')
    await selectRecipe(wrapper, lessProfitableRow.outputName as string)

    await wrapper.find('[data-test="reload"]').trigger('click')
    await flushPromises()

    expect((wrapper.find('[data-test="search"]').element as HTMLInputElement).value).toBe('o')
    expect(wrapper.find('[data-test="sort-outputName"]').text()).toContain('▲')
    expect(wrapper.find('[data-test="detail-name"]').text()).toBe(lessProfitableRow.outputName)
  })

  it('keepsTheSelectedDetailReachableWhileTheSearchHidesItsRow', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)
    await selectRecipe(wrapper, lessProfitableRow.outputName as string)

    await wrapper.find('[data-test="search"]').setValue('Iron')

    expect(recipeNames(wrapper)).toEqual([profitableRow.outputName])
    expect(wrapper.find('[data-test="detail-name"]').text()).toBe(lessProfitableRow.outputName)
    expect(wrapper.find('[data-test="detail-hidden"]').text()).toContain('search or display filters')
  })

  it('keepsTheEffectiveSettingsReadableWhileTheirGroupIsClosed', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () =>
      Promise.resolve(profitResponse(allRows, 'ALL', { ...DEFAULT_SETTINGS, allowBuying: true, maxBuyCopper: 250 }))

    const wrapper = await openScreen(api)

    // Grouped behind one labelled control, with what is actually in effect still on screen.
    expect(wrapper.find('[data-test="settings-disclosure"]').attributes('open')).toBeUndefined()
    const summary = wrapper.find('[data-test="effective-settings"]').text()
    expect(summary).toContain('buying allowed')
    expect(summary).toContain('max buy 2s 50c')
    expect(summary).toContain('own materials used')
  })

  it('tellsTheDetailRegionsOwnStatesApart', async () => {
    const failing = new FakeCraftingApi()
    failing.profitHandler = () =>
      Promise.reject(new ApiRequestError('The data store is unavailable.', 'DATA_STORE_UNAVAILABLE', 503))
    expect((await openScreen(failing)).find('[data-test="detail-placeholder"]').text()).toContain(
      'No result was loaded'
    )

    const empty = new FakeCraftingApi()
    empty.profitHandler = () => Promise.resolve(profitResponse([]))
    expect((await openScreen(empty)).find('[data-test="detail-placeholder"]').text()).toContain(
      'returned no recipes'
    )

    const slow = new FakeCraftingApi()
    const pending = deferred<ReturnType<typeof profitResponse>>()
    slow.profitHandler = () => pending.promise
    const loading = mount(CraftingProfitScreen, { props: { api: slow } })
    await flushPromises()
    expect(loading.find('[data-test="detail-placeholder"]').text()).toContain(
      'once the calculation has answered'
    )
    pending.resolve(profitResponse())
    await flushPromises()
  })

  it('filtersTheDisplayedRowsBySearchWithoutRequestingAgain', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="search"]').setValue('Iron')

    expect(wrapper.findAll('[data-test="profit-row"]')).toHaveLength(1)
    expect(wrapper.find('[data-test="profit-row"]').text()).toContain('Iron Ingot')
    expect(api.profitRequests).toHaveLength(1)

    await wrapper.find('[data-test="search"]').setValue('no such recipe')

    expect(wrapper.find('[data-test="no-matches"]').exists()).toBe(true)
  })

  it('opensTheThreeDisplayFiltersEnabledWithA250Maximum', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () =>
      Promise.resolve(profitResponse([...allRows, notAllowedRow, zeroProfitRow]))

    const wrapper = await openScreen(api)

    expect(isChecked(wrapper, 'filter-zero-craftable')).toBe(true)
    expect(isChecked(wrapper, 'filter-not-allowed')).toBe(true)
    expect(isChecked(wrapper, 'filter-non-positive-profit')).toBe(true)
    expect(maximumInput(wrapper).value).toBe('250')
    expect(isChecked(wrapper, 'show-all')).toBe(false)

    // One row per filter is gone; the rows whose count and profit were not supplied are not.
    expect(recipeNames(wrapper)).toEqual([
      profitableRow.outputName,
      lessProfitableRow.outputName,
      noResultRow.outputName
    ])
    expect(api.profitRequests).toEqual([{}])
  })

  it('groupsTheDisplayControlsInsideTheCalculationControlsPanel', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreen(api)

    // DOMAIN_SPEC 2.1.1: a Displayed results subgroup of the controls panel, beside the Calculation
    // subgroup — not a section of its own in the results region.
    const controls = wrapper.find('[data-test="display-controls"]')
    const panel = wrapper.find('[data-test="calculation-controls"]').element.parentElement
    expect(panel?.contains(controls.element)).toBe(true)
    expect(wrapper.find('[data-test="results-region"]').element.contains(controls.element)).toBe(false)
    expect(controls.find('legend').text()).toBe('Displayed results')
    expect(wrapper.find('[data-test="calculation-controls"]').find('legend').text()).toBe('Calculation')
    // The search narrows the listed rows, so it belongs to that subgroup too.
    expect(controls.element.contains(wrapper.find('[data-test="search"]').element)).toBe(true)

    // The prose the correction removes: the group's own note, the row-selection/keyboard
    // instructions and the paragraph explaining the display limit.
    expect(controls.text()).not.toContain('never recalculate anything')
    expect(wrapper.find('[data-test="table-note"]').exists()).toBe(false)
    await typeMaximum(wrapper, '1')
    expect(wrapper.find('[data-test="limit-note"]').exists()).toBe(false)
  })

  it('appliesEachDisplayFilterReversiblyWithoutAskingTheBackendAgain', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () =>
      Promise.resolve(profitResponse([profitableRow, zeroProfitRow, notAllowedRow, priceUnavailableRow]))
    const wrapper = await openScreen(api)

    // All three on: only the row none of them describes is listed.
    expect(recipeNames(wrapper)).toEqual([profitableRow.outputName])

    await wrapper.find('[data-test="filter-zero-craftable"]').setValue(false)
    // The blocked row's profit is null, not zero or less, so only the count filter was holding it.
    expect(recipeNames(wrapper)).toEqual([profitableRow.outputName, priceUnavailableRow.outputName])

    await wrapper.find('[data-test="filter-not-allowed"]').setValue(false)
    expect(recipeNames(wrapper)).toContain(notAllowedRow.outputName)
    expect(recipeNames(wrapper)).not.toContain(zeroProfitRow.outputName)

    await wrapper.find('[data-test="filter-non-positive-profit"]').setValue(false)
    expect(recipeNames(wrapper)).toHaveLength(4)

    // And each switches back on again, on its own.
    await wrapper.find('[data-test="filter-not-allowed"]').setValue(true)
    expect(recipeNames(wrapper)).not.toContain(notAllowedRow.outputName)
    expect(recipeNames(wrapper)).toContain(zeroProfitRow.outputName)

    expect(api.profitRequests).toEqual([{}])
  })

  it('reportsDisplayedAgainstMatchingAndCalculatedCounts', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () =>
      Promise.resolve(profitResponse([profitableRow, lessProfitableRow, notAllowedRow, priceUnavailableRow]))
    const wrapper = await openScreen(api)

    // Two of four match (the not-allowed and zero-count rows are filtered out); both are displayed.
    expect(wrapper.find('[data-test="summary"]').text()).toContain(
      'Showing 2 of 2 matching recipes · 4 calculated'
    )

    await typeMaximum(wrapper, '1')

    // The compact count is the only thing that says the maximum is holding a row back.
    expect(wrapper.find('[data-test="summary"]').text()).toContain(
      'Showing 1 of 2 matching recipes · 4 calculated'
    )
  })

  it('suspendsTheMaximumWhileShowAllIsOnWithoutForgettingIt', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await typeMaximum(wrapper, '1')
    expect(maximumInput(wrapper).disabled).toBe(false)

    await wrapper.find('[data-test="show-all"]').setValue(true)
    expect(maximumInput(wrapper).disabled).toBe(true)
    expect(maximumInput(wrapper).value).toBe('1')

    await wrapper.find('[data-test="show-all"]').setValue(false)
    expect(maximumInput(wrapper).disabled).toBe(false)
    expect(recipeNames(wrapper)).toEqual([profitableRow.outputName])
  })

  it('appliesTheMaximumAfterTheSearchFiltersAndSortSoShowAllRevealsTheMatchingSet', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () =>
      Promise.resolve(profitResponse([profitableRow, lessProfitableRow, zeroProfitRow, priceUnavailableRow]))
    const wrapper = await openScreen(api)

    // The zero-profit and zero-count rows are already out, by the filters the screen opens with.
    await wrapper.find('[data-test="sort-outputName"]').trigger('click')
    await typeMaximum(wrapper, '1')

    expect(recipeNames(wrapper)).toEqual([lessProfitableRow.outputName])

    await wrapper.find('[data-test="show-all"]').setValue(true)

    // The full *matching* set in the chosen order — not the rows the filters removed.
    expect(recipeNames(wrapper)).toEqual([lessProfitableRow.outputName, profitableRow.outputName])
    expect(maximumInput(wrapper).value).toBe('1')
    expect(api.profitRequests).toEqual([{}])
  })

  it('refusesAnUnusableMaximumAndKeepsTheOneInEffect', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await typeMaximum(wrapper, '0')

    expect(wrapper.find('[data-test="max-displayed-rejected"]').exists()).toBe(true)
    expect(maximumInput(wrapper).value).toBe('250')
    expect(recipeNames(wrapper)).toHaveLength(3)

    await typeMaximum(wrapper, '1')

    expect(wrapper.find('[data-test="max-displayed-rejected"]').exists()).toBe(false)
    expect(recipeNames(wrapper)).toEqual([profitableRow.outputName])
  })

  it('explainsAnEmptyListByNamingEveryRestrictionInForce', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () => Promise.resolve(profitResponse([priceUnavailableRow, notAllowedRow]))
    const wrapper = await openScreen(api)

    expect(wrapper.find('[data-test="no-matches"]').text()).toContain('The calculation returned 2 recipes')
    const restrictions = wrapper.find('[data-test="active-restrictions"]').text()
    expect(restrictions).toContain('craftable count of 0')
    expect(restrictions).toContain('not allowed')
    expect(restrictions).toContain('profit per craft')

    // Only what is actually applied is named: a filter switched off leaves the list.
    await wrapper.find('[data-test="filter-non-positive-profit"]').setValue(false)
    expect(wrapper.find('[data-test="active-restrictions"]').text()).not.toContain('profit per craft')

    // The controls stay reachable so the user can undo what emptied the list.
    expect(wrapper.find('[data-test="display-controls"]').exists()).toBe(true)
    expect(api.profitRequests).toEqual([{}])
  })

  it('selectsTheRecipeWhenAnyNonInteractivePartOfItsRowIsClicked', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    // A plain cell, not the recipe control: the whole row is the target.
    const rows = wrapper.findAll('[data-test="profit-row"]')
    await rows[1]?.find('[data-test="total-profit"]').trigger('click')

    expect(wrapper.find('[data-test="detail-name"]').text()).toBe(lessProfitableRow.outputName)
    expect(wrapper.findAll('[data-test="profit-row"]').filter((row) => row.classes('selected'))).toHaveLength(1)
  })

  it('doesNotSelectTwiceWhenTheRowsOwnControlIsUsed', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)
    const table = wrapper.findComponent(CraftingProfitTable)

    await wrapper.findAll('[data-test="select-row"]')[0]?.trigger('click')

    // One click on the nested button is one selection action, not the button's plus the row's.
    expect(table.emitted('select')).toEqual([[profitableRow.recipeId]])
    expect(wrapper.find('[data-test="detail-name"]').text()).toBe(profitableRow.outputName)
  })

  it('keepsASelectedRecipeTheDisplayControlsHideAndSaysItIsOutsideTheList', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)
    await selectRecipe(wrapper, lessProfitableRow.outputName as string)

    await typeMaximum(wrapper, '1')

    expect(recipeNames(wrapper)).toEqual([profitableRow.outputName])
    expect(wrapper.find('[data-test="detail-name"]').text()).toBe(lessProfitableRow.outputName)
    expect(wrapper.find('[data-test="detail-hidden"]').text()).toContain('Show all')

    // The same recipe, now removed by a filter rather than by the maximum.
    await wrapper.find('[data-test="show-all"]').setValue(true)
    await wrapper.find('[data-test="filter-non-positive-profit"]').setValue(true)
    await wrapper.find('[data-test="search"]').setValue('Soup')
    await wrapper.find('[data-test="filter-zero-craftable"]').setValue(true)
    expect(wrapper.find('[data-test="detail-name"]').text()).toBe(lessProfitableRow.outputName)

    // But a replacement result set without that recipe still clears it, as WEB-005 requires.
    api.profitHandler = () => Promise.resolve(profitResponse([profitableRow]))
    await wrapper.find('[data-test="reload"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="detail-name"]').exists()).toBe(false)
  })

  it('keepsTheDisplayControlsThroughAnExplicitReload', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="filter-zero-craftable"]').setValue(false)
    await wrapper.find('[data-test="filter-not-allowed"]').setValue(false)
    await typeMaximum(wrapper, '3')
    await wrapper.find('[data-test="show-all"]').setValue(true)

    await wrapper.find('[data-test="reload"]').trigger('click')
    await flushPromises()

    expect(isChecked(wrapper, 'filter-zero-craftable')).toBe(false)
    expect(isChecked(wrapper, 'filter-not-allowed')).toBe(false)
    expect(isChecked(wrapper, 'filter-non-positive-profit')).toBe(true)
    expect(isChecked(wrapper, 'show-all')).toBe(true)
    expect(maximumInput(wrapper).value).toBe('3')
    // The reload asked for the same scope and settings; no display control reached the request.
    expect(api.profitRequests.at(1)).toEqual({ scope: { kind: 'ALL' }, settings: DEFAULT_SETTINGS })
  })

  // ---------- the non-Trading-Post material rule (DOMAIN_SPEC 2.1.1, UD-009/UD-010) ----------

  it('groupsTheNonTradingPostMaterialRuleWithTheCalculationAndOpensItEnabled', async () => {
    const api = new FakeCraftingApi()

    const wrapper = await openScreen(api)
    await wrapper.find('[data-test="settings-disclosure"]').trigger('click')

    const control = wrapper.find('[data-test="setting-allowNonTradeableMaterials"]')
    expect(control.exists()).toBe(true)
    // A calculation control, not a fourth display filter.
    expect(
      wrapper.find('[data-test="calculation-controls"]').element.contains(control.element)
    ).toBe(true)
    expect(wrapper.find('[data-test="display-controls"]').element.contains(control.element)).toBe(false)

    // Enabled by default, as the backend's echo reported it.
    expect(isChecked(wrapper, 'setting-allowNonTradeableMaterials')).toBe(true)
    expect(wrapper.find('[data-test="effective-settings"]').text()).toContain(
      'non-Trading-Post materials allowed'
    )

    // The help states the decided rule for both values, in the user's own terms.
    const help = wrapper.find('[data-test="setting-allowNonTradeableMaterials-help"]').text()
    expect(help).toContain('own them or can craft them')
    expect(help).toContain('unavailable')
    expect(help).toContain('consume no such material')
  })

  it('recalculatesThroughTheApiWhenTheMaterialRuleIsSwitchedOffAndFiltersNothingLocally', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreenListingEveryRow(api)
    const rowsBefore = wrapper.findAll('[data-test="profit-row"]').length

    await wrapper.find('[data-test="setting-allowNonTradeableMaterials"]').setValue(false)
    await flushPromises()

    // One fresh request carrying the new rule — the browser did not drop or hide a row itself.
    expect(api.profitRequests.at(1)).toEqual({
      scope: { kind: 'ALL' },
      settings: { ...DEFAULT_SETTINGS, allowNonTradeableMaterials: false }
    })
    expect(wrapper.findAll('[data-test="profit-row"]').length).toBe(rowsBefore)
    expect(isChecked(wrapper, 'setting-allowNonTradeableMaterials')).toBe(false)
    expect(wrapper.find('[data-test="effective-settings"]').text()).toContain(
      'non-Trading-Post materials excluded'
    )

    // The three display filters are untouched by it, and it is untouched by them.
    await wrapper.find('[data-test="filter-zero-craftable"]').setValue(true)
    expect(api.profitRequests).toHaveLength(2)
    expect(isChecked(wrapper, 'setting-allowNonTradeableMaterials')).toBe(false)
  })

  it('keepsTheMaterialRuleThroughAnExplicitReload', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="setting-allowNonTradeableMaterials"]').setValue(false)
    await flushPromises()
    await wrapper.find('[data-test="reload"]').trigger('click')
    await flushPromises()

    expect(api.profitRequests.at(2)).toEqual({
      scope: { kind: 'ALL' },
      settings: { ...DEFAULT_SETTINGS, allowNonTradeableMaterials: false }
    })
    expect(isChecked(wrapper, 'setting-allowNonTradeableMaterials')).toBe(false)
  })

  it('keepsTheLatestMaterialRuleWhenToggledBothWaysDuringCalculationAndThenReloaded', async () => {
    const api = new FakeCraftingApi()
    const wrapper = await openScreen(api)
    await selectRecipe(wrapper, 'Iron Ingot')
    await flushPromises()
    const older = deferred<CraftingProfitResponse>()
    const latest = deferred<CraftingProfitResponse>()
    api.profitHandler = (_, index) => index === 1 ? older.promise : latest.promise

    const control = wrapper.find('[data-test="setting-allowNonTradeableMaterials"]')
    await control.setValue(false)
    expect(control.attributes('disabled')).toBeUndefined()
    await control.setValue(true)
    expect(api.profitRequests).toHaveLength(3)
    expect(api.profitRequests[1]?.settings?.allowNonTradeableMaterials).toBe(false)
    expect(api.profitRequests[2]?.settings?.allowNonTradeableMaterials).toBe(true)

    latest.resolve(echoedProfitResponse(api.profitRequests[2]!))
    await flushPromises()
    older.resolve(echoedProfitResponse(api.profitRequests[1]!))
    await flushPromises()
    expect(isChecked(wrapper, 'setting-allowNonTradeableMaterials')).toBe(true)
    expect(api.resolutionRequests.at(-1)?.calculation.settings?.allowNonTradeableMaterials).toBe(true)
    expect(wrapper.find('[data-test="detail-name"]').text()).toBe('Iron Ingot')

    api.profitHandler = (request) => Promise.resolve(echoedProfitResponse(request))
    await wrapper.find('[data-test="reload"]').trigger('click')
    await flushPromises()
    expect(api.profitRequests.at(-1)?.settings?.allowNonTradeableMaterials).toBe(true)
    expect(api.resolutionRequests.at(-1)?.calculation.settings?.allowNonTradeableMaterials).toBe(true)
    expect(isChecked(wrapper, 'setting-allowNonTradeableMaterials')).toBe(true)
  })

  it('explainsAMaterialRestrictedRecipeInTheSelectedResultWithoutATableTag', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = () =>
      Promise.resolve(
        profitResponse([profitableRow, nonTradeableMaterialRow], 'ALL', {
          ...DEFAULT_SETTINGS,
          allowNonTradeableMaterials: false
        })
      )
    api.resolutionHandler = (request) =>
      Promise.resolve(resolutionResponse(request, { row: nonTradeableMaterialRow, tree: nonTradeableMaterialTree }))

    const wrapper = await openScreenListingEveryRow(api)

    // No row label, and the raw code stays out of the comparison table.
    expect(rowDiagnostics(wrapper)).toEqual([])
    expect(wrapper.find('[data-test="profit-table"]').text()).not.toContain('NON_TRADEABLE_MATERIAL')

    await selectRecipe(wrapper, nonTradeableMaterialRow.outputName as string)
    await flushPromises()

    const explanation = wrapper.find('[data-test="detail-status-explanation"]').text()
    expect(explanation).toContain('cannot be traded on the Trading Post')
    expect(explanation).toContain('switched off')
    expect(wrapper.find('[data-test="detail-state-code"]').text()).toBe('NON_TRADEABLE_MATERIAL')

    // And beside the material it applies to, in the tree the backend supplied.
    // Matched on each node's own name, so a parent containing the child's text is not mistaken
    // for it.
    const nodes = wrapper.findAll('[data-test="tree-node"]')
    const named = (name: string) =>
      nodes.find((element) => element.find('[data-test="node-name"]').text().includes(name))

    const restricted = named('Account Bound Scrap')
    expect(restricted).toBeDefined()
    expect(restricted?.find('[data-test="node-blocked-reason"]').text()).toBe(
      'Non-Trading-Post material'
    )
    expect(restricted?.find('[data-test="node-blocked-explanation"]').text()).toContain(
      'cannot be traded on the Trading Post'
    )

    // The tradeable sibling in the same craft carries no restriction.
    const sibling = named('Iron Ore')
    expect(sibling).toBeDefined()
    expect(sibling?.find('[data-test="node-blocked-reason"]').exists()).toBe(false)
  })
})
