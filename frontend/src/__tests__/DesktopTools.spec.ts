import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { connectKey, connection, installAccountSession, resetAccountSession, STORAGE_KEY } from '@/api/accountSession'
import { installAccountTransport } from '@/api/http'
import { bankWithEmptySlots, deferred, materialStorage } from '@/account/__tests__/accountFixtures'
import { echoedDiscoveryResponse, profitResponse, resolutionResponse, selectorOptions } from '@/crafting/__tests__/fixtures'
import { accountLuck, itemMetadata, itemPrices } from '@/ecto/__tests__/currentEctoFixtures'

// Real App, routes, session, KeepAlive and clients. Only viewport and HTTP answers are controlled.
beforeAll(async () => { await import('../App.vue') }, 30_000)
const KEY = 'synthetic-desktop-unit-key'
const ACCOUNT = '00000000-0000-4000-8000-000000000243'
const tools = ['crafting', 'discovery', 'forge', 'ecto', 'synchronization', 'bank', 'materials', 'characters']
const WARNING = 'This page is not optimized for mobile use. You can scroll horizontally to use the whole page.'
const toolContent: Record<string, string> = {
  crafting: '[data-test="calculation-controls"]', forge: '[data-test="forge-screen"]', discovery: '[data-test="discovery-calculation-controls"]',
  ecto: '#ecto-count', synchronization: '[data-test="api-key-input"]', bank: '[data-test="bank-slots"]',
  materials: '[data-test="material-category"]', characters: '[data-test="roster-empty"]'
}
let wrapper: VueWrapper | null = null
let width = 1440
const media = new Set<{ query: string; list: MediaQueryList; listeners: Set<(event: MediaQueryListEvent) => void> }>()
const requests: string[] = []
let heldProfit: Promise<Response> | null = null
const originalWidth = Object.getOwnPropertyDescriptor(window, 'innerWidth')

function matches(query: string): boolean {
  const min = query.match(/min-width:\s*([\d.]+)px/)
  const max = query.match(/max-width:\s*([\d.]+)px/)
  return (!min || width >= Number(min[1])) && (!max || width <= Number(max[1]))
}

async function resize(next: number): Promise<void> {
  const previous = new Map([...media].map(entry => [entry, entry.list.matches]))
  width = next
  window.dispatchEvent(new Event('resize'))
  for (const entry of media) if (previous.get(entry) !== entry.list.matches) {
    const event = { matches: entry.list.matches, media: entry.query } as MediaQueryListEvent
    entry.listeners.forEach(listener => listener(event))
    entry.list.onchange?.call(entry.list, event)
  }
  await flushPromises()
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  width = 1440
  media.clear()
  requests.length = 0
  heldProfit = null
  window.sessionStorage.clear()
  window.localStorage.clear()
  resetAccountSession()
  installAccountSession()
  Object.defineProperty(window, 'innerWidth', { configurable: true, get: () => width })
  vi.stubGlobal('matchMedia', (query: string) => {
    const listeners = new Set<(event: MediaQueryListEvent) => void>()
    const list = { media: query, get matches() { return matches(query) }, onchange: null,
      addEventListener: (_: string, listener: (event: MediaQueryListEvent) => void) => listeners.add(listener),
      removeEventListener: (_: string, listener: (event: MediaQueryListEvent) => void) => listeners.delete(listener),
      addListener: (listener: (event: MediaQueryListEvent) => void) => listeners.add(listener),
      removeListener: (listener: (event: MediaQueryListEvent) => void) => listeners.delete(listener), dispatchEvent: () => true
    } as MediaQueryList
    media.add({ query, list, listeners })
    return list
  })
  vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
    requests.push(path)
    if (path === '/api/session') return json({ accountId: ACCOUNT, accessContext: 'desktop-context',
      expiresAt: '2099-01-01T00:00:00Z', grants: ['account', 'inventories', 'characters', 'unlocks'] })
    if (path === '/api/crafting/selector-options') return json(selectorOptions)
    if (path === '/api/mystic-forge/recipes') return json({ status: 'OK', source: 'wiki', license: 'CC', recipeCount: 0, referenceCount: 0, recipes: [] })
    if (path === '/api/crafting/profit') return heldProfit ?? json(profitResponse())
    if (path === '/api/crafting/discovery') return json(echoedDiscoveryResponse(JSON.parse(String(init?.body))))
    if (path === '/api/crafting/profit/resolution') return json(resolutionResponse(JSON.parse(String(init?.body))))
    if (path === '/api/account/bank') return json(bankWithEmptySlots)
    if (path === '/api/account/materials') return json(materialStorage)
    if (path === '/api/account/characters') return json({ state: 'AVAILABLE', characterCount: 0, characters: [],
      freshness: { status: 'CURRENT', completedAt: '2099-01-01T00:00:00Z', refresh: 'CURRENT' } })
    if (path.startsWith('/api/items/metadata?')) return json(itemMetadata)
    if (path.startsWith('/api/items/prices?')) return json(itemPrices)
    if (path === '/api/account/luck') return json(accountLuck)
    if (path === '/api/system/status') return json({ globalHealth: 'OK', priceHealth: 'OK' })
    if (path === '/api/system/status/details') return json({ error: 'FORBIDDEN', message: 'Not owner' }, 403)
    throw new Error(`Unexpected controlled request: ${path}`)
  }))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  installAccountTransport(null)
  resetAccountSession()
  window.sessionStorage.clear()
  window.localStorage.clear()
  if (originalWidth) Object.defineProperty(window, 'innerWidth', originalWidth)
  vi.unstubAllGlobals()
})

