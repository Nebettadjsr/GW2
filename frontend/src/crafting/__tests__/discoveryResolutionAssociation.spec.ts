import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent, h, KeepAlive, ref } from 'vue'
import { ApiRequestError } from '@/api/http'
import { RECIPE_NOT_IN_CALCULATION, type CraftingDiscoveryResolutionResponse } from '@/api/types'
import CraftingDiscoveryScreen from '../CraftingDiscoveryScreen.vue'
import {
  DISCOVERY_SETTINGS,
  FakeCraftingApi,
  craftedTree,
  deferred,
  discoveryResolutionResponse,
  discoveryRows,
  echoedDiscoveryResponse,
  inventoryOnlyTree,
  lessProfitableRow,
  node,
  otherRecipeTree,
  profitableRow
} from './fixtures'

/**
 * Crafting Discovery's selected-recipe fresh detail: the Discovery route's own request body, and the
 * association rules of `TARGET_ARCHITECTURE.md` 13.4 driven through the real screen.
 *
 * A Discovery detail belongs to the recipe, the effective scope, **the effective inventory character**
 * and the effective settings the *table response echoed*, plus a local request generation. The
 * inventory character is what makes this more than Profit's check: the same recipe resolved against
 * another character's owned materials is a different calculation.
 *
 * What a node looks like is not retested here — `CraftingResolution.spec.ts` owns the shared
 * semantic-tree and fresh-row presentation, and Discovery renders through that same component.
 */

const FIRST_SCOPE = { discipline: 'Armorsmith', characterName: 'Nbt Anch', rating: 500 }
const SECOND_SCOPE_ID = 'Chef|400|Sat Anat'

const DISCOVERY_SETTINGS_REQUEST = {
  useOwnMats: DISCOVERY_SETTINGS.useOwnMats,
  allowBuying: DISCOVERY_SETTINGS.allowBuying,
  maxBuyCopper: DISCOVERY_SETTINGS.maxBuyCopper,
  listingSell: DISCOVERY_SETTINGS.listingSell,
  listingBuy: DISCOVERY_SETTINGS.listingBuy
}

async function openScreen(api: FakeCraftingApi): Promise<VueWrapper> {
  const wrapper = mount(CraftingDiscoveryScreen, { props: { api } })
  await flushPromises()
  return wrapper
}

function recipeNames(wrapper: VueWrapper): string[] {
  return wrapper.findAll('[data-test="discovery-row"] .recipe-name').map((element) => element.text())
}

async function selectRecipe(wrapper: VueWrapper, recipeName: string): Promise<void> {
  const index = recipeNames(wrapper).indexOf(recipeName)
  if (index < 0) {
    throw new Error(`No row for "${recipeName}" — rows: ${recipeNames(wrapper).join(', ')}`)
  }
  await wrapper.findAll('[data-test="discovery-select-row"]')[index]?.trigger('click')
  await flushPromises()
}

/** The item name at the root of the tree on screen, or null when no tree is rendered. */
function rootItem(wrapper: VueWrapper): string | null {
  const root = wrapper.find('[data-path="0"] [data-test="node-name"]')
  return root.exists() ? root.text() : null
}

/** A tree whose root item name identifies which answer produced it. */
function treeNamed(itemName: string) {
  return node({ itemName, recipeId: 11, craftCount: 1, producedQuantity: 1 })
}

/** Answers with the echoed inputs replaced, so an identity check has something to refuse. */
function echoingInstead(
  api: FakeCraftingApi,
  override: Partial<CraftingDiscoveryResolutionResponse['calculation']>
): void {
  api.discoveryResolutionHandler = (request) => {
    const answer = discoveryResolutionResponse(request)
    return Promise.resolve({ ...answer, calculation: { ...answer.calculation, ...override } })
  }
}

