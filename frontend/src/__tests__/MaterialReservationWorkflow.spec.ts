import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { connectKey, installAccountSession, resetAccountSession } from '@/api/accountSession'
import { installAccountTransport } from '@/api/http'
import type { CraftingProfitRequest, CraftingProfitResolutionRequest, CraftingProfitResponse, SyncTaskStatus } from '@/api/types'
import { deferred } from '@/account/__tests__/accountFixtures'
import {
  accountWork, echoedProfitResponse, finalFreshness, node, profitableRow,
  provisionalFreshness, resolutionResponse, selectorOptions
} from '@/crafting/__tests__/fixtures'
import { pendingProfitSearch } from '@/materials/profitSearchIntent'
import { resetMaterialProtection, useMaterialProtection } from '@/materials/materialProtectionState'

const ACCOUNT = 'aaaaaaaa-0000-0000-0000-000000000235'
const ITEM = 19697
const TABLE = '/api/crafting/profit'
const DETAIL = '/api/crafting/profit/resolution'
const TASK = '/api/sync/tasks/reservation-existing-task'
const seen: Array<{ path: string; method: string; body: unknown }> = []
let wrapper: VueWrapper | null = null
let profitAnswer: (request: CraftingProfitRequest, index: number) => Promise<CraftingProfitResponse>
let detailAnswer: (request: CraftingProfitResolutionRequest) => Promise<ReturnType<typeof resolutionResponse>>
let taskAnswer: () => Promise<SyncTaskStatus>

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
const calls = (path: string) => seen.filter(request => request.path === path)
const lastRequest = () => calls(TABLE).at(-1)!.body as CraftingProfitRequest

beforeAll(async () => { await import('../App.vue') }, 30_000)
beforeEach(async () => {
  localStorage.clear()
  sessionStorage.clear()
  pendingProfitSearch.value = null
  resetMaterialProtection()
  resetAccountSession()
  installAccountSession()
  profitAnswer = async request => echoedProfitResponse(request)
  detailAnswer = async request => resolutionResponse(request)
  taskAnswer = async () => ({ taskId: 'reservation-existing-task', operation: 'ACCOUNT_SYNC', state: 'SUCCEEDED',
    submittedAt: '', startedAt: '', finishedAt: '', failure: null })
  seen.length = 0
  window.history.replaceState(null, '', '/#/crafting')
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    const path = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : null
    seen.push({ path, method, body })
    if (path === '/api/session' && method === 'POST') return json({ accountId: ACCOUNT,
      accessContext: 'reservation-context', expiresAt: '2099-01-01T00:00:00Z', grants: ['account', 'inventories', 'characters', 'unlocks'] })
    expect(new Headers(init?.headers).has('X-GW2-API-Key')).toBe(false)
    expect(JSON.stringify(body)).not.toContain('SYNTHETIC-235-KEY')
    if (path === '/api/crafting/selector-options' && method === 'GET') return json(selectorOptions)
    if (path === TABLE && method === 'POST') return json(await profitAnswer(body, calls(TABLE).length - 1))
    if (path === DETAIL && method === 'POST') return json(await detailAnswer(body))
    if (path === '/api/account/bank' && method === 'GET') return json({ slotCount: 2,
      freshness: { status: 'FRESH', completedAt: '2099-01-01T00:00:00Z' },
      slots: [200, 100].map((count, slot) => ({ slot, itemId: ITEM, count, itemName: 'Copper Ore', iconUrl: null, rarity: 'Basic' })) })
    if (path === '/api/account/materials' && method === 'GET') return json({ categoryCount: 1,
      freshness: { status: 'FRESH', completedAt: '2099-01-01T00:00:00Z' }, categories: [{
        category: 5, name: 'Controlled Materials', order: 1,
        materials: [
          { position: 0, itemId: ITEM, count: 300, itemName: 'Copper Ore', iconUrl: null, rarity: 'Basic' },
          { position: 1, itemId: 19698, count: 1, itemName: null, iconUrl: null, rarity: null }
        ]
      }] })
    if (path.startsWith('/api/crafting/ingredient-search?') && method === 'GET') return json({ recipeIds: [11] })
    if (path === TASK && method === 'GET') return json(await taskAnswer())
    throw new Error(`Unexpected request: ${method} ${path}`)
  }))
  await connectKey('SYNTHETIC-235-KEY')
  seen.length = 0
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  installAccountTransport(null)
  resetAccountSession()
  resetMaterialProtection()
  pendingProfitSearch.value = null
  localStorage.clear()
  sessionStorage.clear()
  document.body.replaceChildren()
  vi.unstubAllGlobals()
})

