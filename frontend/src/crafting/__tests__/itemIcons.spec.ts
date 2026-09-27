import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { CraftingRow } from '@/api/types'
import CraftingProfitScreen from '../CraftingProfitScreen.vue'
import SelectedResultDetail from '../SelectedResultDetail.vue'
import {
  FakeCraftingApi,
  lossRow,
  node,
  echoedProfitResponse,
  profitableRow,
  resolutionResponse
} from './fixtures'

/**
 * The shared item image in its Crafting Profit consumers (`TARGET_ARCHITECTURE.md` 12.1, AR-005):
 * the comparison rows, the selected recipe's detail, the materials still to buy, and the resolution
 * tree.
 *
 * The point of every check here is *which* item's supplied URL ends up on screen. The backend sends
 * an image URL per item; this client renders it and builds none — not from a recipe id, not from an
 * item id, and never from an upstream address. Nothing here asserts that an image loaded: jsdom does
 * not fetch, and the real-browser checks own that.
 */

/** Application-relative URLs in the backend's own shape, one per item used below. */
const ICON = {
  ironIngot: '/api/items/1101/icon/1111111111111111111111111111111111111111111111111111111111111111.png',
  tarnishedRing: '/api/items/2101/icon/2222222222222222222222222222222222222222222222222222222222222222.png',
  silverOre: '/api/items/55/icon/3333333333333333333333333333333333333333333333333333333333333333.png',
  mithrilOre: '/api/items/19700/icon/4444444444444444444444444444444444444444444444444444444444444444.jpg'
}

function withIcon(row: CraftingRow, iconUrl: string | null): CraftingRow {
  return { ...row, iconUrl }
}

function iconStates(wrapper: VueWrapper, scope = ''): string[] {
  return wrapper
    .findAll(`${scope} [data-test="item-icon"]`.trim())
    .map((icon) => icon.attributes('data-icon-state') ?? '')
}

function iconSources(wrapper: VueWrapper, scope = ''): (string | undefined)[] {
  return wrapper
    .findAll(`${scope} [data-test="item-icon-image"]`.trim())
    .map((image) => image.attributes('src'))
}

async function openScreenListingEveryRow(api: FakeCraftingApi): Promise<VueWrapper> {
  const wrapper = mount(CraftingProfitScreen, { props: { api } })
  await flushPromises()
  await wrapper.find('[data-test="filter-zero-craftable"]').setValue(false)
  await wrapper.find('[data-test="filter-not-allowed"]').setValue(false)
  await wrapper.find('[data-test="filter-non-positive-profit"]').setValue(false)
  return wrapper
}

