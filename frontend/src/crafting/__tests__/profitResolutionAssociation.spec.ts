import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent, h, KeepAlive, ref } from 'vue'
import { ApiRequestError } from '@/api/http'
import { RECIPE_NOT_IN_CALCULATION, type CraftingProfitResolutionResponse } from '@/api/types'
import type { SyncApi } from '@/api/syncApi'
import CraftingProfitScreen from '../CraftingProfitScreen.vue'
import {
  DEFAULT_SETTINGS,
  FakeCraftingApi,
  blockedTree,
  deferred,
  inventoryOnlyTree,
  lessProfitableRow,
  node,
  otherRecipeTree,
  profitResponse,
  profitableRow,
  resolutionResponse
} from './fixtures'

const completedSyncApi: SyncApi = {
  async startAccountSync() { return { taskId: 'account', operation: 'ACCOUNT_SYNC', statusUrl: '/api/sync/tasks/account' } },
  async startProfitDataRefresh() { return { taskId: 'profit-data', operation: 'ACCOUNT_SYNC', statusUrl: '/api/sync/tasks/profit-data' } },
  async startGlobalSync() { return { taskId: 'global', operation: 'GLOBAL_SYNC', statusUrl: '/api/sync/tasks/global' } },
  async startPriceRefresh(variant) { return { taskId: variant, operation: `PRICE_REFRESH_${variant}`, statusUrl: `/api/sync/tasks/${variant}` } },
  async readTaskStatus(statusUrl) {
    const taskId = statusUrl.split('/').at(-1) ?? 'task'
    return { taskId, operation: taskId, state: 'SUCCEEDED', submittedAt: '', startedAt: '', finishedAt: '', failure: null }
  }
}

/**
 * The association rules of `TARGET_ARCHITECTURE.md` 13.4, driven through the real screen.
 *
 * A detail belongs to a feature, a recipe, the effective calculation inputs and a local request
 * generation. These tests are about which answers may be displayed and which requests are issued at
 * all — not about what a node looks like, which `CraftingResolution.spec.ts` covers.
 */

async function openScreen(api: FakeCraftingApi): Promise<VueWrapper> {
  const wrapper = mount(CraftingProfitScreen, { props: { api, refreshApi: completedSyncApi } })
  await flushPromises()
  return wrapper
}

function recipeNames(wrapper: VueWrapper): string[] {
  return wrapper.findAll('[data-test="profit-row"] .recipe-name').map((element) => element.text())
}

async function selectRecipe(wrapper: VueWrapper, recipeName: string): Promise<void> {
  const index = recipeNames(wrapper).indexOf(recipeName)
  if (index < 0) throw new Error(`No row for "${recipeName}" — rows: ${recipeNames(wrapper).join(', ')}`)
  await wrapper.findAll('[data-test="select-row"]')[index]?.trigger('click')
  await flushPromises()
}

/** The name at the root of the tree currently on screen, or null when no tree is rendered. */
function rootItem(wrapper: VueWrapper): string | null {
  const root = wrapper.find('[data-path="0"] [data-test="node-name"]')
  return root.exists() ? root.text() : null
}

/** A tree whose root item name identifies which answer produced it. */
function treeNamed(itemName: string) {
  return node({ itemName, recipeId: 11, craftCount: 1, producedQuantity: 1 })
}