async function open(path: string): Promise<VueWrapper> {
  window.history.replaceState(null, '', path)
  wrapper = mount((await import('../App.vue')).default, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

function notice(app: VueWrapper): void {
  const region = app.get('[data-test="desktop-width-notice"]')
  expect(region.text()).toBe(WARNING)
  expect(region.isVisible()).toBe(true)
}

// Owner18Nov revision:1024px is a layout baseline, never an access/session gate.
describe('desktop-layout warning through the real App', () => {
  it.each(tools)('restores the retained key and keeps %s available on a direct1023px bookmark', async destination => {
    await resize(1023)
    window.sessionStorage.setItem(STORAGE_KEY, KEY)
    const app = await open(`/#/${destination}`)
    notice(app)
    expect(app.get(toolContent[destination]!).isVisible()).toBe(true)
    expect(requests[0]).toBe('/api/session')
    expect(requests.filter(path => path === '/api/session')).toHaveLength(1)
    expect(connection.phase).toBe('connected')
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(KEY)
    expect(window.location.hash).toBe(`#/${destination}`)
  })

  it.each(['/welcome', '/#/legal', '/#/privacy', '/#/storage'])('keeps public%s readable and request-quiet at360px', async path => {
    await resize(360)
    window.sessionStorage.setItem(STORAGE_KEY, KEY)
    const app = await open(path)
    expect(app.find('[data-test="desktop-width-notice"]').exists()).toBe(false)
    expect(app.find('[data-test="page-heading"]').exists()).toBe(true)
    expect(requests).toEqual([])
    if (path === '/welcome') for (const tab of ['features', 'guide', 'tips', 'welcome']) {
      await app.get(`#home-tab-${tab}`).trigger('click')
      expect(app.get(`#home-panel-${tab}`).isVisible()).toBe(true)
    }
    expect(requests).toEqual([])
  })

  it('loads Bank at390px and removes only the warning at1024px without another proof/read', async () => {
    await resize(390)
    window.sessionStorage.setItem(STORAGE_KEY, KEY)
    const app = await open('/#/bank')
    notice(app)
    expect(app.get('[data-test="bank-slots"]').isVisible()).toBe(true)
    expect(requests).toEqual(['/api/session', '/api/account/bank'])
    await resize(1024)
    expect(app.find('[data-test="desktop-width-notice"]').exists()).toBe(false)
    expect(app.find('[data-test="bank-slots"]').exists()).toBe(true)
    expect(requests).toEqual(['/api/session', '/api/account/bank'])
    expect(window.location.hash).toBe('#/bank')
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(KEY)
  })

  it('preserves completed Profit selection, controls, credentials and saved preferences through resize', async () => {
    await connectKey(KEY)
    const app = await open('/#/crafting')
    await app.get('[data-test="select-row"]').trigger('click')
    await flushPromises()
    await app.get('[data-test="max-displayed"]').setValue('7')
    const selected = app.get('[data-test="detail-name"]').text()
    const generation = connection.generation
    window.localStorage.setItem('desktop-qa-sentinel', 'saved-reservation-sentinel')
    const before = [...requests]
    await resize(1023)
    notice(app)
    expect(app.get('[data-test="calculation-controls"]').isVisible()).toBe(true)
    expect(app.get('[data-test="detail-name"]').text()).toBe(selected)
    expect(requests).toEqual(before)
    expect(connection.generation).toBe(generation)
    expect(connection.phase).toBe('connected')
    expect(window.location.hash).toBe('#/crafting')
    await resize(1024)
    expect(app.find('[data-test="desktop-width-notice"]').exists()).toBe(false)
    expect(app.get('[data-test="detail-name"]').text()).toBe(selected)
    expect(app.get('[data-test="max-displayed"]').element).toHaveProperty('value', '7')
    expect(requests).toEqual(before)
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(KEY)
    expect(window.localStorage.getItem('desktop-qa-sentinel')).toBe('saved-reservation-sentinel')
  })

  it('navigates from a narrow tool to quiet public Home and back with ordinary connected reads', async () => {
    await resize(1023)
    window.sessionStorage.setItem(STORAGE_KEY, KEY)
    const app = await open('/#/bank')
    notice(app)
    expect(app.get('[data-test="bank-slots"]').isVisible()).toBe(true)
    const beforeHome = [...requests]
    await app.get('[data-test="nav-home"]').trigger('click')
    await flushPromises()
    expect(app.get('[data-test="home-screen"]').isVisible()).toBe(true)
    expect(requests).toEqual(beforeHome)
    await app.get('[data-test="nav-materials"]').trigger('click')
    await flushPromises()
    notice(app)
    expect(app.get('[data-test="material-category"]').isVisible()).toBe(true)
    expect(requests).toEqual([...beforeHome, '/api/account/materials'])
  })

  it('opens a previously unvisited tool while narrow using ordinary reads and the same connection', async () => {
    await connectKey(KEY)
    const app = await open('/#/crafting')
    await resize(1023)
    notice(app)
    const before = [...requests]
    await app.get('[data-test="nav-ecto"]').trigger('click')
    await flushPromises()
    notice(app)
    expect(app.get('[data-test="ecto-screen"]').isVisible()).toBe(true)
    expect(requests.slice(before.length).filter(path => path.startsWith('/api/items/metadata?'))).toHaveLength(1)
    expect(requests.slice(before.length).filter(path => path.startsWith('/api/items/prices?'))).toHaveLength(1)
    expect(requests.slice(before.length).filter(path => path === '/api/account/luck')).toHaveLength(1)
    expect(requests.filter(path => path === '/api/session')).toHaveLength(1)
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(KEY)
  })

  it('shows the pending Profit answer while narrow and does not calculate again merely on widening', async () => {
    await connectKey(KEY)
    const answer = deferred<Response>()
    heldProfit = answer.promise
    const app = await open('/#/crafting')
    expect(requests.filter(path => path === '/api/crafting/profit')).toHaveLength(1)
    await resize(1023)
    notice(app)
    heldProfit = null // Any erroneous second request receives a fresh ordinary response, not a reused Response body.
    answer.resolve(json(profitResponse()))
    await flushPromises()
    expect(requests.filter(path => path === '/api/crafting/profit')).toHaveLength(1)
    expect(app.get('[data-test="profit-table"]').isVisible()).toBe(true)
    await resize(1024)
    expect(app.find('[data-test="desktop-width-notice"]').exists()).toBe(false)
    expect(requests.filter(path => path === '/api/crafting/profit')).toHaveLength(1)
    expect(app.find('[data-test="profit-table"]').exists()).toBe(true)
    expect(app.get('[data-test="profit-table"]').isVisible()).toBe(true)
    expect(app.get('[data-test="profit-table"]').text()).toContain(profitResponse().rows![0]!.outputName)
  })

  it('preserves Ecto inputs and settings across width changes but still resets on actual navigation', async () => {
    await connectKey(KEY)
    const app = await open('/#/ecto')
    await app.get('#ecto-count').setValue('42')
    await app.findAll('.salvage-row').find(button => button.text().includes('Basic / Copper-Fed'))!.trigger('click')
    await app.findAll('.tool-choice__button').find(button => button.text() === 'Copper-Fed')!.trigger('click')
    await app.findAll('.tp-block')[0]!.findAll('button').find(button => button.text().includes('Buy order'))!.trigger('click')
    await app.findAll('.tp-block')[1]!.findAll('button').find(button => button.text().includes('Listing sell'))!.trigger('click')
    const result = app.get('[data-test="ecto-calculation"]').text()
    const before = [...requests]
    await resize(1023)
    notice(app)
    expect(app.get('[data-test="ecto-screen"]').isVisible()).toBe(true)
    expect(app.get('#ecto-count').element).toHaveProperty('value', '42')
    await resize(1024)
    expect(requests).toEqual(before)
    expect(app.get('#ecto-count').element).toHaveProperty('value', '42')
    expect(app.get('.salvage-row[aria-pressed="true"]').text()).toContain('Basic / Copper-Fed')
    expect(app.get('.tool-choice__button--selected').text()).toBe('Copper-Fed')
    expect(app.findAll('.tp-block')[0]!.get('[aria-pressed="true"]').text()).toContain('Buy order')
    expect(app.findAll('.tp-block')[1]!.get('[aria-pressed="true"]').text()).toContain('Listing sell')
    expect(app.get('[data-test="ecto-calculation"]').text()).toBe(result)
    await app.get('[data-test="nav-bank"]').trigger('click')
    await flushPromises()
    await app.get('[data-test="nav-ecto"]').trigger('click')
    await flushPromises()
    expect(app.get('#ecto-count').element).toHaveProperty('value', '100')
    for (const prefix of ['/api/items/metadata?', '/api/items/prices?', '/api/account/luck']) {
      expect(requests.filter(path => path.startsWith(prefix))).toHaveLength(2)
    }
  })
})