describe('item icons in Crafting Profit', () => {
  it('showsEachRowsOwnOutputItemImageAndTheFallbackWhereTheBackendHadNone', async () => {
    const api = new FakeCraftingApi()
    const rows = [withIcon(profitableRow, ICON.ironIngot), withIcon(lossRow, null)]
    api.profitHandler = (request) => Promise.resolve(echoedProfitResponse(request, rows))

    const wrapper = await openScreenListingEveryRow(api)

    expect(iconStates(wrapper, '[data-test="profit-row"]')).toEqual(['image', 'no-url'])
    expect(iconSources(wrapper, '[data-test="profit-row"]')).toEqual([ICON.ironIngot])
    // The URL is the backend's, for the recipe's *output item* (1101) — not for the recipe id (11)
    // that identifies the row, and not assembled from anything else on it.
    expect(iconSources(wrapper, '[data-test="profit-row"]')[0]).toContain('/api/items/1101/')
    expect(wrapper.html()).not.toContain('/api/items/11/')
    expect(wrapper.html()).not.toContain('recipes/')
  })

  it('leavesRowSelectionAndItsAccessibleNameUntouched', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = (request) =>
      Promise.resolve(echoedProfitResponse(request, [withIcon(profitableRow, ICON.ironIngot)]))

    const wrapper = await openScreenListingEveryRow(api)
    const button = wrapper.find('[data-test="select-row"]')

    // The image contributes no text, so the control is still named by the recipe alone.
    expect(button.text()).toBe('Iron Ingotrecipe 11')
    await button.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="detail-name"]').text()).toBe('Iron Ingot')
  })

  it('requestsNothingFromTheBackendToRenderAnImage', async () => {
    const api = new FakeCraftingApi()
    api.profitHandler = (request) =>
      Promise.resolve(echoedProfitResponse(request, [withIcon(profitableRow, ICON.ironIngot)]))

    const wrapper = await openScreenListingEveryRow(api)
    const calculationsAfterOpening = api.profitRequests.length

    await wrapper.find('[data-test="select-row"]').trigger('click')
    await flushPromises()

    // Selecting a row asks for its resolution detail and nothing else: no second calculation, and
    // no metadata lookup of any kind for the images that appeared with it.
    expect(api.profitRequests).toHaveLength(calculationsAfterOpening)
    expect(api.resolutionRequests).toHaveLength(1)
    expect(JSON.stringify(api.resolutionRequests[0])).not.toContain('icon')
  })

  it('showsTheSelectedRecipesOutputImageBesideItsNameWithoutAnnouncingItTwice', () => {
    const detail = mount(SelectedResultDetail, {
      props: {
        row: withIcon(profitableRow, ICON.ironIngot),
        hiddenReason: null,
        settings: null,
        placeholder: 'Choose a recipe.',
        resolutionPhase: 'idle' as const,
        resolutionDetail: null,
        resolutionFailure: null,
        resolutionRecipeId: null
      }
    })

    expect(iconSources(detail, '.detail__heading')).toEqual([ICON.ironIngot])
    // The heading is still the name on its own — the image is decorative, so a screen reader hears
    // "Iron Ingot" once.
    expect(detail.find('[data-test="detail-name"]').text()).toBe('Iron Ingot')
    expect(detail.get('.detail__heading img').attributes('aria-hidden')).toBe('true')
  })

  it('showsEachMaterialsOwnImageInTheStillToBuyList', () => {
    const row: CraftingRow = {
      ...lossRow,
      iconUrl: ICON.tarnishedRing,
      missingToBuy: [
        { ...lossRow.missingToBuy![0]!, iconUrl: ICON.silverOre },
        // The second material has a name the backend did not supply and no icon either.
        { ...lossRow.missingToBuy![1]!, iconUrl: null }
      ],
      // Still supplied by the contract, and no longer displayed (DOMAIN_SPEC 2.1.1): an icon here
      // must not reach the screen at all.
      missingToBuyOne: [{ ...lossRow.missingToBuyOne![0]!, iconUrl: ICON.mithrilOre }]
    }

    const detail = mount(SelectedResultDetail, {
      props: {
        row,
        hiddenReason: null,
        settings: null,
        placeholder: 'Choose a recipe.',
        resolutionPhase: 'idle' as const,
        resolutionDetail: null,
        resolutionFailure: null,
        resolutionRecipeId: null
      }
    })

    expect(iconStates(detail, '[data-test="missing-item"]')).toEqual(['image', 'no-url'])
    expect(iconSources(detail, '[data-test="missing-item"]')).toEqual([ICON.silverOre])
    expect(detail.findAll('[data-test="missing-one-item"]')).toHaveLength(0)
    expect(iconSources(detail, '[data-test="selected-detail"]')).not.toContain(ICON.mithrilOre)
    // The quantity and the name are still the readable part of the entry.
    expect(detail.findAll('[data-test="missing-item"]')[0]?.text()).toContain('Silver Ore')
    expect(detail.findAll('[data-test="missing-item"]')[0]?.text()).toContain('×8')
  })

  it('givesEachTreeNodeItsOwnItemImageIncludingARepeatedItem', async () => {
    const api = new FakeCraftingApi()
    const rows = [withIcon(profitableRow, ICON.ironIngot)]
    api.profitHandler = (request) => Promise.resolve(echoedProfitResponse(request, rows))
    // The same item occurs in two branches. Each occurrence is its own node and carries the icon the
    // backend supplied for *that* node — the second one deliberately has none.
    const tree = node({
      itemId: 1101,
      itemName: 'Iron Ingot',
      iconUrl: ICON.ironIngot,
      children: [
        node({ itemId: 19_700, itemName: 'Mithril Ore', iconUrl: ICON.mithrilOre, children: [] }),
        node({
          itemId: 19_700,
          itemName: 'Mithril Ore',
          iconUrl: null,
          children: [node({ itemId: 55, itemName: 'Silver Ore', iconUrl: ICON.silverOre })]
        })
      ]
    })
    api.resolutionHandler = (request) => Promise.resolve(resolutionResponse(request, { tree }))

    const wrapper = await openScreenListingEveryRow(api)
    await wrapper.find('[data-test="select-row"]').trigger('click')
    await flushPromises()

    expect(
      wrapper.findAll('[data-test="tree-node"] [data-test="node-name"]').map((name) => name.text())
    ).toEqual(['Iron Ingot', 'Mithril Ore', 'Mithril Ore', 'Silver Ore'])
    expect(iconStates(wrapper, '[data-test="tree-node"] .node__identity')).toEqual([
      'image',
      'image',
      'no-url',
      'image'
    ])
    expect(iconSources(wrapper, '[data-test="tree-node"] .node__identity')).toEqual([
      ICON.ironIngot,
      ICON.mithrilOre,
      ICON.silverOre
    ])
  })
})
