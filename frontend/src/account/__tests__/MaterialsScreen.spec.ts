import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { ApiRequestError } from '@/api/http'
import type { MaterialStorage } from '@/api/types'
import MaterialsScreen from '../MaterialsScreen.vue'
import {
  FakeAccountApi,
  deferred,
  emptyMaterialStorage,
  materialStorage
} from './accountFixtures'

/**
 * Rendering and state checks for the Materials screen against controlled responses
 * (`CURRENT_ARCHITECTURE.md` 5.12). Nothing here reaches a backend or a browser.
 */
async function openScreen(api: FakeAccountApi): Promise<VueWrapper> {
  const wrapper = mount(MaterialsScreen, { props: { api } })
  await flushPromises()
  return wrapper
}

function categoryNames(wrapper: VueWrapper): string[] {
  return wrapper.findAll('[data-test="material-category-name"]').map((name) => name.text())
}

describe('MaterialsScreen', () => {
  it('readsTheMaterialsRouteOnceWhenOpenedAndNothingElse', async () => {
    const api = new FakeAccountApi()

    await openScreen(api)

    expect(api.materialCalls).toBe(1)
    expect(api.bankCalls).toBe(0)
  })

  it('repeatsOnlyThatOneReadOnAnExplicitReload', async () => {
    const api = new FakeAccountApi()
    const wrapper = await openScreen(api)

    await wrapper.find('[data-test="materials-reload"]').trigger('click')
    await flushPromises()

    expect(api.materialCalls).toBe(2)
    expect(api.bankCalls).toBe(0)
  })

  it('rendersTheSuppliedCategoriesInTheSuppliedOrderIncludingAFallbackLabel', async () => {
    const api = new FakeAccountApi()

    const wrapper = await openScreen(api)

    // The backend's order, not an alphabetical one, and its own fallback label unchanged.
    expect(categoryNames(wrapper)).toEqual(['Zephyrite Supplies', 'Category 77'])
  })

  it('keepsEveryOfficialPositionInItsSuppliedOrderIncludingUnownedMaterials', async () => {
    const wrapper = await openScreen(new FakeAccountApi())
    const firstCategory = wrapper.findAll('.material-category').at(0)
    expect(firstCategory?.findAll('.inventory-tile').map((tile) => tile.attributes('title')))
      .toEqual(['Item #12134', 'Item #19697', 'Item #19698'])
    expect(wrapper.findAll('[data-test="material-stack"]')).toHaveLength(4)
  })

  it('showsQuantitiesOnlyOnOwnedPositionsAndGreysUnownedIcons', async () => {
    const wrapper = await openScreen(new FakeAccountApi())
    expect(wrapper.findAll('[data-test="item-count"]').map((count) => count.text())).toEqual(['3', '250', '11'])
    const unowned = wrapper.findAll('[data-test="material-stack"]').at(1)
    expect(unowned?.find('[data-test="item-count"]').exists()).toBe(false)
    expect(unowned?.find('.inventory-tile--empty').exists()).toBe(true)
  })

  it('showsALoadingStateWhileTheReadIsInFlight', async () => {
    const api = new FakeAccountApi()
    const pending = deferred<MaterialStorage>()
    api.materialsHandler = () => pending.promise

    const wrapper = mount(MaterialsScreen, { props: { api } })
    await flushPromises()

    expect(wrapper.find('[data-test="materials-loading"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="material-category"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="materials-empty"]').exists()).toBe(false)

    pending.resolve(materialStorage)
    await flushPromises()

    expect(wrapper.find('[data-test="materials-loading"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-test="material-category"]')).toHaveLength(2)
  })

  it('showsASuccessfulEmptyReadAsEmptyAndNotAsAFailure', async () => {
    const api = new FakeAccountApi()
    api.materialsHandler = () => Promise.resolve(emptyMaterialStorage)

    const wrapper = await openScreen(api)

    expect(wrapper.find('[data-test="materials-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="materials-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="material-category"]').exists()).toBe(false)
  })

  it('showsTheBackendsOwnSanitizedFailureAndRetriesOnlyOnRequest', async () => {
    const api = new FakeAccountApi()
    api.materialsHandler = (callIndex) =>
      callIndex === 0
        ? Promise.reject(
            new ApiRequestError('Reading the account failed.', 'ACCOUNT_READ_FAILED', 500)
          )
        : Promise.resolve(materialStorage)

    const wrapper = await openScreen(api)

    const failure = wrapper.find('[data-test="materials-error"]')
    expect(failure.text()).toContain('ACCOUNT_READ_FAILED')
    expect(failure.text()).toContain('Reading the account failed.')
    expect(wrapper.find('[data-test="materials-empty"]').exists()).toBe(false)
    expect(api.materialCalls).toBe(1)

    await wrapper.find('[data-test="materials-retry"]').trigger('click')
    await flushPromises()

    expect(api.materialCalls).toBe(2)
    expect(wrapper.findAll('[data-test="material-category"]')).toHaveLength(2)
  })

  it('doesNotPresentTheEarlierCategoriesWhenAReloadFails', async () => {
    const api = new FakeAccountApi()
    const wrapper = await openScreen(api)
    expect(wrapper.findAll('[data-test="material-category"]')).toHaveLength(2)

    api.materialsHandler = () =>
      Promise.reject(new ApiRequestError('The data store is unavailable.', 'DATA_STORE_UNAVAILABLE', 503))
    await wrapper.find('[data-test="materials-reload"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="materials-error"]').text()).toContain('DATA_STORE_UNAVAILABLE')
    expect(wrapper.findAll('[data-test="material-category"]')).toHaveLength(0)
    expect(wrapper.find('[data-test="materials-empty"]').exists()).toBe(false)
  })

  it('rendersEachStacksOwnSuppliedImageIncludingTheSameItemTwice', async () => {
    const api = new FakeAccountApi()

    const wrapper = await openScreen(api)

    // Item 12134 is stored in both categories, with a URL in one and none in the other. Each
    // occurrence shows what was supplied for *it*; nothing is carried over from the other stack.
    const icons = wrapper.findAll('[data-test="item-icon"]')
    expect(icons).toHaveLength(4)
    expect(icons.map((icon) => icon.attributes('data-icon-state'))).toEqual([
      'image',
      'no-url',
      'no-url',
      'no-url'
    ])
    expect(wrapper.findAll('img').map((image) => image.attributes('src'))).toEqual([
      materialStorage.categories[0]?.materials[0]?.iconUrl
    ])
    expect(wrapper.html()).not.toContain('render.guildwars2.com')
  })
})