async function open() {
  const App = (await import('../App.vue')).default
  wrapper = mount(App, { attachTo: document.body })
  await flushPromises()
  return wrapper
}
async function navigate(destination: string) {
  await wrapper!.get(`[data-test="nav-${destination}"]`).trigger('click')
  await flushPromises()
}
async function reserve(quantity: number) {
  await wrapper!.get('.inventory-tile').trigger('contextmenu', { button: 2, clientX: 10, clientY: 10 })
  await flushPromises()
  await wrapper!.get('[data-test="menu-reserve"]').trigger('click')
  await wrapper!.get('[data-test="reserve-input"]').setValue(String(quantity))
  await wrapper!.get('[data-test="reserve-dialog"]').trigger('submit')
  await flushPromises()
}
async function selectIron() {
  const row = wrapper!.findAll('[data-test="profit-row"]').find(candidate => candidate.text().includes('Iron Ingot'))!
  await row.get('[data-test="select-row"]').trigger('click')
  await flushPromises()
}
function enabledTarget(quantity = 100) {
  const state = useMaterialProtection()
  state.reserveQuantity(ITEM, 'bank', quantity)
  state.setActivation(true)
  return state
}

describe('reservation edits through the real kept-alive workspace', () => {
  it('makes zero offscreen calculations/details and calculates the latest autosaved stores once on return', async () => {
    enabledTarget()
    const app = await open()
    expect(calls(TABLE)).toHaveLength(1)
    await selectIron()
    expect(calls(DETAIL)).toHaveLength(1)
    await app.get('[data-test="max-displayed"]').setValue('7')
    await app.get('[data-test="max-displayed"]').trigger('change')
    await app.get('[data-test="filter-zero-craftable"]').setValue(false)
    await navigate('bank')
    const before = calls(TABLE).length
    const beforeDetail = calls(DETAIL).length
    await reserve(249)
    await reserve(250)
    await navigate('materials')
    await reserve(30)
    ;(app.get('input[data-test="protect-checkbox"]').element as HTMLInputElement).click() // finite -> ALL
    await flushPromises()
    expect(calls(TABLE)).toHaveLength(before)
    expect(calls(DETAIL)).toHaveLength(beforeDetail)
    expect(seen.filter(request => request.path.startsWith('/api/sync'))).toEqual([])

    const latest = deferred<CraftingProfitResponse>()
    profitAnswer = () => latest.promise
    await navigate('crafting')
    expect(calls(TABLE)).toHaveLength(before + 1)
    expect(calls(DETAIL)).toHaveLength(beforeDetail)
    expect(lastRequest().settings).toMatchObject({ materialProtectionEnabled: true, protectedItemIds: [],
      protectedBankItemIds: [], protectedMaterialItemIds: [ITEM],
      protectedBankQuantities: { [ITEM]: 250 }, protectedMaterialQuantities: {} })
    latest.resolve(echoedProfitResponse(lastRequest(), undefined, finalFreshness({ calculationId: 'latest-reservations' })))
    await flushPromises()
    expect(calls(DETAIL)).toHaveLength(beforeDetail + 1)
    const detail = calls(DETAIL).at(-1)!.body as CraftingProfitResolutionRequest
    expect(detail.calculation.settings).toEqual(echoedProfitResponse(lastRequest()).settings)
    expect(detail.basedOnCalculationId).toBe('latest-reservations')
    expect((app.get('[data-test="max-displayed"]').element as HTMLInputElement).value).toBe('7')
    expect((app.get('[data-test="filter-zero-craftable"]').element as HTMLInputElement).checked).toBe(false)

    await navigate('bank')
    await navigate('crafting')
    expect(calls(TABLE)).toHaveLength(before + 1) // no changed context: retained final table
  })

  it('does not calculate on inactive edits when protection is disabled', async () => {
    await open()
    await navigate('bank')
    await reserve(250)
    await navigate('materials')
    await reserve(17)
    await navigate('crafting')
    expect(calls(TABLE)).toHaveLength(1)
    expect(calls(DETAIL)).toHaveLength(0)
  })

  it('rejects an old same-ID list after only the finite target changed while inactive', async () => {
    enabledTarget(100)
    const old = deferred<CraftingProfitResponse>()
    profitAnswer = () => old.promise
    const app = await open()
    const initial = lastRequest()
    await navigate('bank')
    await reserve(250)
    old.resolve(echoedProfitResponse(initial, [{ ...profitableRow, outputName: 'OLD TARGET 100' }]))
    await flushPromises()
    expect(calls(TABLE)).toHaveLength(1)
    profitAnswer = async request => echoedProfitResponse(request, [{ ...profitableRow, outputName: 'LATEST TARGET 250' }])
    await navigate('crafting')
    expect(calls(TABLE)).toHaveLength(2)
    expect(app.text()).toContain('LATEST TARGET 250')
    expect(app.text()).not.toContain('OLD TARGET 100')
  })

  it('discards old detail across deactivation and a finite-only context change', async () => {
    enabledTarget(100)
    const app = await open()
    const old = deferred<ReturnType<typeof resolutionResponse>>()
    detailAnswer = () => old.promise
    await selectIron()
    const initial = calls(DETAIL)[0]!.body as CraftingProfitResolutionRequest
    await navigate('bank')
    await reserve(250)
    old.resolve(resolutionResponse(initial, { tree: node({ itemName: 'OLD DETAIL 100' }) }))
    await flushPromises()
    expect(calls(DETAIL)).toHaveLength(1)
    detailAnswer = async request => resolutionResponse(request, { tree: node({ itemName: 'LATEST DETAIL 250' }) })
    await navigate('crafting')
    expect(calls(TABLE)).toHaveLength(2)
    expect(calls(DETAIL)).toHaveLength(2)
    expect(app.text()).toContain('LATEST DETAIL 250')
    expect(app.text()).not.toContain('OLD DETAIL 100')
  })

  it('does not resubmit an admitted recovery task offscreen, then resumes the latest calculation once visible', async () => {
    enabledTarget()
    const status = deferred<SyncTaskStatus>()
    taskAnswer = () => status.promise
    profitAnswer = async request => echoedProfitResponse(request, undefined, provisionalFreshness({
      outstandingWork: [accountWork('reservation-existing-task', { taskStatusUrl: TASK })]
    }))
    await open()
    expect(calls(TASK)).toHaveLength(1)
    expect(calls(TABLE)).toHaveLength(1)
    await navigate('bank')
    status.resolve({ taskId: 'reservation-existing-task', operation: 'ACCOUNT_SYNC', state: 'SUCCEEDED',
      submittedAt: '', startedAt: '', finishedAt: '', failure: null })
    await flushPromises()
    expect(calls(TABLE)).toHaveLength(1)
    expect(calls(DETAIL)).toHaveLength(0)
    profitAnswer = async request => echoedProfitResponse(request)
    await navigate('crafting')
    expect(calls(TABLE)).toHaveLength(2)
  })

  it('prefills cached ingredient search without a calculation and preserves settings and browser history', async () => {
    const app = await open()
    await app.get('[data-test="scope-selector"]').setValue('DISCIPLINE|Chef')
    await flushPromises()
    await app.get('[data-test="max-displayed"]').setValue('7')
    await app.get('[data-test="max-displayed"]').trigger('change')
    const before = calls(TABLE).length
    await navigate('bank')
    await app.get('.inventory-tile').trigger('contextmenu', { button: 2, clientX: 10, clientY: 10 })
    await app.get('[data-test="menu-open-profit"]').trigger('click')
    await flushPromises()
    expect(window.location.hash).toBe('#/crafting')
    expect((app.get('[data-test="search"]').element as HTMLInputElement).value).toBe('Copper Ore')
    await vi.waitFor(() => expect(seen.filter(request => request.path.startsWith('/api/crafting/ingredient-search?'))).toHaveLength(1))
    const lookup = seen.find(request => request.path.startsWith('/api/crafting/ingredient-search?'))!
    expect(lookup.method).toBe('GET')
    expect(new URL(lookup.path, window.location.origin).searchParams.get('query')).toBe('Copper Ore')
    expect(app.findAll('[data-test="profit-row"]').map(row => row.text())).toEqual([expect.stringContaining('Iron Ingot')])
    expect(calls(TABLE)).toHaveLength(before)
    expect(calls(DETAIL)).toHaveLength(0)
    expect((app.get('[data-test="scope-selector"]').element as HTMLSelectElement).value).toBe('DISCIPLINE|Chef')
    expect((app.get('[data-test="max-displayed"]').element as HTMLInputElement).value).toBe('7')
    window.history.back()
    await vi.waitFor(() => expect(app.find('[data-test="bank-screen"]').exists()).toBe(true))
    window.history.forward()
    await vi.waitFor(() => expect(app.find('[data-test="profit-table"]').exists()).toBe(true))
    expect((app.get('[data-test="search"]').element as HTMLInputElement).value).toBe('Copper Ore')
    expect(calls(TABLE)).toHaveLength(before)
    expect(window.location.href).not.toContain('SYNTHETIC-235-KEY')
    expect(pendingProfitSearch.value).toBeNull()
  })

  it('keeps the unknown-name label honest but searches the selected ingredient by its bare ID and shows its cached consumer', async () => {
    const app = await open()
    const before = calls(TABLE).length
    await navigate('materials')
    const unknown = app.findAll('.inventory-tile')[1]!
    expect(unknown.attributes('title')).toBe('Item #19698')
    expect(unknown.get('input[type="checkbox"]').attributes('aria-label')).toContain('Item #19698')
    await unknown.trigger('contextmenu', { button: 2, clientX: 10, clientY: 10 })
    expect(app.get('[data-test="item-menu"]').attributes('aria-label')).toContain('Item #19698')
    await app.get('[data-test="menu-open-profit"]').trigger('click')
    await flushPromises()
    expect((app.get('[data-test="search"]').element as HTMLInputElement).value).toBe('19698')
    await vi.waitFor(() => expect(seen.filter(request => request.path.startsWith('/api/crafting/ingredient-search?'))).toHaveLength(1))
    const lookup = seen.find(request => request.path.startsWith('/api/crafting/ingredient-search?'))!
    expect(lookup.method).toBe('GET')
    expect(new URL(lookup.path, window.location.origin).searchParams.get('query')).toBe('19698')
    // Iron Ingot has neither 19698 in its output identity nor a supplied ingredient name. Its
    // presence therefore depends on the fixture's explicit cached consumer recipe ID (11).
    expect(app.findAll('[data-test="profit-row"]').map(row => row.text())).toEqual([expect.stringContaining('Iron Ingot')])
    expect(calls(TABLE)).toHaveLength(before)
    expect(calls(DETAIL)).toHaveLength(0)
    expect(window.location.hash).toBe('#/crafting')
  })
})