describe('Crafting Discovery resolution detail association', () => {
  describe('what is requested, and when', () => {
    it('requestsNoDetailUntilARecipeIsSelected', async () => {
      const api = new FakeCraftingApi()

      const wrapper = await openScreen(api)

      // Five candidates are listed and not one of them caused a detail request: detail is lazy.
      expect(recipeNames(wrapper)).toHaveLength(discoveryRows.length)
      expect(api.discoveryRequests).toHaveLength(1)
      expect(api.discoveryResolutionRequests).toHaveLength(0)
      expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)
    })

    it('sendsTheRecipeIdAndTheTablesOwnEchoedInputsIncludingTheInventoryCharacter', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)

      await selectRecipe(wrapper, profitableRow.outputName as string)

      expect(api.discoveryResolutionRequests).toEqual([
        {
          recipeId: profitableRow.recipeId,
          calculation: {
            scope: FIRST_SCOPE,
            inventoryCharacterName: 'Nbt Anch',
            settings: DISCOVERY_SETTINGS_REQUEST
          }
        }
      ])
      // One request for the selection, not one per row.
      expect(api.discoveryResolutionRequests).toHaveLength(1)
    })

    it('leavesTheInventoryCharacterOutWhenTheTableEchoedNone', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await wrapper.find('[data-test="discovery-inventory-selector"]').setValue('')
      await flushPromises()

      await selectRecipe(wrapper, profitableRow.outputName as string)

      // The table reported the unfiltered owned pool, so the detail asks for that same null — the
      // field is omitted rather than sent as a value this client chose.
      const calculation = api.discoveryResolutionRequests.at(-1)?.calculation
      expect(calculation?.inventoryCharacterName).toBeUndefined()
      expect(Object.keys(calculation ?? {})).not.toContain('inventoryCharacterName')
      expect(wrapper.find('[data-test="discovery-detail-resolution-inventory"]').text()).toBe(
        'none (all owned materials)'
      )
    })

    it('carriesAChangedScopeInventoryCharacterAndSettingsIntoTheNextDetailRequest', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)

      await wrapper.find('[data-test="discovery-scope-selector"]').setValue(SECOND_SCOPE_ID)
      await flushPromises()

      expect(api.discoveryResolutionRequests.at(-1)?.calculation.scope).toEqual({
        discipline: 'Chef',
        characterName: 'Sat Anat',
        rating: 400
      })

      // A changed inventory character is a changed calculation, so the detail is asked again for it.
      const beforeInventoryChange = api.discoveryResolutionRequests.length
      await wrapper.find('[data-test="discovery-inventory-selector"]').setValue('Sat Anat')
      await flushPromises()

      expect(api.discoveryResolutionRequests.length).toBe(beforeInventoryChange + 1)
      expect(api.discoveryResolutionRequests.at(-1)?.calculation.inventoryCharacterName).toBe(
        'Sat Anat'
      )

      await wrapper.find('[data-test="discovery-setting-allowBuying"]').setValue(false)
      await flushPromises()

      expect(api.discoveryResolutionRequests.at(-1)?.calculation.settings).toEqual({
        ...DISCOVERY_SETTINGS_REQUEST,
        allowBuying: false
      })
    })

    it('doesNotRequestAgainForSortingOrSearchingAlone', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)
      expect(api.discoveryResolutionRequests).toHaveLength(1)

      await wrapper.find('[data-test="discovery-sort-outputName"]').trigger('click')
      await wrapper.find('[data-test="discovery-sort-profitCopper"]').trigger('click')
      await wrapper.find('[data-test="discovery-search"]').setValue('Iron')
      await flushPromises()

      // Neither changes the calculation, so the detail on screen stays valid and is not re-fetched.
      expect(api.discoveryResolutionRequests).toHaveLength(1)
      expect(api.discoveryRequests).toHaveLength(1)
      expect(rootItem(wrapper)).toBe('Iron Ingot')
    })

    it('invalidatesAndAsksAgainWhenTheTableIsReloaded', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)

      const reloadedDetail = deferred<CraftingDiscoveryResolutionResponse>()
      const tableReload = deferred<ReturnType<typeof echoedDiscoveryResponse>>()
      api.discoveryResolutionHandler = () => reloadedDetail.promise
      api.discoveryHandler = () => tableReload.promise
      await wrapper.find('[data-test="discovery-reload"]').trigger('click')
      await flushPromises()

      // The old tree goes the moment the reload starts; it is not left under a pending answer.
      expect(rootItem(wrapper)).toBeNull()

      tableReload.resolve(echoedDiscoveryResponse(api.discoveryRequests[1]!))
      await flushPromises()
      expect(api.discoveryResolutionRequests).toHaveLength(2)

      reloadedDetail.resolve(
        discoveryResolutionResponse(api.discoveryResolutionRequests[1]!, {
          tree: treeNamed('Reloaded root')
        })
      )
      await flushPromises()
      expect(rootItem(wrapper)).toBe('Reloaded root')
    })
  })

  describe('answers that must not be displayed', () => {
    it('ignoresASupersededAnswerWhenTheSelectionMovedOn', async () => {
      const api = new FakeCraftingApi()
      const first = deferred<CraftingDiscoveryResolutionResponse>()
      api.discoveryResolutionHandler = (request, callIndex) =>
        callIndex === 0
          ? first.promise
          : Promise.resolve(discoveryResolutionResponse(request, { tree: treeNamed('Soup root') }))

      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)
      await selectRecipe(wrapper, lessProfitableRow.outputName as string)

      first.resolve(
        discoveryResolutionResponse(api.discoveryResolutionRequests[0]!, {
          tree: treeNamed('Ingot root')
        })
      )
      await flushPromises()

      expect(rootItem(wrapper)).toBe('Soup root')
    })

    it('ignoresTheFirstAnswerOfAnAToBToASelection', async () => {
      const api = new FakeCraftingApi()
      const firstA = deferred<CraftingDiscoveryResolutionResponse>()
      api.discoveryResolutionHandler = (request, callIndex) => {
        if (callIndex === 0) return firstA.promise
        const tree = callIndex === 1 ? otherRecipeTree : inventoryOnlyTree
        return Promise.resolve(discoveryResolutionResponse(request, { tree }))
      }

      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)
      await selectRecipe(wrapper, lessProfitableRow.outputName as string)
      await selectRecipe(wrapper, profitableRow.outputName as string)

      // The same recipe is selected again, so only the generation can tell the two answers apart.
      firstA.resolve(
        discoveryResolutionResponse(api.discoveryResolutionRequests[0]!, {
          tree: treeNamed('Stale first A')
        })
      )
      await flushPromises()

      expect(api.discoveryResolutionRequests).toHaveLength(3)
      // The third answer's own root, which is owned stock rather than any executed recipe.
      expect(wrapper.find('[data-test="resolution-root-sourcing"]').text()).toContain(
        'No recipe was selected'
      )
      expect(rootItem(wrapper)).not.toBe('Stale first A')
    })

    it('refusesAnAnswerThatEchoesADifferentRecipe', async () => {
      const api = new FakeCraftingApi()
      api.discoveryResolutionHandler = (request) =>
        Promise.resolve(discoveryResolutionResponse({ ...request, recipeId: 999 }))

      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)

      expect(wrapper.find('[data-test="resolution-failed"]').text()).toContain(
        'a different recipe or different calculation inputs'
      )
      expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)
    })

    it('refusesAnAnswerThatEchoesADifferentInventoryCharacter', async () => {
      const api = new FakeCraftingApi()
      // The table echoed "Nbt Anch"; every answer claims another character's owned materials.
      echoingInstead(api, { inventoryCharacterName: 'Sat Anat' })

      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)

      expect(wrapper.find('[data-test="resolution-failed"]').text()).toContain(
        'a different recipe or different calculation inputs'
      )
      expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)
      // The table's own values for the recipe are untouched by a refused explanation.
      expect(wrapper.find('[data-test="discovery-detail-total-profit"]').text()).toBe('+9s 0c')
    })

    it('refusesAnAnswerThatEchoesADifferentInventoryPoolThanTheTableUsed', async () => {
      const api = new FakeCraftingApi()
      // The answer claims the unfiltered pool while the table reported one character's materials.
      echoingInstead(api, { inventoryCharacterName: null })

      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)

      expect(wrapper.find('[data-test="resolution-failed"]').exists()).toBe(true)
      expect(rootItem(wrapper)).toBeNull()
    })

    it('refusesAnAnswerThatEchoesDifferentSettings', async () => {
      const api = new FakeCraftingApi()
      echoingInstead(api, {
        settings: { ...DISCOVERY_SETTINGS, maxBuyCopper: DISCOVERY_SETTINGS.maxBuyCopper + 1 }
      })

      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)

      expect(wrapper.find('[data-test="resolution-failed"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)
    })

    it('ignoresADetailStillInFlightFromThePreviousInventoryCharacter', async () => {
      const api = new FakeCraftingApi()
      const underThePreviousCharacter = deferred<CraftingDiscoveryResolutionResponse>()
      api.discoveryResolutionHandler = (request, callIndex) =>
        callIndex === 0
          ? underThePreviousCharacter.promise
          : Promise.resolve(discoveryResolutionResponse(request, { tree: treeNamed('Sat Anat root') }))

      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)

      await wrapper.find('[data-test="discovery-inventory-selector"]').setValue('Sat Anat')
      await flushPromises()

      underThePreviousCharacter.resolve(
        discoveryResolutionResponse(api.discoveryResolutionRequests[0]!, {
          tree: treeNamed('Nbt Anch root')
        })
      )
      await flushPromises()

      expect(api.discoveryResolutionRequests.at(1)?.calculation.inventoryCharacterName).toBe('Sat Anat')
      expect(rootItem(wrapper)).toBe('Sat Anat root')
    })

    it('clearsTheDetailWhenANewResultSetNoLongerContainsTheSelectedRecipe', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)
      expect(rootItem(wrapper)).toBe('Iron Ingot')

      api.discoveryHandler = (request) =>
        Promise.resolve(echoedDiscoveryResponse(request, [lessProfitableRow]))
      await wrapper.find('[data-test="discovery-reload"]').trigger('click')
      await flushPromises()

      expect(wrapper.find('[data-test="discovery-detail-placeholder"]').text()).toContain(
        'Choose a recipe'
      )
      expect(rootItem(wrapper)).toBeNull()
    })

    it('clearsTheDetailWhenTheReplacementCalculationIsEmpty', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)
      expect(rootItem(wrapper)).toBe('Iron Ingot')

      api.discoveryHandler = (request) => Promise.resolve(echoedDiscoveryResponse(request, []))
      await wrapper.find('[data-test="discovery-reload"]').trigger('click')
      await flushPromises()

      // An empty success is still a result: the stale tree goes with the row it belonged to.
      expect(wrapper.find('[data-test="discovery-empty"]').exists()).toBe(true)
      expect(rootItem(wrapper)).toBeNull()
      expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)
    })
  })

  describe('failures and unavailability', () => {
    it('presentsAnAbsentFreshCandidateAsItsOwnSituation', async () => {
      const api = new FakeCraftingApi()
      api.discoveryResolutionHandler = () =>
        Promise.reject(
          new ApiRequestError('Recipe 11 is not in this calculation.', RECIPE_NOT_IN_CALCULATION, 404)
        )
      const wrapper = await openScreen(api)

      await selectRecipe(wrapper, profitableRow.outputName as string)

      expect(wrapper.find('[data-test="resolution-absent"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="resolution-failed"]').exists()).toBe(false)
      expect(wrapper.find('[data-test="resolution-unavailable"]').exists()).toBe(false)
      // The table's own values for the recipe survive a missing explanation.
      expect(wrapper.find('[data-test="discovery-detail-total-profit"]').text()).toBe('+9s 0c')
    })

    it('keepsATechnicalFailureDistinctAndClearsTheStaleTree', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)
      await selectRecipe(wrapper, profitableRow.outputName as string)
      expect(rootItem(wrapper)).toBe('Iron Ingot')

      api.discoveryResolutionHandler = () =>
        Promise.reject(
          new ApiRequestError('The data store is unavailable.', 'DATA_STORE_UNAVAILABLE', 503)
        )
      await selectRecipe(wrapper, lessProfitableRow.outputName as string)

      expect(wrapper.find('[data-test="resolution-failed"]').text()).toContain('DATA_STORE_UNAVAILABLE')
      expect(wrapper.find('[data-test="resolution-absent"]').exists()).toBe(false)
      expect(rootItem(wrapper)).toBeNull()
    })

    it('presentsAnUnavailableCalculationResultWithoutATree', async () => {
      const api = new FakeCraftingApi()
      api.discoveryResolutionHandler = (request) =>
        Promise.resolve(
          discoveryResolutionResponse(request, { treeStatus: 'RESULT_UNAVAILABLE', tree: null })
        )
      const wrapper = await openScreen(api)

      await selectRecipe(wrapper, profitableRow.outputName as string)

      expect(wrapper.find('[data-test="resolution-unavailable"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)
      expect(wrapper.find('[data-test="resolution-failed"]').exists()).toBe(false)
      // The backend's own reported status stays available as technical information.
      expect(wrapper.find('[data-test="discovery-detail-tree-status"]').text()).toBe(
        'RESULT_UNAVAILABLE'
      )
    })
  })

  describe('what an accepted answer shows', () => {
    it('showsTheFreshTreeWithoutReplacingOrRepeatingTheTableRow', async () => {
      const api = new FakeCraftingApi()
      // The fresh calculation legitimately disagrees with the table's own numbers.
      api.discoveryResolutionHandler = (request) =>
        Promise.resolve(
          discoveryResolutionResponse(request, {
            row: { ...profitableRow, profitCopper: 7, totalProfitCopper: 4_321 }
          })
        )
      const wrapper = await openScreen(api)

      await selectRecipe(wrapper, profitableRow.outputName as string)

      // The Discovery row's own total is what is displayed, and the fresh row's disagreeing
      // figures are neither written over it nor printed beside it (`DOMAIN_SPEC.md` 2.1.1).
      expect(wrapper.find('[data-test="discovery-detail-total-profit"]').text()).toBe('+9s 0c')
      expect(wrapper.find('[data-test="resolution-total-profit"]').exists()).toBe(false)
      expect(wrapper.find('[data-test="resolution-profit"]').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('+43s 21c')
      expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(true)

      // Discovery retains its one-output-batch basis.
      const basis = wrapper.find('[data-test="resolution-basis"]').text().replace(/\s+/g, ' ')
      expect(basis).toContain('one output batch')
      expect(basis).not.toContain('every craft the table counted')
      expect(wrapper.find('[data-test="discovery-detail-tree-basis"]').text()).toBe(
        'SINGLE_OUTPUT_REQUIREMENT'
      )
    })

    it('startsTheSharedTreesGroupsCollapsedForDiscoveryToo', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)

      await selectRecipe(wrapper, profitableRow.outputName as string)

      const groups = wrapper.findAll('[data-test="node-children"]')
      expect(groups).toHaveLength(2)
      for (const group of groups) {
        expect((group.element as HTMLDetailsElement).open).toBe(false)
      }
    })

    it('labelsWhatActuallySuppliedTheRootRatherThanAssumingTheRequestedRecipe', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)

      // The requested recipe really was the one selected.
      await selectRecipe(wrapper, profitableRow.outputName as string)
      expect(wrapper.find('[data-test="resolution-root-sourcing"]').text()).toBe(
        `The requested recipe ${profitableRow.recipeId} is the recipe selected for this requirement.`
      )

      // Another recipe produced the output, and the label says so instead.
      api.discoveryResolutionHandler = (request) =>
        Promise.resolve(discoveryResolutionResponse(request, { tree: otherRecipeTree }))
      await selectRecipe(wrapper, lessProfitableRow.outputName as string)
      expect(wrapper.find('[data-test="resolution-root-sourcing"]').text()).toBe(
        `Recipe 4242 was selected for this requirement, not the requested recipe ${lessProfitableRow.recipeId}.`
      )

      // Owned stock supplied it, so nothing claims a craft happened at all.
      api.discoveryResolutionHandler = (request) =>
        Promise.resolve(discoveryResolutionResponse(request, { tree: inventoryOnlyTree }))
      await selectRecipe(wrapper, profitableRow.outputName as string)
      expect(wrapper.find('[data-test="resolution-root-sourcing"]').text()).toContain(
        'No recipe was selected'
      )
    })

    it('rendersTheSuppliedRequirementsThroughTheSharedTreeInTheOrderTheyArrived', async () => {
      const api = new FakeCraftingApi()
      const wrapper = await openScreen(api)

      await selectRecipe(wrapper, profitableRow.outputName as string)

      // The Discovery response's own tree, reaching the shared component whole: every node, in the
      // supplied order, with a repeated requirement kept as two occurrences. How each node presents
      // its quantities, costs and codes is `CraftingResolution.spec.ts`'s.
      const nodes = wrapper.findAll('[data-test="tree-node"]')
      expect(nodes.map((element) => element.attributes('data-path'))).toEqual([
        '0',
        '0.0',
        '0.1',
        '0.1.0'
      ])
      expect(nodes.map((element) => element.find('[data-test="node-name"]').text())).toEqual([
        'Iron Ingot',
        'Copper Ore',
        'Charged Core',
        'Copper Ore'
      ])
      expect(craftedTree.children).toHaveLength(2)

      // The inclusive cost a node carried is printed as supplied — the root's 832 is not its
      // children's 72 and 500 added up.
      expect(wrapper.find('[data-path="0"] [data-test="node-effective-cost"]').text()).toBe('8s 32c')
      // And a cost the backend could not establish stays missing rather than becoming zero.
      expect(wrapper.find('[data-path="0.1"] [data-test="node-effective-cost"]').text()).toBe('—')
    })
  })

  describe('leaving the page while it is kept alive', () => {
    /** The shell's own arrangement: the screen survives navigation instead of being re-created. */
    const KeptAliveShell = defineComponent({
      props: { api: { type: FakeCraftingApi, required: true } },
      setup(props) {
        const open = ref(true)
        return () => [
          h('button', { 'data-test': 'toggle', onClick: () => (open.value = !open.value) }, 'toggle'),
          h(KeepAlive, null, {
            default: () => (open.value ? h(CraftingDiscoveryScreen, { api: props.api }) : null)
          })
        ]
      }
    })

    it('invalidatesOnLeavingAndAsksFreshlyOnReturn', async () => {
      const api = new FakeCraftingApi()
      const wrapper = mount(KeptAliveShell, { props: { api } })
      await flushPromises()
      await selectRecipe(wrapper, profitableRow.outputName as string)
      expect(rootItem(wrapper)).toBe('Iron Ingot')

      await wrapper.find('[data-test="toggle"]').trigger('click')
      await flushPromises()

      api.discoveryResolutionHandler = (request) =>
        Promise.resolve(discoveryResolutionResponse(request, { tree: treeNamed('Asked again') }))
      await wrapper.find('[data-test="toggle"]').trigger('click')
      await flushPromises()

      // The selection survived the trip, the calculation was not re-posted, and the detail is new.
      expect(api.discoveryRequests).toHaveLength(1)
      expect(api.discoveryResolutionRequests).toHaveLength(2)
      expect(rootItem(wrapper)).toBe('Asked again')
    })

    it('doesNotLetAnAnswerThatArrivesWhileAwayReviveTheDetail', async () => {
      const api = new FakeCraftingApi()
      const pending = deferred<CraftingDiscoveryResolutionResponse>()
      api.discoveryResolutionHandler = (request, callIndex) =>
        callIndex === 0 ? pending.promise : Promise.resolve(discoveryResolutionResponse(request))

      const wrapper = mount(KeptAliveShell, { props: { api } })
      await flushPromises()
      await selectRecipe(wrapper, profitableRow.outputName as string)

      await wrapper.find('[data-test="toggle"]').trigger('click')
      await flushPromises()
      pending.resolve(
        discoveryResolutionResponse(api.discoveryResolutionRequests[0]!, {
          tree: treeNamed('Late root')
        })
      )
      await flushPromises()

      await wrapper.find('[data-test="toggle"]').trigger('click')
      await flushPromises()

      expect(rootItem(wrapper)).not.toBe('Late root')
      expect(rootItem(wrapper)).toBe('Iron Ingot')
    })
  })
})
