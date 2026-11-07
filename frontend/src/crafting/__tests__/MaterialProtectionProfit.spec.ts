import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { connection } from '@/api/accountSession'
import type { CraftingProfitRequest, CraftingProfitResponse } from '@/api/types'
import type { SyncApi } from '@/api/syncApi'
import {
  PREFERENCE_KEY_PREFIX,
  createMaterialProtectionState,
  resetMaterialProtection,
  useMaterialProtection
} from '@/materials/materialProtectionState'
import { calculationKey } from '../useProfitResolution'
import CraftingProfitScreen from '../CraftingProfitScreen.vue'
import { DEFAULT_SETTINGS, FakeCraftingApi, deferred, echoedProfitResponse, profitableRow } from './fixtures'

const A = 'aaaaaaaa-0000-0000-0000-000000000001'
const B = 'bbbbbbbb-0000-0000-0000-000000000002'

const syncApi = {} as SyncApi

/** Echoes request settings merged over the defaults, like the backend's canonical echo. */
function echo(request: CraftingProfitRequest): CraftingProfitResponse {
  const response = echoedProfitResponse(request)
  const ids = [...new Set(request.settings?.protectedItemIds ?? [])].sort((a, b) => a - b)
  response.settings = { ...DEFAULT_SETTINGS, ...request.settings }
  if (request.settings?.protectedItemIds !== undefined) response.settings.protectedItemIds = ids
  return response
}

function api(): FakeCraftingApi {
  const fake = new FakeCraftingApi()
  fake.profitHandler = (request) => Promise.resolve(echo(request))
  return fake
}

async function open(fake: FakeCraftingApi): Promise<VueWrapper> {
  const wrapper = mount(CraftingProfitScreen, { props: { api: fake, refreshApi: syncApi } })
  await flushPromises()
  return wrapper
}

const toggle = (w: VueWrapper) => w.find('[data-test="protection-toggle"]')

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

function commit(ids: number[]): void {
  const state = useMaterialProtection()
  state.setPending(ids)
  state.commit()
}

