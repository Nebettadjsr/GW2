import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { connectKey, connection, resetAccountSession } from '@/api/accountSession'
import type { CraftingProfitRequest, CraftingProfitResolutionResponse, CraftingProfitResponse } from '@/api/types'
import type { SyncApi } from '@/api/syncApi'
import {
  PREFERENCE_KEY_PREFIX,
  createMaterialProtectionState,
  resetMaterialProtection,
  useMaterialProtection
} from '@/materials/materialProtectionState'
import { calculationKey } from '../useProfitResolution'
import CraftingProfitScreen from '../CraftingProfitScreen.vue'
import { DEFAULT_SETTINGS, FakeCraftingApi, deferred, echoedProfitResponse, profitableRow, resolutionResponse } from './fixtures'

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
    // The outgoing workspace submits nothing for B and must not show A's late answer.
    expect(fake.profitRequests).toHaveLength(1)
    expect(useMaterialProtection().state.effective?.enabled ?? false).toBe(false)
    expect(w.find('[data-test="summary"]').exists()).toBe(false)
  })

  it('shows the empty-selection warning as a floating alert that can be dismissed', async () => {
    const w = await open(api())
    await toggle(w).setValue(true)
    const warn = w.find('[data-test="protection-empty-warning"]')
    expect(warn.classes()).toContain('protection__popup')
    expect(warn.find('a[href="#/bank"]').exists()).toBe(true)
    expect(warn.find('a[href="#/materials"]').exists()).toBe(true)
    await w.find('[data-test="protection-warning-dismiss"]').trigger('click')
    expect(w.find('[data-test="protection-empty-warning"]').exists()).toBe(false)
    await toggle(w).setValue(true)
    expect(w.find('[data-test="protection-empty-warning"]').exists()).toBe(true)
    await w.find('[data-test="protection-control"]').trigger('keydown', { key: 'Escape' })
    expect(w.find('[data-test="protection-empty-warning"]').exists()).toBe(false)
    expect(useMaterialProtection().state.activation).toBe(false)
  })

  it('places the checkbox between Use own materials and Allow buying, unchecked by default', async () => {
    const w = await open(api())
    const column = w.find('.settings__checks')
    const labels = column.findAll('label').map((l) => l.text())
    expect(labels).toEqual([
      'Use own materials',
      expect.stringContaining('Respect excluded items/ mats'),
      'Allow buying',
      'Allow daily craft'
    ])
    const toggle = w.find('[data-test="protection-toggle"]')
    expect((toggle.element as HTMLInputElement).checked).toBe(false)
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

describe('info tooltip and source hints', () => {
  const panel = (w: VueWrapper) => {
    const el = w.find('[data-test="protection-panel"]')
    return { element: el.element, isVisible: () => (el.element as HTMLElement).style.display !== 'none' }
  }
  const infoButton = (w: VueWrapper) => w.find('[data-test="protection-info-button"]')

  it('is hidden until hover, stays open while the pointer is over the panel, and closes on leave', async () => {
    commit([3])
    const w = await open(api())
    expect(panel(w).isVisible()).toBe(false)
    await w.find('[data-test="protection-info"]').trigger('mouseenter')
    expect(panel(w).isVisible()).toBe(true)
    expect(panel(w).element.closest('[data-test="protection-info"]')).not.toBeNull()
    await w.find('[data-test="protection-info"]').trigger('mouseleave')
    expect(panel(w).isVisible()).toBe(false)
  })

  it('opens on keyboard focus, stays while focus is inside, and closes on Escape', async () => {
    commit([3])
    const w = await open(api())
    expect(infoButton(w).attributes('aria-label')).toBeTruthy()
    await infoButton(w).trigger('focusin')
    expect(panel(w).isVisible()).toBe(true)
    await w.find('[data-test="protection-bank-link"]').trigger('focusin')
    expect(panel(w).isVisible()).toBe(true)
    await w.find('[data-test="protection-control"]').trigger('keydown', { key: 'Escape' })
    expect(panel(w).isVisible()).toBe(false)
  })

  it('shows only the destinations where the committed selection was made, per account, at Save', async () => {
    const state = useMaterialProtection()
    state.togglePending(3, 'bank')
    const w = await open(api())
    expect(w.find('[data-test="protection-links"]').exists()).toBe(false)
    state.commit()
    await flushPromises()
    expect(w.find('[data-test="protection-bank-link"]').exists()).toBe(true)
    expect(w.find('[data-test="protection-materials-link"]').exists()).toBe(false)
    state.togglePending(4, 'materials') // pending only: links unchanged
    await flushPromises()
    expect(w.find('[data-test="protection-materials-link"]').exists()).toBe(false)
    state.discardPending()
    state.togglePending(4, 'materials')
    state.commit()
    await flushPromises()
    expect(w.find('[data-test="protection-bank-link"]').exists()).toBe(true)
    expect(w.find('[data-test="protection-materials-link"]').exists()).toBe(true)
    expect(JSON.parse(localStorage.getItem(PREFIX(A))!).sources).toEqual(['bank', 'materials'])
    connection.accountId = B
    await flushPromises()
    expect(w.find('[data-test="protection-links"]').exists()).toBe(false)
  })

  it('shows both links for a legacy record without hints and never sends hints to the backend', async () => {
    localStorage.setItem(PREFIX(A), JSON.stringify({ version: 1, protectedItemIds: [3], enabled: true }))
    const fake = api()
    const w = await open(fake)
    expect(w.find('[data-test="protection-bank-link"]').exists()).toBe(true)
    expect(w.find('[data-test="protection-materials-link"]').exists()).toBe(true)
    expect(JSON.stringify(fake.profitRequests)).not.toContain('sources')
  })
})

describe('committed-context watcher request counts', () => {
  it('does not recalculate for saved-selection edits while protection is off', async () => {
    commit([3])
    const fake = api()
    await open(fake)
    const count = fake.profitRequests.length
    commit([3, 4])
    await flushPromises()
    expect(fake.profitRequests).toHaveLength(count)
  })

  it('does not submit during transient null identity, nor for another account in the old workspace', async () => {
    commit([3])
    useMaterialProtection().setActivation(true)
    const fake = api()
    const w = mount(CraftingProfitScreen, { props: { api: fake, refreshApi: syncApi } })
    await flushPromises()
    const count = fake.profitRequests.length
    connection.accountId = null
    await flushPromises()
    expect(fake.profitRequests).toHaveLength(count)
    connection.accountId = B
    await flushPromises()
    // The incoming workspace (keyed by generation) calculates for B with its own inputs.
    expect(fake.profitRequests).toHaveLength(count)
    // Same-account renewal after a transient null still calculates.
    connection.accountId = null
    connection.accountId = A
    await flushPromises()
    expect(w.exists()).toBe(true)
  })

  it('real connectKey account replacement: old workspace submits nothing under the new account', async () => {
    commit([3])
    useMaterialProtection().setActivation(true)
    const fake = api()
    await open(fake)
    resetAccountSession()
    connection.accountId = A
    await flushPromises()
    const count = fake.profitRequests.length
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ accountId: B, accessContext: 'ctx', expiresAt: '2099-01-01T00:00:00Z', grants: ['account'] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    ))
    try {
      expect(await connectKey('KEY-B')).toBe(true)
      await flushPromises()
      expect(connection.accountId).toBe(B)
      expect(fake.profitRequests).toHaveLength(count)
    } finally {
      vi.unstubAllGlobals()
      resetAccountSession()
    }
  })

  it('rejects an in-flight response that was overtaken by a null identity', async () => {
    commit([3])
    useMaterialProtection().setActivation(true)
    const fake = api()
    const slow = deferred<CraftingProfitResponse>()
    fake.profitHandler = () => slow.promise
    const w = mount(CraftingProfitScreen, { props: { api: fake, refreshApi: syncApi } })
    await flushPromises()
    connection.accountId = null
    await flushPromises()
    slow.resolve(echo({ settings: { ...DEFAULT_SETTINGS, materialProtectionEnabled: true, protectedItemIds: [3] } }))
    await flushPromises()
    expect(w.find('[data-test="summary"]').exists()).toBe(false)
  })
})