describe('Crafting Profit resolution detail association', () => {
  describe('what is requested, and when', () => {
    it('requestsNoDetailUntilARecipeIsSelected', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)

      // Four rows were calculated and three are listed; not one of them caused a detail request.
      expect(api.profitRequests).toHaveLength(1)
      expect(api.resolutionRequests).toHaveLength(0)
      expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)
    })

    it('sendsTheRecipeIdAndTheTablesOwnEffectiveInputsAndNothingElse', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)

      await selectRecipe(wrapper, 'Iron Ingot')

      expect(api.resolutionRequests).toHaveLength(1)
      expect(api.resolutionRequests[0]).toEqual({
        recipeId: 11,
        calculation: { scope: { kind: 'ALL', rating: 0 }, settings: DEFAULT_SETTINGS }
      })
    })

    it('carriesAChangedScopeAndChangedSettingsIntoTheNextDetailRequest', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, 'Iron Ingot')

      // The default handler echoes the request the way the contract does, so the detail request
      // carries what the *table response* reported rather than what the selector was set to.
      await wrapper.find('[data-test="scope-selector"]').setValue('DISCIPLINE|Chef')
      await flushPromises()

      expect(api.resolutionRequests[1]?.calculation.scope).toEqual({
        kind: 'DISCIPLINE',
        discipline: 'Chef',
        rating: 0
      })

      await wrapper.find('[data-test="setting-allowBuying"]').setValue(true)
      await flushPromises()

      expect(api.resolutionRequests.at(-1)?.calculation.settings).toEqual({
        ...DEFAULT_SETTINGS,
        allowBuying: true
      })
    })

    it('doesNotRequestAgainForSortingSearchingOrADisplayFilter', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, 'Iron Ingot')
      expect(api.resolutionRequests).toHaveLength(1)

      await wrapper.find('[data-test="sort-outputName"]').trigger('click')
      await wrapper.find('[data-test="search"]').setValue('Iron')
      await wrapper.find('[data-test="filter-non-positive-profit"]').setValue(true)
      await flushPromises()

      // None of these changes the calculation, so the detail on screen is still valid.
      expect(api.resolutionRequests).toHaveLength(1)
      expect(rootItem(wrapper)).toBe('Iron Ingot')
    })

    it('invalidatesAndAsksAgainWhenTheTableIsReloaded', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, 'Iron Ingot')

      const reloaded = deferred<CraftingProfitResolutionResponse>()
      const tableReload = deferred<ReturnType<typeof profitResponse>>()
      api.resolutionHandler = () => reloaded.promise
      api.profitHandler = () => tableReload.promise
      await wrapper.find('[data-test="refresh-data-and-results"]').trigger('click')
      await flushPromises()

      // The old tree is gone the moment the reload starts; it is not left under a pending answer.
      expect(rootItem(wrapper)).toBeNull()

      tableReload.resolve(profitResponse())
      await flushPromises()
      expect(api.resolutionRequests).toHaveLength(2)

      reloaded.resolve(
        resolutionResponse(api.resolutionRequests[1]!, { tree: treeNamed('Reloaded root') })
      )
      await flushPromises()
      expect(rootItem(wrapper)).toBe('Reloaded root')
    })
  })

  describe('answers that must not be displayed', () => {
    it('ignoresASupersededAnswerWhenTheSelectionMovedOn', async () => {
      const api = new FakeCraftingApi()
      const first = deferred<CraftingProfitResolutionResponse>()
      api.resolutionHandler = (request, callIndex) =>
        callIndex === 0 ? first.promise : Promise.resolve(resolutionResponse(request, { tree: treeNamed('Soup root') }))

      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, 'Iron Ingot')
      await selectRecipe(wrapper, 'Bowl of Soup')

      first.resolve(resolutionResponse(api.resolutionRequests[0]!, { tree: treeNamed('Ingot root') }))
      await flushPromises()

      expect(rootItem(wrapper)).toBe('Soup root')
    })

    it('ignoresTheFirstAnswerOfAnAToBToASelection', async () => {
      const api = new FakeCraftingApi()
      const firstA = deferred<CraftingProfitResolutionResponse>()
      api.resolutionHandler = (request, callIndex) => {
        if (callIndex === 0) return firstA.promise
        const tree = callIndex === 1 ? otherRecipeTree : inventoryOnlyTree
        return Promise.resolve(resolutionResponse(request, { tree }))
      }

      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, 'Iron Ingot')
      await selectRecipe(wrapper, 'Bowl of Soup')
      await selectRecipe(wrapper, 'Iron Ingot')

      // The same recipe is selected again, so only the generation can tell the two answers apart.
      firstA.resolve(resolutionResponse(api.resolutionRequests[0]!, { tree: blockedTree }))
      await flushPromises()

      expect(api.resolutionRequests).toHaveLength(3)
      expect(wrapper.find('[data-test="resolution-root-sourcing"]').text()).toContain(
        'No recipe was selected'
      )
      expect(wrapper.find('[data-test="node-blocked-reason"]').exists()).toBe(false)
    })

    it('refusesAnAnswerThatEchoesADifferentRecipeOrDifferentInputs', async () => {
      const api = new FakeCraftingApi()
      api.resolutionHandler = (request) =>
        Promise.resolve(resolutionResponse({ ...request, recipeId: 999 }))
      const wrapper = await openScreen(api)

      await selectRecipe(wrapper, 'Iron Ingot')

      expect(wrapper.find('[data-test="resolution-failed"]').text()).toContain(
        'a different recipe or different calculation inputs'
      )
      expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)

      api.resolutionHandler = (request) =>
        Promise.resolve(
          resolutionResponse(request, {
            calculation: {
              scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 },
              settings: { ...DEFAULT_SETTINGS, allowBuying: !DEFAULT_SETTINGS.allowBuying }
            }
          })
        )
      await selectRecipe(wrapper, 'Bowl of Soup')

      expect(wrapper.find('[data-test="resolution-failed"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)
    })

    it('clearsTheDetailWhenANewResultSetNoLongerContainsTheSelectedRecipe', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, 'Iron Ingot')
      expect(rootItem(wrapper)).toBe('Iron Ingot')

      api.profitHandler = () => Promise.resolve(profitResponse([lessProfitableRow]))
      await wrapper.find('[data-test="refresh-data-and-results"]').trigger('click')
      await flushPromises()

      expect(wrapper.find('[data-test="selected-detail"]').text()).toContain('Choose a recipe')
      expect(rootItem(wrapper)).toBeNull()
    })
  })

  describe('failures and unavailability', () => {
    it('presentsAnAbsentFreshCandidateAsItsOwnSituation', async () => {
      const api = new FakeCraftingApi()
      api.resolutionHandler = () =>
        Promise.reject(
          new ApiRequestError('Recipe 11 is not in this calculation.', RECIPE_NOT_IN_CALCULATION, 404)
        )
      const wrapper = await openScreen(api)

      await selectRecipe(wrapper, 'Iron Ingot')

      expect(wrapper.find('[data-test="resolution-absent"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="resolution-failed"]').exists()).toBe(false)
      // The table's own values for the recipe are untouched by a failed explanation.
      expect(wrapper.find('[data-test="detail-total-profit"]').text()).toBe('+9s 0c')
    })

    it('keepsATechnicalFailureDistinctAndClearsTheTree', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, 'Iron Ingot')
      expect(rootItem(wrapper)).toBe('Iron Ingot')

      api.resolutionHandler = () =>
        Promise.reject(new ApiRequestError('The data store is unavailable.', 'DATA_STORE_UNAVAILABLE', 503))
      await selectRecipe(wrapper, 'Bowl of Soup')

      expect(wrapper.find('[data-test="resolution-failed"]').text()).toContain(
        'DATA_STORE_UNAVAILABLE'
      )
      expect(rootItem(wrapper)).toBeNull()
    })

    it('presentsAnUnavailableCalculationResultWithoutATree', async () => {
      const api = new FakeCraftingApi()
      api.resolutionHandler = (request) =>
        Promise.resolve(resolutionResponse(request, { treeStatus: 'RESULT_UNAVAILABLE', tree: null }))
      const wrapper = await openScreen(api)

      await selectRecipe(wrapper, 'Iron Ingot')

      expect(wrapper.find('[data-test="resolution-unavailable"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)
    })
  })

  describe('leaving the view while the page is kept alive', () => {
    /** The shell's own arrangement: the screen survives navigation instead of being re-created. */
    const KeptAliveShell = defineComponent({
      props: { api: { type: FakeCraftingApi, required: true } },
      setup(props) {
        const open = ref(true)
        return () => [
          h('button', { 'data-test': 'toggle', onClick: () => (open.value = !open.value) }, 'toggle'),
          h(KeepAlive, null, {
            default: () => (open.value ? h(CraftingProfitScreen, { api: props.api, refreshApi: completedSyncApi }) : null)
          })
        ]
      }
    })

    it('invalidatesOnLeavingAndAsksFreshlyOnReturn', async () => {
      const api = new FakeCraftingApi()
      const wrapper = mount(KeptAliveShell, { props: { api } })
      await flushPromises()
      await selectRecipe(wrapper, 'Iron Ingot')
      expect(rootItem(wrapper)).toBe('Iron Ingot')

      await wrapper.find('[data-test="toggle"]').trigger('click')
      await flushPromises()

      api.resolutionHandler = (request) =>
        Promise.resolve(resolutionResponse(request, { tree: treeNamed('Asked again') }))
      await wrapper.find('[data-test="toggle"]').trigger('click')
      await flushPromises()

      // The selection survived the trip, the calculation was not re-posted, and the detail is new.
      expect(api.profitRequests).toHaveLength(1)
      expect(api.resolutionRequests).toHaveLength(2)
      expect(rootItem(wrapper)).toBe('Asked again')
    })

    it('doesNotLetAnAnswerThatArrivesWhileAwayReviveTheDetail', async () => {
      const api = new FakeCraftingApi()
      const pending = deferred<CraftingProfitResolutionResponse>()
      api.resolutionHandler = (request, callIndex) =>
        callIndex === 0 ? pending.promise : Promise.resolve(resolutionResponse(request))

      const wrapper = mount(KeptAliveShell, { props: { api } })
      await flushPromises()
      await selectRecipe(wrapper, 'Iron Ingot')

      await wrapper.find('[data-test="toggle"]').trigger('click')
      await flushPromises()
      pending.resolve(resolutionResponse(api.resolutionRequests[0]!, { tree: treeNamed('Late root') }))
      await flushPromises()

      await wrapper.find('[data-test="toggle"]').trigger('click')
      await flushPromises()

      expect(rootItem(wrapper)).not.toBe('Late root')
      expect(rootItem(wrapper)).toBe(profitableRow.outputName)
    })
  })
})