describe('Crafting Profit material protection', () => {
  it('warns on activation with zero committed IDs and sends nothing enabled', async () => {
    const fake = api()
    const w = await open(fake)
    expect(w.find('[data-test="protection-count"]').text()).toContain('0 items')
    expect(w.find('[data-test="protection-links"]').exists()).toBe(false)
    const before = fake.profitRequests.length
    await toggle(w).setValue(true)
    await flushPromises()
    expect(w.find('[data-test="protection-empty-warning"]').attributes('role')).toBe('alert')
    expect(useMaterialProtection().state.activation).toBe(false)
    expect((toggle(w).element as HTMLInputElement).checked).toBe(false)
    expect(fake.profitRequests).toHaveLength(before)
    expect(fake.profitRequests.some((r) => r.settings?.materialProtectionEnabled === true)).toBe(false)
  })

  it('sends canonical committed IDs with activation, independent of allowBuying, and links to Bank/Materials', async () => {
    commit([9, 3, 3])
    const fake = api()
    const w = await open(fake)
    expect(w.find('[data-test="protection-count"]').text()).toContain('2 items')
    expect(w.find('[data-test="protection-bank-link"]').attributes('href')).toBe('#/bank')
    expect(w.find('[data-test="protection-materials-link"]').attributes('href')).toBe('#/materials')
    await toggle(w).setValue(true)
    await flushPromises()
    const last = fake.profitRequests.at(-1)!
    expect(last.settings?.materialProtectionEnabled).toBe(true)
    expect(last.settings?.protectedItemIds).toEqual([3, 9])
    expect(last.settings?.allowBuying).toBe(DEFAULT_SETTINGS.allowBuying)
    expect(JSON.parse(localStorage.getItem(PREFIX(A))!).enabled).toBe(true)
    await toggle(w).setValue(false)
    await flushPromises()
    expect(fake.profitRequests.at(-1)!.settings).toMatchObject({ materialProtectionEnabled: false, protectedItemIds: [3, 9] })
  })

  it('pending edits change nothing; committing while enabled recalculates and invalidates detail', async () => {
    commit([3])
    useMaterialProtection().setActivation(true)
    const fake = api()
    const w = await open(fake)
    await w.find('[data-test="search"]').setValue('')
    await w.find('[data-test="max-displayed"]').setValue(7)
    await w.find('[data-test="max-displayed"]').trigger('change')
    const count = fake.profitRequests.length
    expect(fake.profitRequests[0]!.settings).toEqual({ materialProtectionEnabled: true, protectedItemIds: [3] })

    useMaterialProtection().setPending([3, 4, 5])
    await flushPromises()
    expect(fake.profitRequests).toHaveLength(count)
    expect(w.find('[data-test="protection-count"]').text()).toContain('1 item ')

    await w.findAll('tbody tr')[0]!.trigger('click')
    await flushPromises()
    const detailRequests = fake.resolutionRequests.length
    expect(detailRequests).toBeGreaterThan(0)
    expect(fake.resolutionRequests.at(-1)!.calculation.settings?.protectedItemIds).toEqual([3])

    useMaterialProtection().commit()
    await flushPromises()
    expect(fake.profitRequests).toHaveLength(count + 1)
    expect(fake.profitRequests.at(-1)!.settings?.protectedItemIds).toEqual([3, 4, 5])
    expect((w.find('[data-test="max-displayed"]').element as HTMLInputElement).value).toBe('7')
    // Detail was re-requested for the new echoed settings, identical to the list echo.
    expect(fake.resolutionRequests.at(-1)!.calculation.settings?.protectedItemIds).toEqual([3, 4, 5])
    expect(fake.resolutionRequests.at(-1)!.calculation.settings?.materialProtectionEnabled).toBe(true)
  })

  it('discards a delayed old list response after an activation change', async () => {
    commit([3])
    const fake = api()
    const w = await open(fake)
    const slow = deferred<CraftingProfitResponse>()
    const original = fake.profitHandler
    fake.profitHandler = (request, index) => (index === fake.profitRequests.length - 1 && request.settings?.materialProtectionEnabled === true ? slow.promise : original(request, index))
    await toggle(w).setValue(true)
    fake.profitHandler = original
    await toggle(w).setValue(false)
    await flushPromises()
    slow.resolve(echo({ settings: { ...DEFAULT_SETTINGS, materialProtectionEnabled: true, protectedItemIds: [3] } }))
    await flushPromises()
    expect(useMaterialProtection().state.effective?.enabled).toBe(false)
    expect(fake.profitRequests.at(-1)!.settings?.materialProtectionEnabled).toBe(false)
  })

  it('discards a delayed response after an account switch', async () => {
    commit([3])
    useMaterialProtection().setActivation(true)
    const fake = api()
    const slow = deferred<CraftingProfitResponse>()
    fake.profitHandler = (request, index) => (index === 0 ? slow.promise : Promise.resolve(echo(request)))
    const w = mount(CraftingProfitScreen, { props: { api: fake, refreshApi: syncApi } })
    await flushPromises()
    connection.accountId = B
    await flushPromises()
    slow.resolve(echo({ settings: { ...DEFAULT_SETTINGS, materialProtectionEnabled: true, protectedItemIds: [3] } }))
    await flushPromises()
    expect(fake.profitRequests.at(-1)!.settings?.materialProtectionEnabled).not.toBe(true)
    expect(w.find('[data-test="protection-count"]').text()).toContain('0 items')
    expect(useMaterialProtection().state.effective?.enabled ?? false).toBe(false)
    expect(w.find('[data-test="summary"]').exists()).toBe(true)
  })

  it('shows an unconfirmed error when the backend omits the protection echo', async () => {
    commit([3])
    useMaterialProtection().setActivation(true)
    const fake = api()
    fake.profitHandler = (request) => {
      const response = echoedProfitResponse(request, [profitableRow])
      response.settings = { ...DEFAULT_SETTINGS }
      return Promise.resolve(response)
    }
    const w = await open(fake)
    expect(w.find('[data-test="request-error"]').text()).toContain('did not confirm')
    expect(w.findAll('tbody tr')).toHaveLength(0)
  })
})

describe('protection context comparison', () => {
  it('ignores ID order and duplicates, notices real changes', () => {
    const base = { ...DEFAULT_SETTINGS, materialProtectionEnabled: true }
    const key = (ids: number[], enabled = true) =>
      calculationKey({ scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 }, settings: { ...base, materialProtectionEnabled: enabled, protectedItemIds: ids } })
    expect(key([5, 3])).toBe(key([3, 5, 3]))
    expect(key([3, 5])).not.toBe(key([3, 6]))
    expect(key([3, 5])).not.toBe(key([3, 5], false))
  })
})

describe('storage recovery', () => {
  it('clears an earlier write failure after a later successful write', () => {
    let fail = true
    const m = createMaterialProtectionState({
      storage: { getItem: () => null, setItem: () => { if (fail) throw new Error('x') }, removeItem: () => undefined },
      accountId: () => A
    })
    m.setPending([1])
    m.commit()
    expect(m.state.diagnostic).toBe('write-failed')
    fail = false
    m.setActivation(true)
    expect(m.state.diagnostic).toBeNull()
  })
})

function PREFIX(id: string): string {
  return PREFERENCE_KEY_PREFIX + id
}
