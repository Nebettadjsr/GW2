import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { ApiRequestError } from '@/api/http'
import type { EctoSalvage } from '@/api/types'
import EctoSalvageScreen from '../EctoSalvageScreen.vue'
import {
  FakeEctoApi,
  deferred,
  ectoSalvage,
  reloadedEctoSalvage,
  unavailableEctoSalvage
} from './ectoFixtures'

/**
 * Rendering, interaction and state checks for the Ectoplasm Salvage screen against controlled
 * responses (`CURRENT_ARCHITECTURE.md` 5.15, `TEST_STRATEGY.md` 12). Nothing here reaches a backend,
 * a browser or the GW2 API.
 *
 * The fixture's numbers do not add up to each other on purpose, so every assertion below is a check
 * that a *supplied* value reached the screen — a page that recalculated profit, net cost, the
 * recovered Dust value, the fee or the Luck cost would disagree with these expectations rather than
 * with itself.
 */
async function openScreen(api: FakeEctoApi): Promise<VueWrapper> {
  const wrapper = mount(EctoSalvageScreen, { props: { api } })
  await flushPromises()
  return wrapper
}

function cellsOf(wrapper: VueWrapper, hook: string): string[] {
  return wrapper.findAll(`[data-test="${hook}"]`).map((cell) => cell.text())
}

function valueOf(wrapper: VueWrapper, hook: string): string {
  return wrapper.find(`[data-test="${hook}"]`).text()
}