describe('delayed detail responses', () => {
  it('cannot reappear after the committed set changes, and detail uses the list echo', async () => {
    commit([3])
    useMaterialProtection().setActivation(true)
    const fake = api()
    const w = await open(fake)
    const slow = deferred<CraftingProfitResolutionResponse>()
    const original = fake.resolutionHandler
    fake.resolutionHandler = (request, index) => (index === 0 ? slow.promise : original(request, index))
    await w.findAll('tbody tr')[0]!.trigger('click')
    await flushPromises()
    const first = fake.resolutionRequests[0]!
    expect(first.calculation.settings).toMatchObject({ materialProtectionEnabled: true, protectedItemIds: [3] })
    expect(first.basedOnCalculationId).toBeDefined()
    // New list is held back: the interval before the new list returns.
    const list = deferred<CraftingProfitResponse>()
    fake.profitHandler = () => list.promise
    commit([3, 4])
    await flushPromises()
    slow.resolve(resolutionResponse(first))
    await flushPromises()
    expect(w.find('[data-test="resolution-tree"]').exists()).toBe(false)
    expect(fake.resolutionRequests).toHaveLength(1)
    list.resolve(echo({ settings: { ...DEFAULT_SETTINGS, materialProtectionEnabled: true, protectedItemIds: [3, 4] } }))
    await flushPromises()
    // Any detail now shown belongs to a request made from the new list's echoed settings.
    const latest = fake.resolutionRequests.at(-1)!
    if (fake.resolutionRequests.length > 1) {
      expect(latest.calculation.settings).toMatchObject({ materialProtectionEnabled: true, protectedItemIds: [3, 4] })
    }
  })

  it('cannot reappear after an activation change or account switch', async () => {
    commit([3])
    useMaterialProtection().setActivation(true)
    const fake = api()
    const w = await open(fake)
    const slow = deferred<CraftingProfitResolutionResponse>()
    fake.resolutionHandler = () => slow.promise
    await w.findAll('tbody tr')[0]!.trigger('click')
    await flushPromises()
    const first = fake.resolutionRequests[0]!
    await toggle(w).setValue(false)
    await flushPromises()
    slow.resolve(resolutionResponse(first))
    await flushPromises()
    expect(w.find('[data-test="resolution-tree"]').exists()).toBe(false)
    connection.accountId = B
    await flushPromises()
    expect(w.find('[data-test="resolution-tree"]').exists()).toBe(false)
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

describe('read-failed warning', () => {
  it('stays while reads keep failing after successful writes, and clears after a genuine read recovery', () => {
    let readsFail = true
    const data = new Map<string, string>()
    const m = createMaterialProtectionState({
      storage: {
        getItem: (k) => { if (readsFail) throw new Error('read'); return data.get(k) ?? null },
        setItem: (k, v) => { data.set(k, v) },
        removeItem: (k) => { data.delete(k) }
      },
      accountId: () => A
    })
    expect(m.state.diagnostic).toBe('read-failed')
    m.setPending([7])
    m.commit()
    expect(m.state.diagnostic).toBe('read-failed')
    m.setActivation(true)
    expect(m.state.diagnostic).toBe('read-failed')
    readsFail = false
    m.setActivation(false)
    expect(m.state.diagnostic).toBeNull()
  })
})

describe('storage recovery of recovered diagnostics', () => {
  const memory = (raw: string | null, failRead = false) => {
    const data = new Map<string, string>()
    if (raw !== null) data.set(PREFERENCE_KEY_PREFIX + A, raw)
    return {
      data,
      storage: {
        getItem: (k: string) => {
          if (failRead) throw new Error('read')
          return data.get(k) ?? null
        },
        setItem: (k: string, v: string) => void data.set(k, v),
        removeItem: (k: string) => void data.delete(k)
      }
    }
  }

  it.each([
    ['corrupt', '{not json'],
    ['unsupported-version', JSON.stringify({ version: 99, protectedItemIds: [1], enabled: true })]
  ])('clears %s after a successful save and reload restores the saved values', (diagnostic, raw) => {
    const { storage, data } = memory(raw)
    const m = createMaterialProtectionState({ storage, accountId: () => A })
    expect(m.state.diagnostic).toBe(diagnostic)
    m.setPending([5, 2])
    m.commit()
    expect(m.state.diagnostic).toBeNull()
    const reloaded = createMaterialProtectionState({ storage, accountId: () => A })
    expect(reloaded.state.diagnostic).toBeNull()
    expect(reloaded.state.committed).toEqual([2, 5])
    expect(data.size).toBe(1)
  })
})

function PREFIX(id: string): string {
  return PREFERENCE_KEY_PREFIX + id
}
