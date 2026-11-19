import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { connection } from '@/api/accountSession'
import type { CraftingProfitRequest, CraftingProfitResponse, CraftingRow } from '@/api/types'
import type { SyncApi } from '@/api/syncApi'
import { resetMaterialProtection } from '@/materials/materialProtectionState'
import CraftingProfitScreen from '../CraftingProfitScreen.vue'
import { FORGE_PREFERENCE_KEY_PREFIX } from '../forgePreference'
import { DEFAULT_SETTINGS, FakeCraftingApi, echoedProfitResponse, profitableRow } from './fixtures'

const A = 'aaaaaaaa-0000-0000-0000-000000000001'
const B = 'bbbbbbbb-0000-0000-0000-000000000002'

const forgeRow: CraftingRow = {
  ...profitableRow, recipeId: -3105801, disciplines: '', outputName: 'Abyssal Scepter',
  expectedYield: { source: 'MYSTIC_FORGE', estimated: true, expectedOutputPerCraft: '2.5', minOutput: 1, maxOutput: 4,
    expectedSellableUnitsTotal: '5', guaranteedSellableUnitsTotal: 2, reusedSeedUnits: 0 }
}

/** The backend echo: Forge rows appear only while the request allows the Forge. */
function answer(request: CraftingProfitRequest): CraftingProfitResponse {
  const response = echoedProfitResponse(request)
  response.settings = { ...DEFAULT_SETTINGS, ...request.settings }
  response.rows = request.settings?.allowMysticForge === true ? [forgeRow, profitableRow] : [profitableRow]
  return response
}

function fake(): FakeCraftingApi {
  const api = new FakeCraftingApi()
  api.profitHandler = (request) => Promise.resolve(answer(request))
  return api
}

async function open(api: FakeCraftingApi): Promise<VueWrapper> {
  const wrapper = mount(CraftingProfitScreen, { props: { api, refreshApi: {} as SyncApi } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  resetMaterialProtection()
  connection.accountId = A
})
afterEach(() => {
  connection.accountId = null
  resetMaterialProtection()
  localStorage.clear()
})

describe('Mystic Forge toggle journey', () => {
  it('enablesForgeShowsSourceAndEstimateSelectsNegativeRowThenOffRemovesItAndReloadRestoresPerAccount', async () => {
    const api = fake()
    let wrapper = await open(api)
    expect(wrapper.find('[data-test="forge-source"]').exists()).toBe(false)
    expect(api.profitRequests.at(-1)?.settings?.allowMysticForge ?? false).toBe(false)

    await wrapper.find('[data-test="setting-allowMysticForge"]').setValue(true)
    await flushPromises()
    expect(api.profitRequests.at(-1)?.settings?.allowMysticForge).toBe(true)
    expect(wrapper.find('[data-test="forge-source"]').text()).toBe('Mystic Forge')
    expect(wrapper.find('[data-test="estimated-hint"]').exists()).toBe(true)

    await wrapper.findAll('[data-test="select-row"]')[0]?.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="detail-identity"]').text()).toBe('Mystic Forge')
    expect(wrapper.find('[data-test="detail-yield"]').text()).toContain('expected 2.5 per craft (min 1, max 4)')
    expect(localStorage.getItem(FORGE_PREFERENCE_KEY_PREFIX + A)).toContain('"allowMysticForge":true')

    // Same-account reload: the very first request already carries the saved flag.
    wrapper.unmount()
    const reloadApi = fake()
    wrapper = await open(reloadApi)
    expect(reloadApi.profitRequests[0]?.settings?.allowMysticForge).toBe(true)
    expect(wrapper.find('[data-test="forge-source"]').exists()).toBe(true)
    wrapper.unmount()

    // Another verified account does not inherit it.
    connection.accountId = B
    const otherApi = fake()
    wrapper = await open(otherApi)
    expect(otherApi.profitRequests[0]?.settings?.allowMysticForge ?? false).toBe(false)
    expect(wrapper.find('[data-test="forge-source"]').exists()).toBe(false)
    expect(localStorage.getItem(FORGE_PREFERENCE_KEY_PREFIX + B)).toBeNull()
    wrapper.unmount()

    // Toggling off for A removes Forge rows.
    connection.accountId = A
    const offApi = fake()
    wrapper = await open(offApi)
    await wrapper.find('[data-test="setting-allowMysticForge"]').setValue(false)
    await flushPromises()
    expect(offApi.profitRequests.at(-1)?.settings?.allowMysticForge).toBe(false)
    expect(wrapper.find('[data-test="forge-source"]').exists()).toBe(false)
    expect(localStorage.getItem(FORGE_PREFERENCE_KEY_PREFIX + A)).toContain('"allowMysticForge":false')
  })
})
