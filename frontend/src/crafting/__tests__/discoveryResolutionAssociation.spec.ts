import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CraftingDiscoveryScreen from '../CraftingDiscoveryScreen.vue'
import type { ResolutionNode } from '@/api/types'
import { FakeCraftingApi, craftedTree, discoveryResolutionResponse, node, profitableRow } from './fixtures'

describe('Discovery resolution request association', () => {
  it('uses the selected character for both scope and inventory without a second input', async () => {
    const api = new FakeCraftingApi()
    const wrapper = mount(CraftingDiscoveryScreen, { props: { api } })
    await flushPromises()
    await wrapper.find('[data-test="discovery-select-row"]').trigger('click')
    await flushPromises()

    const request = api.discoveryResolutionRequests[0]
    expect(request?.calculation.scope.characterName).toBe('Nbt Anch')
    expect(request?.calculation).not.toHaveProperty('inventoryCharacterName')
    expect(request?.calculation.settings).toEqual({
      useOwnMats: true,
      allowBuying: true,
      listingSell: false,
      listingBuy: false
    })
    expect(wrapper.find('[data-test="discovery-inventory-selector"]').exists()).toBe(false)
  })

  it('does not request detail when no candidate is selected', async () => {
    const api = new FakeCraftingApi()
    mount(CraftingDiscoveryScreen, { props: { api } })
    await flushPromises()
    expect(api.discoveryResolutionRequests).toEqual([])
  })

  it('shows discovery knowledge badges for crafted recipes but not ordinary ingredients', async () => {
    const api = new FakeCraftingApi()
    api.discoveryResolutionHandler = (request) => Promise.resolve(discoveryResolutionResponse(request, {
      tree: {
        ...craftedTree,
        recipeId: request.recipeId,
        recipeKnowledge: 'TO_DISCOVER',
        children: [
          node({ itemId: 400, recipeId: null, recipeKnowledge: null }),
          node({ itemId: 500, recipeId: 88, recipeKnowledge: 'KNOWN', children: [
            node({ itemId: 600, recipeId: 99, recipeKnowledge: 'TO_DISCOVER' }),
            node({ itemId: 700, recipeId: null, recipeKnowledge: null })
          ] })
        ]
      } as unknown as ResolutionNode
    }))
    const wrapper = mount(CraftingDiscoveryScreen, { props: { api } })
    await flushPromises()
    await wrapper.find('[data-test="discovery-select-row"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-path="0"] [data-test="recipe-knowledge"]').text()).toBe('To discover')
    expect(wrapper.find('[data-path="0.1"] [data-test="recipe-knowledge"]').text()).toBe('Known')
    expect(wrapper.find('[data-path="0.1.0"] [data-test="recipe-knowledge"]').text()).toBe('To discover')
    expect(wrapper.find('[data-path="0.0"] [data-test="recipe-knowledge"]').exists()).toBe(false)
    expect(wrapper.find('[data-path="0.1.1"] [data-test="recipe-knowledge"]').exists()).toBe(false)
  })

  it('keeps a blocked recipe actionable in the selected result', async () => {
    const api = new FakeCraftingApi()
    api.discoveryHandler = (request) => Promise.resolve({
      scope: { discipline: request.scope.discipline, characterName: request.scope.characterName, rating: request.scope.rating },
      settings: { useOwnMats: true, allowBuying: false, listingSell: false, listingBuy: false, allowDailyCrafts: true },
      rowCount: 1,
      rows: [{ ...profitableRow, blockedReason: 'BUYING_DISABLED', craftableCount: 0 }]
    })
    const wrapper = mount(CraftingDiscoveryScreen, { props: { api } })
    await flushPromises()
    await wrapper.find('[data-test="discovery-select-row"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="discovery-detail-status"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="discovery-detail-state-code"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="discovery-detail-diagnostics"]').exists()).toBe(false)
  })
})
