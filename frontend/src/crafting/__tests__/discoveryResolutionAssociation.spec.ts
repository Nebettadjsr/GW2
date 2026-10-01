import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CraftingDiscoveryScreen from '../CraftingDiscoveryScreen.vue'
import { FakeCraftingApi, profitableRow } from './fixtures'

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
    expect(wrapper.find('[data-test="discovery-detail-status-explanation"]').text()).toContain('buying is switched off')
  })
})
