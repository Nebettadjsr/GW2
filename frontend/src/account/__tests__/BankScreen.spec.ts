import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { ApiRequestError, UNREACHABLE_CODE } from '@/api/http'
import type { BankContents } from '@/api/types'
import BankScreen from '../BankScreen.vue'
import {
  FakeAccountApi,
  bankWithEmptySlots,
  bankWithoutSlots,
  deferred
} from './accountFixtures'

/**
 * Rendering and state checks for the Bank screen against controlled responses
 * (`CURRENT_ARCHITECTURE.md` 5.12). Nothing here reaches a backend or a browser.
 */
async function openScreen(api: FakeAccountApi): Promise<VueWrapper> {
  const wrapper = mount(BankScreen, { props: { api } })
  await flushPromises()
  return wrapper
}

describe('BankScreen', () => {
  it('readsTheBankRouteOnceWhenOpenedAndNothingElse', async () => {
    const api = new FakeAccountApi()

    await openScreen(api)

    expect(api.bankCalls).toBe(1)
    expect(api.materialCalls).toBe(0)
  })

  it('repeatsOnlyThatOneReadOnAnExplicitReload', async () => {
    const api = new FakeAccountApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="bank-reload"]').trigger('click')
    await flushPromises()

    expect(api.bankCalls).toBe(2)
    expect(api.materialCalls).toBe(0)
  })

  it('rendersEverySuppliedSlotInOrderWithEmptyOnesLeftInPlace', async () => {
    const wrapper = await openScreen(new FakeAccountApi())
    expect(wrapper.findAll('[data-test="bank-slot"]').map((slot) => slot.attributes('data-slot')))
      .toEqual(['0', '1', '2', '3', '4'])
    const emptyPositions = wrapper.findAll('[data-test="bank-slot"]')
      .map((slot, index) => (slot.find('[data-test="bank-empty-slot"]').exists() ? index : -1))
      .filter((index) => index >= 0)
    expect(emptyPositions).toEqual([1, 3])
  })

  it('neverTurnsAnEmptySlotIntoItemIdZeroOrAnOwnedCountOfZero', async () => {
    const api = new FakeAccountApi()

    const wrapper = await openScreen(api)

    const empty = wrapper.findAll('[data-test="bank-slot"]').at(1)
    expect(empty?.find('[data-test="item-identity"]').exists()).toBe(false)
    expect(empty?.find('[data-test="item-count"]').exists()).toBe(false)
    expect(empty?.text()).toContain('Empty')
    expect(empty?.text()).not.toContain('#0')
    expect(empty?.text()).not.toContain('0')
  })

  it('showsTheSuppliedQuantityAndItemIdentityInTheSlotTooltip', async () => {
    const wrapper = await openScreen(new FakeAccountApi())
    const slots = wrapper.findAll('[data-test="bank-slot"]')
    expect(slots[0]?.attributes('title')).toContain('Item #19697')
    expect(slots[0]?.find('[data-test="item-count"]').text()).toBe('42')
    expect(slots[4]?.attributes('title')).toContain('Item #12134')
    expect(slots[4]?.find('[data-test="item-count"]').text()).toBe('250')
    expect(slots[2]?.attributes('title')).toContain('Item #24295')
    expect(slots[2]?.find('[data-test="item-count"]').exists()).toBe(false)
  })

  it('distinguishesAZeroSlotResponseFromABankThatContainsEmptySlots', async () => {
    const withEmptySlots = await openScreen(new FakeAccountApi())
    expect(withEmptySlots.find('[data-test="bank-no-slots"]').exists()).toBe(false)
    expect(withEmptySlots.findAll('[data-test="bank-empty-slot"]')).toHaveLength(2)

    const api = new FakeAccountApi()
    api.bankHandler = () => Promise.resolve(bankWithoutSlots)
    const withoutSlots = await openScreen(api)

    expect(withoutSlots.find('[data-test="bank-no-slots"]').exists()).toBe(true)
    expect(withoutSlots.find('[data-test="bank-slots"]').exists()).toBe(false)
    expect(withoutSlots.find('[data-test="bank-error"]').exists()).toBe(false)
  })

  it('showsALoadingStateWhileTheReadIsInFlight', async () => {
    const api = new FakeAccountApi()
    const pending = deferred<BankContents>()
    api.bankHandler = () => pending.promise

    const wrapper = mount(BankScreen, { props: { api } })
    await flushPromises()

    expect(wrapper.find('[data-test="bank-loading"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="bank-slots"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="bank-no-slots"]').exists()).toBe(false)

    pending.resolve(bankWithEmptySlots)
    await flushPromises()

    expect(wrapper.find('[data-test="bank-loading"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="bank-slots"]').exists()).toBe(true)
  })

  it('showsTheBackendsOwnSanitizedFailureAndRetriesOnlyOnRequest', async () => {
    const api = new FakeAccountApi()
    api.bankHandler = (callIndex) =>
      callIndex === 0
        ? Promise.reject(
            new ApiRequestError('The data store is unavailable.', 'DATA_STORE_UNAVAILABLE', 503)
          )
        : Promise.resolve(bankWithEmptySlots)

    const wrapper = await openScreen(api)

    const failure = wrapper.find('[data-test="bank-error"]')
    expect(failure.text()).toContain('DATA_STORE_UNAVAILABLE')
    expect(failure.text()).toContain('The data store is unavailable.')
    expect(wrapper.find('[data-test="bank-slots"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="bank-no-slots"]').exists()).toBe(false)
    expect(api.bankCalls).toBe(1)

    await wrapper.find('[data-test="bank-retry"]').trigger('click')
    await flushPromises()

    expect(api.bankCalls).toBe(2)
    expect(wrapper.find('[data-test="bank-error"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-test="bank-slot"]')).toHaveLength(5)
  })

  it('reportsAnUnreachableBackendAsAFailureRatherThanAnEmptyBank', async () => {
    const api = new FakeAccountApi()
    api.bankHandler = () =>
      Promise.reject(new ApiRequestError('The backend could not be reached.', UNREACHABLE_CODE, null))

    const wrapper = await openScreen(api)

    expect(wrapper.find('[data-test="bank-error"]').text()).toContain(UNREACHABLE_CODE)
    expect(wrapper.find('[data-test="bank-no-slots"]').exists()).toBe(false)
  })

  it('doesNotPresentTheEarlierBankWhenAReloadFails', async () => {
    const api = new FakeAccountApi()
    const wrapper = await openScreen(api)
    expect(wrapper.findAll('[data-test="bank-slot"]')).toHaveLength(5)

    api.bankHandler = () =>
      Promise.reject(new ApiRequestError('Reading the account failed.', 'ACCOUNT_READ_FAILED', 500))
    await wrapper.find('[data-test="bank-reload"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="bank-error"]').text()).toContain('ACCOUNT_READ_FAILED')
    expect(wrapper.findAll('[data-test="bank-slot"]')).toHaveLength(0)
    expect(wrapper.find('[data-test="bank-no-slots"]').exists()).toBe(false)
  })

  it('keepsTheReloadUnrepeatableWhileItsOwnReadIsStillInFlight', async () => {
    const api = new FakeAccountApi()
    const pending = deferred<BankContents>()
    const wrapper = await openScreen(api)

    api.bankHandler = () => pending.promise
    await wrapper.find('[data-test="bank-reload"]').trigger('click')

    expect(wrapper.find('[data-test="bank-reload"]').attributes('disabled')).toBeDefined()

    pending.resolve(bankWithoutSlots)
    await flushPromises()

    expect(api.bankCalls).toBe(2)
    expect(wrapper.find('[data-test="bank-reload"]').attributes('disabled')).toBeUndefined()
  })

  it('rendersTheSuppliedApplicationImageForEachOccupiedSlotAndNothingForAnEmptyOne', async () => {
    const api = new FakeAccountApi()

    const wrapper = await openScreen(api)

    // One icon per *occupied* slot: an empty slot stays empty, without a placeholder standing in
    // for an item it does not hold.
    const icons = wrapper.findAll('[data-test="item-icon"]')
    expect(icons).toHaveLength(3)
    expect(icons.map((icon) => icon.attributes('data-icon-state'))).toEqual([
      'image',
      'no-url',
      'image'
    ])
    for (const emptySlot of wrapper.findAll('.slot--empty')) {
      expect(emptySlot.find('[data-test="item-icon"]').exists()).toBe(false)
    }

    // The supplied URL is used verbatim — no cache-busting query, no rebuilt address.
    expect(wrapper.findAll('img').map((image) => image.attributes('src'))).toEqual([
      bankWithEmptySlots.slots[0]?.iconUrl,
      bankWithEmptySlots.slots[4]?.iconUrl
    ])

    // The contract carries an application-relative URL and nothing else: no upstream origin and no
    // backend path can appear, so the browser can only ever ask this application for an image.
    expect(wrapper.html()).not.toContain('render.guildwars2.com')
    expect(wrapper.html()).not.toContain('C:\\gw2\\icons')
  })
})
