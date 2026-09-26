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

function identitiesOfCategory(wrapper: VueWrapper, index: number): string[] {
  const category = wrapper.findAll('[data-test="material-category"]').at(index)
  return category === undefined
    ? []
    : category.findAll('[data-test="item-identity"]').map((identity) => identity.text())
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

  it('keepsTheSuppliedStackOrderAndNeitherRegroupsNorDeduplicates', async () => {
    const api = new FakeAccountApi()

    const wrapper = await openScreen(api)

    expect(identitiesOfCategory(wrapper, 0)).toEqual(['#12134', '#19697'])
    // The same item id is supplied in both categories and stays in both.
    expect(identitiesOfCategory(wrapper, 1).at(0)).toBe('#12134')
    expect(wrapper.findAll('[data-test="material-stack"]')).toHaveLength(4)
  })

  it('showsEachSuppliedStacksCountAndTheCategoryIdItWasGroupedBy', async () => {
    const api = new FakeAccountApi()

    const wrapper = await openScreen(api)

    const counts = wrapper.findAll('[data-test="item-count"]').map((count) => count.text())
    expect(counts).toEqual(['× 3', '× 250', '× 11', '× 7'])

    const categoryIds = wrapper
      .findAll('[data-test="material-stack-category"]')
      .map((element) => element.text())
    expect(categoryIds).toEqual([
      'category id 30',
      'category id 30',
      'category id 77',
      'category id 77'
    ])
  })

  it('rendersAStackWithoutAnItemIdWithoutInventingOneAndKeepsItsCount', async () => {
    const api = new FakeAccountApi()

    const wrapper = await openScreen(api)

    const lastStack = wrapper.findAll('[data-test="material-stack"]').at(3)
    expect(lastStack?.find('[data-test="item-identity"]').text()).toContain('no item id supplied')
    expect(lastStack?.text()).not.toContain('#0')
    expect(lastStack?.find('[data-test="item-count"]').text()).toBe('× 7')
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

  it('reportsWhetherAnIconUrlWasSuppliedAndStillRequestsNoImageBeforeWeb010', async () => {
    const api = new FakeAccountApi()

    const wrapper = await openScreen(api)

    expect(wrapper.findAll('img')).toHaveLength(0)
    const fallbacks = wrapper.findAll('[data-test="item-icon-fallback"]')
    expect(fallbacks).toHaveLength(4)
    expect(fallbacks.map((icon) => icon.attributes('data-icon-supplied'))).toEqual([
      'true',
      'false',
      'false',
      'false'
    ])
    expect(wrapper.html()).not.toContain('render.guildwars2.com')
  })
})
