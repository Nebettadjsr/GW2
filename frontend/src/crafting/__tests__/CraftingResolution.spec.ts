import { mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { CraftingProfitResolutionResponse } from '@/api/types'
import CraftingResolution from '../CraftingResolution.vue'
import type { ResolutionPhase } from '../useResolutionDetail'
import {
  blockedTree,
  craftedTree,
  inventoryOnlyTree,
  node,
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
  requestedRecipeId: number | null = profitableRow.recipeId,
  selectedResultMode = true
) {
  return mount(CraftingResolution, {
    props: { phase, detail, failure, requestedRecipeId, selectedResultMode }
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

/**
 * A node's *own* element, not one of its descendants': a parent `<li>` contains every child node's
 * markup, so `find` inside it would report a child's explanation as the parent's.
 */
function ownFacts(wrapper: VueWrapper, path: string, test: string): string[] {
  const own = nodeAt(wrapper, path).element.querySelectorAll(`:scope > [data-test="${test}"]`)
  return [...own].map((element) => (element.textContent ?? '').replace(/\s+/g, ' ').trim())
}

/** The index paths of the child groups currently expanded, so "which one" is part of the assertion. */
function expandedGroups(wrapper: VueWrapper): string[] {
  return wrapper
    .findAll('[data-test="node-children"]')
    .filter((group) => (group.element as HTMLDetailsElement).open)
    .map((group) => group.element.parentElement?.getAttribute('data-path') ?? '')
}

/** This node's own child group — not a descendant's, which its markup also contains. */
function groupOf(wrapper: VueWrapper, path: string) {
  const group = nodeAt(wrapper, path).element.querySelector(':scope > [data-test="node-children"]')
  if (group === null) throw new Error(`The node at ${path} has no child group`)
  return group as HTMLDetailsElement
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
    it('doesNotDescribeASeparateOneBatchCalculationOrRepeatTheExpectedRootRecipe', () => {
      const region = ready()
      expect(region.find('[data-test="resolution-basis"]').exists()).toBe(false)
      expect(region.find('[data-test="resolution-root-sourcing"]').exists()).toBe(false)
      expect(region.text()).not.toContain('What supplied the output')
    })

    it('omitsTheGenericTreeHelpParagraph', () => {
      const region = ready({ tree: craftedTree })
      expect(region.find('[data-test="resolution-tree-note"]').exists()).toBe(false)
    })

    it('rendersBackendSuppliedFullQuantitiesAndEffectiveValueWithoutRecomputingThem', () => {
      const root = node({
        ...craftedTree,
        requestedQuantity: 41,
        cashCostCopper: 900,
        opportunityCostCopper: 334,
        effectiveCostCopper: 1234,
        children: [node({
          itemId: 200,
          itemName: 'Cured Thin Leather Square',
          requestedQuantity: 205,
          craftedQuantity: 205,
          methods: ['CRAFT'],
          cashCostCopper: 700,
          opportunityCostCopper: 534,
          effectiveCostCopper: 1234
        })]
      })
      const region = ready({ tree: root })

      expect(nodeAt(region, '0').find('[data-test="node-requested"]').text()).toBe('41 needed')
      expect(nodeAt(region, '0.0').find('[data-test="node-requested"]').text()).toBe('205 needed')
      expect(nodeAt(region, '0').find('[data-test="node-value"]').text()).toBe('Value: 12s 34c')
    })
  })

  describe('requested identity versus actual root sourcing', () => {
    it('omitsRootSourcingThatOnlyRestatesTheSelectedRecipe', () => {
      expect(ready({ tree: craftedTree }).find('[data-test="resolution-root-sourcing"]').exists()).toBe(false)
    })

    it('doesNotCallAnInventoryOnlyRootAnExecutionOfTheRequestedRecipe', () => {
      const region = ready({ tree: inventoryOnlyTree })

      expect(region.find('[data-test="resolution-root-sourcing"]').text()).toContain(
        'No recipe was selected'
      )
      expect(fact(region, '0', 'node-methods')).toBe('From stock')
      // No recipe, so no ingredient is invented for it — and no group to expand either.
      expect(nodePaths(region)).toEqual(['0'])
      expect(region.find('[data-test="node-children"]').exists()).toBe(false)
    })

    it('keepsAnotherProducingRecipesOwnIdentityAndChildren', () => {
      const region = ready({ tree: otherRecipeTree })

      // The producing recipe's identity is the sourcing line's, now that the node's own recipe row
      // has gone: removing the bookkeeping must not make the root read as the requested recipe.
      expect(region.find('[data-test="resolution-root-sourcing"]').text()).toBe(
        'Recipe 4242 was selected for this requirement, not the requested recipe 11.'
      )
      expect(nodePaths(region)).toEqual(['0', '0.0'])
    })

    it('keepsABlockedAttemptedRecipeWithoutClaimingACompletedCraft', () => {
      const region = ready({ tree: blockedTree }, blockedTree.recipeId ?? 13)

      expect(fact(region, '0', 'node-methods')).toBe('Nothing supplied this requirement')
      expect(fact(region, '0', 'node-value')).toBe('Value: —')
      expect(ownFacts(region, '0', 'node-blocked-explanation')).toHaveLength(1)
    })
  })

  describe('the compact summary each node shows', () => {
    it('doesNotRenderPerNodeTechnicalDetails', () => {
      const region = ready({ tree: craftedTree })

      expect(region.findAll('[data-test="tree-node"]')).toHaveLength(4)
      expect(region.findAll('[data-test="node-technical-details"]')).toHaveLength(0)
    })

    it('showsIdentityRequiredQuantitySuppliedSourcingAndTheNamedCrafter', () => {
      const region = ready({ tree: craftedTree })

      expect(fact(region, '0', 'node-name')).toBe('Iron Ingot')
      expect(fact(region, '0', 'node-requested')).toBe('1 needed')
      expect(fact(region, '0', 'node-crafter')).toBe('Crafted by Nbt Anch')

      // Split sourcing keeps every contributing method, in the supplied order: a mixed requirement
      // is not forced into one label (`DOMAIN_SPEC.md` 2.1.1).
      const split = nodeAt(region, '0.0')
      expect(split.findAll('[data-test="node-method"]').map((chip) => chip.text())).toEqual([
        'From stock',
        'Bought'
      ])
      expect(fact(region, '0.0', 'node-requested')).toBe('6 needed')

      // A node the backend assigned no character to carries no crafter line at all, rather than a
      // "Not assigned" row on every requirement.
      expect(ownFacts(region, '0.1', 'node-crafter')).toEqual([])
      expect(region.text()).not.toContain('Not assigned')
    })

    it('omitsTheResolverBookkeepingRowsFromTheNormalNode', () => {
      const region = ready({ tree: craftedTree })

      // Request-012 / DOMAIN_SPEC 2.1.1: the stock/crafted/bought/missing split, the producing
      // recipe and its id, the craft count and the produced batch total are gone from the normal
      // view. The transport fields they came from are untouched.
      for (const removed of [
        'node-inventory',
        'node-crafted',
        'node-bought',
        'node-missing',
        'node-recipe',
        'node-craft-count',
        'node-produced',
        'node-character'
      ]) {
        expect(region.find(`[data-test="${removed}"]`).exists()).toBe(false)
      }
      const text = region.text().replace(/\s+/g, ' ')
      for (const label of [
        'Crafted for this requirement',
        'Producing recipe',
        'Crafts run',
        'Produced in total',
        'None selected'
      ]) {
        expect(text).not.toContain(label)
      }
      // The supplied values themselves are still in the response the region was given.
      expect(craftedTree.producedQuantity).toBe(3)
      expect(craftedTree.recipeId).toBe(11)
    })

    it('keepsAKnownZeroApartFromACostItCouldNotEstablish', () => {
      const region = ready({ tree: blockedTree })

      // An unvalued non-tradable item is a domain zero and stays 0c with its own state.
      expect(fact(region, '0.1', 'node-value')).toBe('Value: 0c')
      expect(nodeAt(region, '0.1').find('[data-test="node-state"]').text()).toBe(
        'Not tradable, valued at zero'
      )
      expect(nodeAt(region, '0.1').find('[data-test="node-state-explanation"]').text()).toContain(
        'known zero rather than a missing price'
      )

      // An unknown purchase price leaves no cost at all, and never becomes zero. The node's own
      // state sentence still says so for the item it is about; the marker is explained once for
      // the tree rather than under every requirement.
      expect(fact(region, '0.0', 'node-value')).toBe('Value: —')
      expect(ownFacts(region, '0.0', 'node-state-explanation')).toContain(
        'No purchase price is available for Pile of Dust. A cost shown as missing is unknown, not zero.'
      )
      expect(region.find('[data-test="resolution-tree-note"]').exists()).toBe(false)
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

    it('fallsBackToTheItemIdWhenNoNameWasSupplied', () => {
      expect(fact(ready({ tree: blockedTree }), '0.2', 'node-name')).toBe('Item #93')
    })
  })

  describe('collapsed ingredient groups', () => {
    it('startsEveryGroupCollapsedIncludingTheRootsOwn', () => {
      const region = ready({ tree: craftedTree })

      const groups = region.findAll('[data-test="node-children"]')
      expect(groups).toHaveLength(2)
      expect(expandedGroups(region)).toEqual([])
      expect(groups[0]?.find('summary').text()).toBe('2 ingredient requirements')
      expect(groups[1]?.find('summary').text()).toBe('1 ingredient requirement')
    })

    it('keepsEachNodesSummaryOutsideItsChildDisclosure', () => {
      const region = ready({ tree: craftedTree })

      // The root's own identity, quantity, sourcing, crafter and value stay readable while its
      // ingredients are collapsed — only the children sit inside the group.
      const root = nodeAt(region, '0').element
      const group = groupOf(region, '0')
      for (const own of ['node-name', 'node-requested', 'node-methods', 'node-crafter', 'node-value']) {
        const element = root.querySelector(`[data-test="${own}"]`)
        expect(element).not.toBeNull()
        expect(group.contains(element)).toBe(false)
      }
      expect([...group.children].map((child) => child.tagName)).toEqual(['SUMMARY', 'UL'])
      expect(group.querySelectorAll('[data-test="tree-node"]')).toHaveLength(3)
    })

    it('expandsOneGroupWithoutExpandingItsDescendants', async () => {
      const region = ready({ tree: craftedTree })

      // A native `<summary>` is the disclosure control: it is in the tab order and browsers
      // activate it from Enter/Space. jsdom drives that activation from a click; real keyboard
      // operation is checked by the browser smoke (`npm run smoke:profit`).
      const summary = nodeAt(region, '0').find(':scope > [data-test="node-children"] > summary')
      expect((summary.element as HTMLElement).tabIndex).toBe(0)

      await summary.trigger('click')
      expect(expandedGroups(region)).toEqual(['0'])

      // The nested craft revealed by that click is still collapsed: nothing cascaded.
      expect(groupOf(region, '0.1').open).toBe(false)
      await nodeAt(region, '0.1').find(':scope > [data-test="node-children"] > summary').trigger('click')
      expect(expandedGroups(region)).toEqual(['0', '0.1'])

      // And each closes on its own.
      await summary.trigger('click')
      expect(expandedGroups(region)).toEqual(['0.1'])
    })

    it('startsCollapsedAgainWhenAReplacementAnswerArrives', async () => {
      const region = render(
        'ready',
        resolutionResponse({ recipeId: profitableRow.recipeId, calculation: {} }),
        null,
        profitableRow.recipeId
      )
      await nodeAt(region, '0').find(':scope > [data-test="node-children"] > summary').trigger('click')
      expect(expandedGroups(region)).toEqual(['0'])

      await region.setProps({
        detail: resolutionResponse(
          { recipeId: profitableRow.recipeId, calculation: {} },
          { tree: craftedTree }
        )
      })

      expect(region.findAll('[data-test="node-children"]')).toHaveLength(2)
      expect(expandedGroups(region)).toEqual([])
    })

    it('rendersEveryRequirementOfACollapsedTreeRatherThanWithholdingIt', () => {
      // Collapsing is presentation: a five-deep chain is all present, in order, with no cap.
      const deep = node({
        itemId: 600,
        itemName: 'Level 1',
        children: [
          node({
            itemId: 601,
            itemName: 'Level 2',
            children: [
              node({
                itemId: 602,
                itemName: 'Level 3',
                children: [
                  node({ itemId: 603, itemName: 'Level 4', children: [node({ itemId: 604, itemName: 'Level 5' })] })
                ]
              })
            ]
          })
        ]
      })
      const region = ready({ tree: deep })

      expect(nodePaths(region)).toEqual(['0', '0.0', '0.0.0', '0.0.0.0', '0.0.0.0.0'])
      expect(expandedGroups(region)).toEqual([])
      expect(
        region.findAll('[data-test="node-name"]').map((name) => name.text())
      ).toEqual(['Level 1', 'Level 2', 'Level 3', 'Level 4', 'Level 5'])
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
      // DOMAIN_SPEC 2.1.1: the missing-price cause names the item it is about, which is this node's
      // own item and not the root's.
      expect(sentence).toContain('no price is available for Pile of Dust')
      expect(sentence).not.toContain('Mystic Curio')
      expect(sentence).toContain('this item has no usable recipe')
    })

    it('namesTheItemWithNoPriceBesideItsOwnRequirementAtEveryDepth', () => {
      // A nested requirement with no price, and a sibling with one, so the name cannot come from the
      // tree as a whole. The unnamed node falls back to its item id, which is the identity it has.
      const region = ready({
        tree: node({
          itemId: 500,
          itemName: 'Deep Root',
          children: [
            node({
              itemId: 501,
              itemName: 'Mithril Ore',
              children: [
                node({
                  itemId: 502,
                  itemName: null,
                  states: ['PRICE_UNAVAILABLE'],
                  blockedReasons: ['PRICE_UNAVAILABLE'],
                  cashCostCopper: null,
                  opportunityCostCopper: null,
                  effectiveCostCopper: null
                })
              ]
            })
          ]
        })
      })

      expect(fact(region, '0.0.0', 'node-name')).toBe('Item #502')
      expect(ownFacts(region, '0.0.0', 'node-state-explanation')).toEqual([
        'No purchase price is available for Item #502. A cost shown as missing is unknown, not zero.'
      ])
      expect(ownFacts(region, '0.0.0', 'node-blocked-explanation')).toEqual([
        'Blocked because no price is available for Item #502.'
      ])
      // And the requirement above it, which has a price, carries no restriction of its own.
      expect(ownFacts(region, '0.0', 'node-blocked-explanation')).toEqual([])
      expect(ownFacts(region, '0.0', 'node-state-explanation')).toEqual([])
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

  describe('the removed fresh-row summary', () => {
    it('showsNoneOfTheFreshRowsOwnFiguresInTheNormalView', () => {
      // DOMAIN_SPEC 2.1.1 removes the "This recipe in that fresh calculation" block. The response
      // still carries the row — nothing about the request or the contract changes — it is simply
      // not a second set of numbers on screen beside the table's.
      const region = ready({
        row: { ...profitableRow, totalSellValueCopper: 3_333, totalProfitCopper: 1_111 }
      })

      for (const gone of [
        'resolution-row',
        'resolution-row-status',
        'resolution-row-basis',
        'resolution-profit',
        'resolution-craftable',
        'resolution-total-sell-value',
        'resolution-total-profit',
        'resolution-buy-cost'
      ]) {
        expect(region.find(`[data-test="${gone}"]`).exists()).toBe(false)
      }
      const text = region.text().replace(/\s+/g, ' ')
      expect(text).not.toContain('This recipe in that fresh calculation')
      expect(text).not.toContain('33s 33c')
      expect(text).not.toContain('fresh calculation counted')

      // The tree itself is untouched by the removal.
      expect(region.find('[data-test="resolution-tree"]').exists()).toBe(true)
    })

    it('keepsAFreshRowWithNoResultFromReadingAsSuccessBecauseItsChipWentAway', () => {
      // The removed chip was the *row's* state. Every unresolved fact the tree carries still has
      // its own marker and sentence, so nothing reads as success for having lost a label.
      const region = ready({ row: noResultRow, tree: blockedTree })

      expect(region.find('[data-test="resolution-row-status"]').exists()).toBe(false)
      expect(nodeAt(region, '0').find('[data-test="node-blocked-reason"]').text()).toBe(
        'Buying is off'
      )
      expect(
        nodeAt(region, '0.0')
          .findAll('[data-test="node-state"]')
          .map((state) => state.text())
      ).toEqual(['Price missing', 'Blocked'])
      expect(region.find('.status--success').exists()).toBe(false)
    })

    it('stillTellsTheFiveSituationsApartWithNoRowOnScreen', () => {
      // Hiding the summary is presentation only: an answer with no tree is still the calculation's
      // own answer, and a failed request is still a failed request.
      const unavailable = render(
        'unavailable',
        resolutionResponse(
          { recipeId: noResultRow.recipeId, calculation: {} },
          { row: noResultRow, treeStatus: 'RESULT_UNAVAILABLE', tree: null }
        ),
        null,
        noResultRow.recipeId
      )
      expect(unavailable.find('[data-test="resolution-unavailable"]').exists()).toBe(true)
      expect(unavailable.find('[data-test="resolution-row"]').exists()).toBe(false)

      const failed = render('failed', null, 'DATA_STORE_UNAVAILABLE: The data store is unavailable.')
      expect(failed.find('[data-test="resolution-failed"]').exists()).toBe(true)
      expect(failed.find('[data-test="resolution-row"]').exists()).toBe(false)
    })
  })
})
