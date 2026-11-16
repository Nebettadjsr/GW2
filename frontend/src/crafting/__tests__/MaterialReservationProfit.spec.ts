import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import BankScreen from '@/account/BankScreen.vue'
import { FakeAccountApi } from '@/account/__tests__/accountFixtures'
import { allocationRows, fourStackBank, type BankSettings } from '@/account/__tests__/bankReservationFixtures'
import { shellProvide } from '@/sync/__tests__/shellProvide'
import { connection } from '@/api/accountSession'
import type { SyncApi } from '@/api/syncApi'
import { resetMaterialProtection, useMaterialProtection } from '@/materials/materialProtectionState'
import CraftingProfitScreen from '../CraftingProfitScreen.vue'
import { calculationKey } from '../useProfitResolution'
import { DEFAULT_SETTINGS, FakeCraftingApi, echoedProfitResponse, node, profitableRow, resolutionResponse } from './fixtures'

const ITEM = 19697
const mounted: VueWrapper[] = []
beforeEach(() => {
  localStorage.clear()
  resetMaterialProtection()
  connection.accountId = 'aaaaaaaa-0000-0000-0000-000000000235'
})
afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  connection.accountId = null
  resetMaterialProtection()
  localStorage.clear()
})
async function open(api = new FakeCraftingApi()) {
  const wrapper = mount(CraftingProfitScreen, { props: { api, refreshApi: {} as SyncApi } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, api }
}

describe('Profit finite reservation contract', () => {
  it('sends source-specific quantities and ALL IDs, never the legacy global list, and reuses the table echo for detail', async () => {
    const state = useMaterialProtection()
    state.reserveQuantity(ITEM, 'bank', 250)
    state.reserveQuantity(ITEM, 'materials', 30)
    state.togglePending(19721, 'bank')
    state.setActivation(true)
    const { wrapper, api } = await open()
    expect(api.profitRequests[0]!.settings).toEqual({
      materialProtectionEnabled: true, protectedItemIds: [],
      protectedBankItemIds: [19721], protectedMaterialItemIds: [],
      protectedBankQuantities: { [ITEM]: 250 }, protectedMaterialQuantities: { [ITEM]: 30 }, protectedBankAllocations: []
    })
    await wrapper.get('[data-test="select-row"]').trigger('click')
    await flushPromises()
    expect(api.resolutionRequests).toHaveLength(1)
    expect(api.resolutionRequests[0]!.calculation.settings)
      .toEqual(echoedProfitResponse(api.profitRequests[0]!).settings)
    expect(api.resolutionRequests[0]!.calculation.settings?.protectedItemIds).toEqual([])
  })

  it.each(['finite in both stores', 'ALL in one and finite in the other'])(
    'counts distinct item types for %s', async mode => {
      const state = useMaterialProtection()
      state.reserveQuantity(ITEM, 'materials', 250)
      if (mode === 'finite in both stores') state.reserveQuantity(ITEM, 'bank', 250)
      else state.togglePending(ITEM, 'bank')
      const { wrapper } = await open()
      expect(wrapper.get('[data-test="protection-count"]').text()).toContain('1 item ')
    }
  )

  it.each(['omitted', 'changed'])('does not claim protection when the finite echo is %s', async kind => {
    const state = useMaterialProtection()
    state.reserveQuantity(ITEM, 'bank', 250)
    state.setActivation(true)
    const api = new FakeCraftingApi()
    api.profitHandler = async request => {
      const response = echoedProfitResponse(request)
      if (kind === 'omitted') delete response.settings.protectedBankQuantities
      else response.settings.protectedBankQuantities = { [ITEM]: 249 }
      return response
    }
    const { wrapper } = await open(api)
    expect(wrapper.get('[data-test="request-error"]').text()).toContain('did not confirm')
    expect(wrapper.findAll('[data-test="profit-row"]')).toHaveLength(0)
    expect(api.resolutionRequests).toHaveLength(0)
  })

  it.each(['protectedBankQuantities', 'protectedMaterialQuantities'] as const)(
    'rejects an unexpected nonempty %s echo when the requested map was empty', async field => {
      const state = useMaterialProtection()
      state.togglePending(19721, 'bank')
      state.setActivation(true)
      const api = new FakeCraftingApi()
      api.profitHandler = async request => {
        const response = echoedProfitResponse(request)
        response.settings[field] = { [ITEM]: 250 }
        return response
      }
      const { wrapper } = await open(api)
      expect(wrapper.get('[data-test="request-error"]').text()).toContain('did not confirm')
      expect(wrapper.findAll('[data-test="profit-row"]')).toHaveLength(0)
    }
  )

  it('rejects detail whose finite map differs from the selected table calculation', async () => {
    const state = useMaterialProtection()
    state.reserveQuantity(ITEM, 'bank', 250)
    state.setActivation(true)
    const api = new FakeCraftingApi()
    api.resolutionHandler = async request => {
      const response = resolutionResponse(request)
      response.calculation.settings.protectedBankQuantities = { [ITEM]: 249 }
      return response
    }
    const { wrapper } = await open(api)
    await wrapper.get('[data-test="select-row"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('different calculation inputs')
  })

  it('distinguishes quantity, source, and ALL mode in calculation association', () => {
    const inputs = { scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 },
      settings: { ...DEFAULT_SETTINGS, materialProtectionEnabled: true, protectedItemIds: [],
        protectedBankItemIds: [], protectedMaterialItemIds: [], protectedBankQuantities: { [ITEM]: 250 }, protectedMaterialQuantities: {} } }
    const key = calculationKey(inputs)
    expect(calculationKey({ ...inputs, settings: { ...inputs.settings, protectedBankQuantities: { [ITEM]: 249 } } })).not.toBe(key)
    expect(calculationKey({ ...inputs, settings: { ...inputs.settings, protectedBankQuantities: {}, protectedMaterialQuantities: { [ITEM]: 250 } } })).not.toBe(key)
    expect(calculationKey({ ...inputs, settings: { ...inputs.settings, protectedBankQuantities: {}, protectedBankItemIds: [ITEM] } })).not.toBe(key)
  })

  it('shows the configured target beside the relevant selected requirement without changing supplied stock usage', async () => {
    const state = useMaterialProtection()
    state.reserveQuantity(ITEM, 'materials', 250)
    state.setActivation(true)
    const api = new FakeCraftingApi()
    const row = { ...profitableRow, craftableCount: 50 }
    api.profitHandler = async request => echoedProfitResponse(request, [row])
    api.resolutionHandler = async request => resolutionResponse(request, { row, tree: node({
      itemId: 1101, itemName: 'Iron Ingot', requestedQuantity: 50, craftedQuantity: 50,
      recipeId: 11, craftCount: 50, producedQuantity: 50,
      children: [node({ itemId: ITEM, itemName: 'Copper Ore', requestedQuantity: 50, inventoryQuantity: 50,
        craftedQuantity: 0, methods: ['INVENTORY'] })]
    }) })
    const { wrapper } = await open(api)
    await wrapper.get('[data-test="select-row"]').trigger('click')
    await flushPromises()
    const ingredient = wrapper.get('[data-path="0.0"]')
    expect(ingredient.text()).toContain('Keep 250 in material storage')
    expect(ingredient.get('[data-test="node-methods"]').text()).toContain('From stock ×50')
  })
})

async function manualThirdStack() {
  const api = new FakeAccountApi()
  api.bankHandler = async () => fourStackBank()
  const bank = mount(BankScreen, { props: { api }, global: { provide: shellProvide() } })
  mounted.push(bank)
  await flushPromises()
  ;(bank.get('[data-slot="2"] [data-test="protect-checkbox"]').element as HTMLInputElement).click()
  await flushPromises()
  bank.unmount()
  useMaterialProtection().setActivation(true)
}

describe('Bank allocation settings association', () => {
  it('sends the complete slot intent, accepts canonical order, and carries it unchanged into detail', async () => {
    await manualThirdStack()
    const { wrapper, api } = await open()
    expect(api.profitRequests[0]!.settings).toMatchObject({ protectedItemIds: [], protectedBankItemIds: [],
      protectedBankQuantities: { [ITEM]: 250 }, protectedBankAllocations: allocationRows([0, 0, 250, 0]) })
    await wrapper.get('[data-test="select-row"]').trigger('click')
    await flushPromises()
    expect((api.resolutionRequests[0]!.calculation.settings as BankSettings).protectedBankAllocations)
      .toEqual(allocationRows([0, 0, 250, 0]))
  })

  it.each(['omitted', 'units', 'owner', 'extra guard'])('rejects a table echo with %s allocation intent', async mismatch => {
    await manualThirdStack()
    const api = new FakeCraftingApi()
    api.profitHandler = async request => {
      const response = echoedProfitResponse(request)
      const settings = response.settings as BankSettings
      const allocations = allocationRows([0, 0, 250, 0])
      settings.protectedBankAllocations = allocations
      if (mismatch === 'omitted') delete settings.protectedBankAllocations
      if (mismatch === 'units') allocations[2]!.reservedUnits = 249
      if (mismatch === 'owner') allocations[0]!.boundTo = 'Other Character'
      if (mismatch === 'extra guard') allocations.push({ ...allocations[0]!, slot: 4 })
      return response
    }
    const { wrapper } = await open(api)
    expect(wrapper.get('[data-test="request-error"]').text()).toContain('did not confirm')
    expect(wrapper.findAll('[data-test="profit-row"]')).toHaveLength(0)
    expect(api.resolutionRequests).toHaveLength(0)
  })

  it('rejects an unexpected nonempty allocation echo for numeric-only intent', async () => {
    useMaterialProtection().reserveQuantity(ITEM, 'bank', 250)
    useMaterialProtection().setActivation(true)
    const api = new FakeCraftingApi()
    api.profitHandler = async request => {
      const response = echoedProfitResponse(request)
      ;(response.settings as BankSettings).protectedBankAllocations = allocationRows([0, 0, 250, 0])
      return response
    }
    const { wrapper } = await open(api)
    expect(wrapper.get('[data-test="request-error"]').text()).toContain('did not confirm')
    expect(wrapper.findAll('[data-test="profit-row"]')).toHaveLength(0)
  })

  it('rejects detail whose slots differ despite an identical item and fixed total', async () => {
    await manualThirdStack()
    const api = new FakeCraftingApi()
    api.resolutionHandler = async request => {
      const response = resolutionResponse(request)
      ;(response.calculation.settings as BankSettings).protectedBankAllocations = allocationRows([250, 0, 0, 0])
      return response
    }
    const { wrapper } = await open(api)
    await wrapper.get('[data-test="select-row"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="resolution-tree"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('different calculation inputs')
  })

  it.each(['slot', 'itemId', 'reservedUnits', 'observedCount', 'binding', 'boundTo'] as const)(
    'includes every allocation %s guard in calculation identity while canonicalizing row order', field => {
      const allocations = allocationRows([0, 0, 250, 0])
      const settings: BankSettings = { ...DEFAULT_SETTINGS, materialProtectionEnabled: true,
        protectedItemIds: [], protectedBankItemIds: [], protectedMaterialItemIds: [],
        protectedBankQuantities: { [ITEM]: 250 }, protectedMaterialQuantities: {}, protectedBankAllocations: allocations }
      const inputs = { scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 }, settings }
      const key = calculationKey(inputs)
      const reordered: BankSettings = { ...settings, protectedBankAllocations: [...allocations].reverse() }
      expect(calculationKey({ ...inputs, settings: reordered })).toBe(key)
      const changed = structuredClone(allocations)
      if (field === 'binding' || field === 'boundTo') changed[0]![field] = 'Raw guard string'
      else changed[0]![field] += 1
      const different: BankSettings = { ...settings, protectedBankAllocations: changed }
      expect(calculationKey({ ...inputs, settings: different })).not.toBe(key)
    }
  )
})
