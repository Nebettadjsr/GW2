import { mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { CraftingProfitResolutionResponse } from '@/api/types'
import CraftingResolution from '../CraftingResolution.vue'
import type { ResolutionPhase } from '../useProfitResolution'
import {
  blockedTree,
  craftedTree,
  inventoryOnlyTree,
  noResultRow,
  otherRecipeTree,
  profitableRow,
  resolutionResponse
} from './fixtures'

/**
 * Rendering of the backend's resolution answer (`TARGET_ARCHITECTURE.md` 13.3/13.4).
 *
 * Every assertion here is about a value the fixture supplied reaching the screen unchanged. No test
 * recomputes a domain result: the fixtures' inclusive costs are deliberately not the sums of their
 * children and `producedQuantity` deliberately exceeds `craftedQuantity`, so a component that tried
 * to work either out would fail these tests rather than pass them (TEST_STRATEGY 12).
 */
function render(
  phase: ResolutionPhase,
  detail: CraftingProfitResolutionResponse | null = null,
  failure: string | null = null,
  requestedRecipeId: number | null = profitableRow.recipeId
) {
  return mount(CraftingResolution, {
    props: { phase, detail, failure, requestedRecipeId }
  })
}

function ready(overrides: Partial<CraftingProfitResolutionResponse> = {}, recipeId = profitableRow.recipeId) {
  return render('ready', resolutionResponse({ recipeId, calculation: {} }, overrides), null, recipeId)
}

/** The nodes in document order, keyed by the index path the component assigns them. */
function nodePaths(wrapper: VueWrapper): string[] {
  return wrapper.findAll('[data-test="tree-node"]').map((node) => node.attributes('data-path') ?? '')
}

function nodeAt(wrapper: VueWrapper, path: string) {
  const node = wrapper.find(`[data-path="${path}"]`)
  if (!node.exists()) throw new Error(`No node at ${path} — have ${nodePaths(wrapper).join(', ')}`)
  return node
}

function fact(wrapper: VueWrapper, path: string, test: string): string {
  return nodeAt(wrapper, path).find(`[data-test="${test}"]`).text()
}

describe('CraftingResolution', () => {
  describe('the five situations it tells apart', () => {
    it('showsLoadingInItsOwnRegionWithoutATree', () => {
      const region = render('loading')

      expect(region.find('[data-test="resolution-loading"]').attributes('role')).toBe('status')
      expect(region.find('[data-test="resolution-tree"]').exists()).toBe(false)
    })

    it('distinguishesAnAbsentCandidateFromAFailedRequest', () => {
      const absent = render('absent', null, 'Recipe 11 is not in this calculation.')
      expect(absent.find('[data-test="resolution-absent"]').text()).toContain(
        'no longer offers this recipe'
      )
      expect(absent.text()).toContain('does not mean the recipe is gone from the database')
      expect(absent.find('[data-test="resolution-failed"]').exists()).toBe(false)

      const failed = render('failed', null, 'DATA_STORE_UNAVAILABLE: The data store is unavailable.')
      expect(failed.find('[data-test="resolution-failed"]').text()).toContain(
        'a request that did not work'
      )
      expect(failed.text()).toContain('DATA_STORE_UNAVAILABLE')
      expect(failed.find('[data-test="resolution-absent"]').exists()).toBe(false)
    })

    it('presentsAnUnavailableResultAsTheCalculationsOwnAnswerAndShowsNoTree', () => {
      const region = render(
        'unavailable',
        resolutionResponse(
          { recipeId: noResultRow.recipeId, calculation: {} },
          { row: noResultRow, treeStatus: 'RESULT_UNAVAILABLE', tree: null }
        ),
        null,
        noResultRow.recipeId
      )

      expect(region.find('[data-test="resolution-unavailable"]').text()).toContain(
        'no result to explain'
      )
      expect(region.text()).toContain('not a failed request')
      expect(region.find('[data-test="resolution-tree"]').exists()).toBe(false)
    })

    it('showsABlockedTreeAsAnAnswerRatherThanAnError', () => {
      const region = ready({ tree: blockedTree })

      expect(region.find('[data-test="resolution-tree"]').exists()).toBe(true)
      expect(region.find('[data-test="resolution-failed"]').exists()).toBe(false)
      expect(region.find('[data-test="resolution-unavailable"]').exists()).toBe(false)
      expect(nodeAt(region, '0').find('[data-test="node-blocked-reason"]').text()).toBe('Buying is off')
    })

    it('neverLeavesATreeUpWhenTheSituationIsNotReady', () => {
      for (const phase of ['idle', 'loading', 'absent', 'failed', 'unavailable'] as ResolutionPhase[]) {
        expect(render(phase).find('[data-test="resolution-tree"]').exists()).toBe(false)
      }
    })
  })

  describe('the basis it states for what it shows', () => {
    it('callsItAFreshCalculationOfOneOutputBatchAndNotTheTablesOwnExplanation', () => {
      const basis = ready().find('[data-test="resolution-basis"]').text()

      expect(basis).toContain('A separate calculation')
      expect(basis).toContain('one output batch')
      expect(basis).toContain('not every craft the table counted')
      expect(basis).toContain('neither replaces the other')
    })

    it('doesNotDescribeTheTreeAsATraceOfEveryCountedCraft', () => {
      expect(ready().text()).not.toContain('every craft counted')
    })
  })

  describe('requested identity versus actual root sourcing', () => {
    it('saysTheRequestedRecipeIsTheOneSelectedWhenItIs', () => {
      expect(ready({ tree: craftedTree }).find('[data-test="resolution-root-sourcing"]').text()).toBe(
        'The requested recipe 11 is the recipe selected for this requirement.'
      )
    })

    it('doesNotCallAnInventoryOnlyRootAnExecutionOfTheRequestedRecipe', () => {
      const region = ready({ tree: inventoryOnlyTree })

      expect(region.find('[data-test="resolution-root-sourcing"]').text()).toContain(
        'No recipe was selected'
      )
      expect(fact(region, '0', 'node-recipe')).toBe('None selected')
      expect(fact(region, '0', 'node-craft-count')).toBe('0')
      expect(fact(region, '0', 'node-produced')).toBe('0')
      expect(fact(region, '0', 'node-methods')).toBe('From stock')
      // No recipe, so no ingredient is invented for it.
      expect(nodePaths(region)).toEqual(['0'])
    })

    it('keepsAnotherProducingRecipesOwnIdentityExecutionsAndChildren', () => {
      const region = ready({ tree: otherRecipeTree })

      expect(region.find('[data-test="resolution-root-sourcing"]').text()).toBe(
        'Recipe 4242 was selected for this requirement, not the requested recipe 11.'
      )
      expect(fact(region, '0', 'node-recipe')).toBe('4242')
      expect(fact(region, '0', 'node-craft-count')).toBe('2')
      expect(nodePaths(region)).toEqual(['0', '0.0'])
    })

    it('keepsABlockedAttemptedRecipeWithoutClaimingACompletedCraft', () => {
      const region = ready({ tree: blockedTree }, blockedTree.recipeId ?? 13)

      expect(fact(region, '0', 'node-recipe')).toBe('13')
      expect(fact(region, '0', 'node-craft-count')).toBe('0')
      expect(fact(region, '0', 'node-methods')).toBe('Nothing supplied this requirement')
      expect(fact(region, '0', 'node-effective-cost')).toBe('—')
    })
  })

  describe('the facts on each node', () => {
    it('showsEverySuppliedQuantityMethodRecipeAndCharacter', () => {
      const region = ready({ tree: craftedTree })

      expect(fact(region, '0', 'node-name')).toBe('Iron Ingot')
      expect(fact(region, '0', 'node-requested')).toBe('1 needed')
      expect(fact(region, '0', 'node-character')).toBe('Nbt Anch')
      // 1 unit used here out of a batch of 3 produced: both numbers are shown, neither is derived.
      expect(fact(region, '0', 'node-crafted')).toBe('1')
      expect(fact(region, '0', 'node-produced')).toBe('3')

      // Split sourcing keeps every contributing method, in the supplied order.
      const split = nodeAt(region, '0.0')
      expect(split.findAll('[data-test="node-method"]').map((chip) => chip.text())).toEqual([
        'From stock',
        'Bought'
      ])
      expect(fact(region, '0.0', 'node-inventory')).toBe('2')
      expect(fact(region, '0.0', 'node-bought')).toBe('4')
      expect(fact(region, '0.1', 'node-character')).toBe('Not assigned')
    })

    it('printsInclusiveCostsAsSuppliedWithoutAddingChildrenIntoParents', () => {
      const region = ready({ tree: craftedTree })

      // Children are 72 and (null) — a parent that summed anything could not print 832.
      expect(fact(region, '0', 'node-effective-cost')).toBe('8s 32c')
      expect(fact(region, '0', 'node-cash-cost')).toBe('7s 77c')
      expect(fact(region, '0.0', 'node-effective-cost')).toBe('72c')
      expect(nodeAt(region, '0').text()).toContain('already includes everything below')
    })

    it('keepsAKnownZeroApartFromACostItCouldNotEstablish', () => {
      const region = ready({ tree: blockedTree })

      // An unvalued non-tradable item is a domain zero and stays 0c with its own state.
      expect(fact(region, '0.1', 'node-effective-cost')).toBe('0c')
      expect(nodeAt(region, '0.1').find('[data-test="node-state"]').text()).toBe(
        'Not tradable, valued at zero'
      )
      expect(nodeAt(region, '0.1').find('[data-test="node-state-explanation"]').text()).toContain(
        'known zero rather than a missing price'
      )

      // An unknown purchase price leaves no cost at all, and never becomes zero.
      expect(fact(region, '0.0', 'node-cash-cost')).toBe('—')
      expect(nodeAt(region, '0.0').text()).toContain('it is not zero')
    })

    it('keepsRepeatedItemsAsSeparateOrderedOccurrences', () => {
      const region = ready({ tree: craftedTree })

      // "Copper Ore" is a requirement of the root and again of a nested craft: two nodes, not one.
      const copperOre = region
        .findAll('[data-test="tree-node"]')
        .filter((node) => node.find('[data-test="node-name"]').text() === 'Copper Ore')
      expect(copperOre).toHaveLength(2)
      expect(copperOre.map((node) => node.attributes('data-path'))).toEqual(['0.0', '0.1.0'])
      // Their quantities stay their own rather than being merged.
      expect(fact(region, '0.0', 'node-requested')).toBe('6 needed')
      expect(fact(region, '0.1.0', 'node-requested')).toBe('2 needed')
    })

    it('rendersEveryNodeOfTheTreeInOrderWithNothingCutOff', () => {
      expect(nodePaths(ready({ tree: craftedTree }))).toEqual(['0', '0.0', '0.1', '0.1.0'])
      expect(nodePaths(ready({ tree: blockedTree }))).toEqual(['0', '0.0', '0.1', '0.2'])
    })

    it('opensEveryChildGroupSoNoBranchStartsHidden', () => {
      const region = ready({ tree: craftedTree })

      const groups = region.findAll('[data-test="node-children"]')
      expect(groups).toHaveLength(2)
      for (const group of groups) expect(group.attributes('open')).toBeDefined()
      expect(groups[0]?.find('summary').text()).toBe('2 ingredient requirements')
      expect(groups[1]?.find('summary').text()).toBe('1 ingredient requirement')
    })

    it('fallsBackToTheItemIdWhenNoNameWasSupplied', () => {
      expect(fact(ready({ tree: blockedTree }), '0.2', 'node-name')).toBe('Item #93')
    })
  })

  describe('state, reason and unknown codes', () => {
    it('explainsEachSuppliedBlockedReasonWithoutDroppingAny', () => {
      const region = ready({ tree: blockedTree })

      expect(
        nodeAt(region, '0.0')
          .findAll('[data-test="node-blocked-reason"]')
          .map((reason) => reason.text())
      ).toEqual(['Price missing', 'No recipe'])
      const sentence = nodeAt(region, '0.0').find('[data-test="node-blocked-explanation"]').text()
      expect(sentence).toContain('no price is available for it')
      expect(sentence).toContain('this item has no usable recipe')
    })

    it('wordsBuyingDisabledAsThisRequirementNotAsLostCrafts', () => {
      const sentence = nodeAt(ready({ tree: blockedTree }), '0')
        .find('[data-test="node-blocked-explanation"]')
        .text()

      expect(sentence).toBe(
        'Blocked because it would have to be bought to resolve this requirement, and buying is ' +
          'switched off.'
      )
    })

    it('keepsTheFreshRowsOwnTotalsIncludingItsSellValue', () => {
      // A fresh calculation is its own answer, so its totals are whatever it supplied - not the
      // table row's, and not a product of this row's revenue and count (5 x 380 = 19s 0c).
      const region = ready({
        row: { ...profitableRow, totalSellValueCopper: 3_333, totalProfitCopper: 1_111 }
      })

      expect(region.find('[data-test="resolution-total-sell-value"]').text()).toBe('33s 33c')
      expect(region.find('[data-test="resolution-total-profit"]').text()).toBe('+11s 11c')
      expect(region.find('[data-test="resolution-row"]').text()).toContain('Total sell value')
      expect(region.find('[data-test="resolution-row-basis"]').text()).toContain('For all 5 crafts')
    })

    it('leavesAFreshRowWithNoSuppliedTotalsMarkedAsUnsupplied', () => {
      const region = ready({ row: noResultRow })

      expect(region.find('[data-test="resolution-total-sell-value"]').text()).toBe('—')
      expect(region.find('[data-test="resolution-total-profit"]').text()).toBe('—')
    })

    it('showsAnUnknownCodeAsItselfAndNeverAsSuccess', () => {
      const unknown = nodeAt(ready({ tree: blockedTree }), '0.2')

      expect(unknown.find('[data-test="node-method"]').text()).toBe('SALVAGE_ADDED_LATER (not recognized)')
      expect(unknown.find('[data-test="node-state"]').text()).toBe('SOME_STATE_ADDED_LATER (not recognized)')
      expect(unknown.find('[data-test="node-state"]').classes()).toContain('status--unknown')
      expect(unknown.find('[data-test="node-state"]').classes()).not.toContain('status--success')
      expect(unknown.find('[data-test="node-blocked-explanation"]').text()).toContain(
        'which this page does not recognize'
      )
    })
  })
})