describe('EctoSalvageScreen', () => {
  it('calculatesOnceWhenOpenedAndAsksForNothingElse', async () => {
    const api = new FakeEctoApi()

    await openScreen(api)

    expect(api.salvageCalls).toBe(1)
  })

  it('repeatsOnlyThatOneCalculationOnAnExplicitReload', async () => {
    const api = new FakeEctoApi()
    api.salvageHandler = (callIndex) =>
      Promise.resolve(callIndex === 0 ? ectoSalvage : reloadedEctoSalvage)
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="ecto-reload"]').trigger('click')
    await flushPromises()

    expect(api.salvageCalls).toBe(2)
    // The newly supplied figures replace the earlier ones rather than being merged with them.
    expect(valueOf(wrapper, 'ecto-scenario-profit')).toContain('+96s 66c')
  })

  it('rendersAllFourScenariosInTheBackendsOrderWithUnambiguousModeLabels', async () => {
    const api = new FakeEctoApi()

    const wrapper = await openScreen(api)

    for (const key of [
      'instant-buy-instant-sell',
      'instant-buy-listing-sell',
      'buy-order-instant-sell',
      'buy-order-listing-sell'
    ]) {
      expect(wrapper.find(`[data-test="ecto-scenario-${key}"]`).exists()).toBe(true)
    }
    expect(cellsOf(wrapper, 'ecto-scenario-acquisition')).toEqual([
      'Instant buy',
      'Instant buy',
      'Buy order',
      'Buy order'
    ])
    expect(cellsOf(wrapper, 'ecto-scenario-sale')).toEqual([
      'Instant sell',
      'Listing sell',
      'Instant sell',
      'Listing sell'
    ])
  })

  it('showsEveryScenarioValueAsSuppliedWithoutDerivingOneFromAnother', async () => {
    const api = new FakeEctoApi()

    const wrapper = await openScreen(api)

    // Gross quotes, exactly as supplied.
    expect(cellsOf(wrapper, 'ecto-scenario-ecto-cost')).toEqual([
      '11s 11c',
      '12s 12c',
      '13s 13c',
      '14s 14c'
    ])
    expect(cellsOf(wrapper, 'ecto-scenario-dust-gross')).toEqual([
      '22s 22c',
      '23s 23c',
      '24s 24c',
      '25s 25c'
    ])

    // The expected value of the recovered Dust, gross: supplied, not the Dust quote scaled here.
    expect(cellsOf(wrapper, 'ecto-scenario-dust-recovered-gross')).toEqual([
      '33s 33c',
      '34s 34c',
      // A supplied zero stays a zero beside a non-zero after-fee value on the same row.
      '0c',
      '36s 36c'
    ])

    // Fee-inclusive economic results. None of these is the arithmetic consequence of the columns
    // above — not even the gross recovered value less the stated percentage — and none is
    // recomputed here.
    expect(cellsOf(wrapper, 'ecto-scenario-dust-recovered-net')).toEqual([
      '44s 44c',
      '45s 45c',
      '46s 46c',
      '47s 47c'
    ])
    expect(cellsOf(wrapper, 'ecto-scenario-net-cost')).toEqual([
      '55s 55c',
      '-56s 56c',
      '57s 57c',
      '58s 58c'
    ])
    expect(cellsOf(wrapper, 'ecto-scenario-luck-cost')).toEqual([
      '77s 77c',
      '-78s 78c',
      '79s 79c',
      '80s 80c'
    ])
  })

  it('writesTheSignAndTheOutcomeOfEveryProfitSoNeitherDependsOnColor', async () => {
    const api = new FakeEctoApi()

    const wrapper = await openScreen(api)

    const profits = wrapper.findAll('[data-test="ecto-scenario-profit"] .money')
    expect(profits.map((profit) => profit.text())).toEqual([
      '-66s 66c',
      '+67s 67c',
      // A supplied zero stays a zero rather than becoming the missing-value marker.
      '0c',
      '+69s 69c'
    ])
    expect(profits.map((profit) => profit.classes().join(' '))).toEqual([
      'money money--loss',
      'money money--gain',
      'money money--none',
      'money money--gain'
    ])
    expect(cellsOf(wrapper, 'ecto-scenario-outcome')).toEqual([
      'loss',
      'gain',
      'break-even',
      'gain'
    ])
  })

  it('statesTheBackendsOwnAssumptionsAndFeePercentageRatherThanItsOwn', async () => {
    const api = new FakeEctoApi()

    const wrapper = await openScreen(api)

    // The fixture's fee is deliberately not the project's 15%: a screen holding its own copy of that
    // number would print 15 here.
    expect(valueOf(wrapper, 'ecto-assumption-fee')).toContain('12%')
    expect(valueOf(wrapper, 'ecto-assumption-yield')).toContain('20 Luck')
    expect(valueOf(wrapper, 'ecto-assumption-yield')).toContain('0.75 Crystalline Dust')
    // Expected values, never presented as a guaranteed salvage outcome.
    expect(valueOf(wrapper, 'ecto-assumption-yield')).toContain('not a guaranteed drop')
    expect(valueOf(wrapper, 'ecto-assumption-luck')).toContain('50 Ectoplasm')
  })

  it('showsOnlyGrossMarketQuotesInThePricePanelAndNamesTheFeeOnTheResultColumns', async () => {
    const api = new FakeEctoApi()

    const wrapper = await openScreen(api)

    expect(valueOf(wrapper, 'ecto-quote-instant-buy')).toBe('11s 11c')
    expect(valueOf(wrapper, 'ecto-quote-buy-order')).toBe('13s 13c')
    expect(valueOf(wrapper, 'dust-quote-instant-sell')).toBe('22s 22c')
    expect(valueOf(wrapper, 'dust-quote-listing-sell')).toBe('23s 23c')

    // The superseded per-Dust-unit "after fee" quotes: a market price is never shown net of the fee
    // (`DOMAIN_SPEC.md` 25, 46).
    expect(wrapper.find('[data-test="dust-net-instant-sell"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="dust-net-listing-sell"]').exists()).toBe(false)
    expect(valueOf(wrapper, 'ecto-prices')).not.toContain('after fee')

    // Every fee-inclusive column says so, with the percentage the backend supplied; the two gross
    // columns beside them do not.
    const columnNotes = wrapper
      .findAll('.column-note')
      .map((note) => note.text().replace(/\s+/g, ' '))
    expect(columnNotes).toEqual([
      'gross, per ecto',
      'gross, per dust',
      'gross, per ecto',
      'after 12% TP fees, per ecto',
      'after 12% TP fees, per ecto',
      'after 12% TP fees, per ecto',
      'after 12% TP fees'
    ])

    expect(valueOf(wrapper, 'ecto-item-id')).toBe('#19721')
    expect(valueOf(wrapper, 'dust-item-id')).toBe('#24277')
  })

  it('rendersTheItemsThroughTheSharedIconComponentWithNoSuppliedSource', async () => {
    const api = new FakeEctoApi()

    const wrapper = await openScreen(api)

    const icons = wrapper.findAll('[data-test="item-icon"]')
    expect(icons).toHaveLength(2)
    // This route carries no item metadata, so both fall back to the established placeholder; no URL
    // is built here and nothing is requested from ArenaNet.
    expect(icons.map((icon) => icon.attributes('data-icon-state'))).toEqual(['no-url', 'no-url'])
    expect(wrapper.findAll('img')).toHaveLength(0)
    expect(wrapper.html()).not.toContain('render.guildwars2.com')
  })

  it('showsALoadingStateWhileTheCalculationIsInFlight', async () => {
    const api = new FakeEctoApi()
    const pending = deferred<EctoSalvage>()
    api.salvageHandler = () => pending.promise

    const wrapper = mount(EctoSalvageScreen, { props: { api } })
    await flushPromises()

    expect(wrapper.find('[data-test="ecto-loading"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="ecto-scenario-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="ecto-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="ecto-unavailable"]').exists()).toBe(false)

    pending.resolve(ectoSalvage)
    await flushPromises()

    expect(wrapper.find('[data-test="ecto-loading"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-test="ecto-scenario-acquisition"]')).toHaveLength(4)
  })

  it('disablesTheReloadControlWhileACalculationIsInFlightAndRestoresItAfterwards', async () => {
    const api = new FakeEctoApi()
    const wrapper = await openScreen(api)
    expect(wrapper.find('[data-test="ecto-reload"]').attributes('disabled')).toBeUndefined()

    const pending = deferred<EctoSalvage>()
    api.salvageHandler = () => pending.promise

    await wrapper.find('[data-test="ecto-reload"]').trigger('click')
    await flushPromises()

    // Disabled, so a second click cannot even be delivered; the suppression behind it — which a
    // repeat that *does* reach the state would hit — is covered by `useEctoSalvage.spec.ts`.
    expect(wrapper.find('[data-test="ecto-reload"]').attributes('disabled')).toBeDefined()
    expect(api.salvageCalls).toBe(2)

    pending.resolve(reloadedEctoSalvage)
    await flushPromises()

    expect(wrapper.find('[data-test="ecto-reload"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-test="ecto-reload"]').trigger('click')
    await flushPromises()
    expect(api.salvageCalls).toBe(3)
  })

  it('showsTheBackendsOwnSanitizedFailureAndRetriesOnlyOnRequest', async () => {
    const api = new FakeEctoApi()
    api.salvageHandler = (callIndex) =>
      callIndex === 0
        ? Promise.reject(
            new ApiRequestError(
              'Live Trading Post prices for the Ectoplasm calculation are currently unavailable',
              'PRICE_SOURCE_UNAVAILABLE',
              502
            )
          )
        : Promise.resolve(ectoSalvage)

    const wrapper = await openScreen(api)

    const failure = wrapper.find('[data-test="ecto-error"]')
    expect(failure.text()).toContain('PRICE_SOURCE_UNAVAILABLE')
    expect(failure.text()).toContain('currently unavailable')
    expect(wrapper.find('[data-test="ecto-scenario-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="ecto-unavailable"]').exists()).toBe(false)
    expect(api.salvageCalls).toBe(1)

    await wrapper.find('[data-test="ecto-retry"]').trigger('click')
    await flushPromises()

    expect(api.salvageCalls).toBe(2)
    expect(wrapper.findAll('[data-test="ecto-scenario-acquisition"]')).toHaveLength(4)
  })

  it('doesNotPresentTheEarlierFiguresWhenAReloadFails', async () => {
    const api = new FakeEctoApi()
    const wrapper = await openScreen(api)
    expect(wrapper.findAll('[data-test="ecto-scenario-acquisition"]')).toHaveLength(4)

    api.salvageHandler = () =>
      Promise.reject(new ApiRequestError('The backend could not be reached.', 'BACKEND_UNREACHABLE', null))
    await wrapper.find('[data-test="ecto-reload"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="ecto-error"]').text()).toContain('BACKEND_UNREACHABLE')
    expect(wrapper.findAll('[data-test="ecto-scenario-acquisition"]')).toHaveLength(0)
    expect(wrapper.find('[data-test="ecto-prices"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('11s 11c')
  })

  it('showsACompletedCalculationWithNoUsableQuotesAsAnAnswerAndNotAsZeroOrAFailure', async () => {
    const api = new FakeEctoApi()
    api.salvageHandler = () => Promise.resolve(unavailableEctoSalvage)

    const wrapper = await openScreen(api)

    expect(wrapper.find('[data-test="ecto-unavailable"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="ecto-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="ecto-scenario-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="ecto-prices"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('0c')
  })

  it('doesNotLetAnAnswerThatArrivesAfterTheScreenIsClosedChangeAnything', async () => {
    const api = new FakeEctoApi()
    const pending = deferred<EctoSalvage>()
    api.salvageHandler = () => pending.promise

    const wrapper = mount(EctoSalvageScreen, { props: { api } })
    await flushPromises()
    expect(wrapper.find('[data-test="ecto-loading"]').exists()).toBe(true)

    wrapper.unmount()
    pending.resolve(ectoSalvage)
    await flushPromises()

    // The late answer was refused rather than applied to a screen that is no longer open, and it
    // provoked no further request.
    expect(api.salvageCalls).toBe(1)
    expect(wrapper.html()).not.toContain('11s 11c')
  })
})
