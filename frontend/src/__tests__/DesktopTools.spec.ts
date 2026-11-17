import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { connectKey, connection, installAccountSession, resetAccountSession, STORAGE_KEY } from '@/api/accountSession'
import { installAccountTransport } from '@/api/http'
import { bankWithEmptySlots, materialStorage } from '@/account/__tests__/accountFixtures'
import { profitResponse, resolutionResponse, selectorOptions } from '@/crafting/__tests__/fixtures'

// Real App, routes, session, KeepAlive and clients. Only viewport and HTTP answers are controlled.
beforeAll(async () => { await import('../App.vue') }, 30_000)
const KEY = 'synthetic-desktop-unit-key'
const ACCOUNT = '00000000-0000-4000-8000-000000000243'
const tools = ['crafting', 'discovery', 'ecto', 'synchronization', 'bank', 'materials', 'characters']
let wrapper: VueWrapper | null = null
let width = 1440
const media = new Set<{ query: string; list: MediaQueryList; listeners: Set<(event: MediaQueryListEvent) => void> }>()
const requests: string[] = []
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
    if (path === '/api/crafting/profit') return json(profitResponse())
    if (path === '/api/crafting/profit/resolution') return json(resolutionResponse(JSON.parse(String(init?.body))))
    if (path === '/api/account/bank') return json(bankWithEmptySlots)
    if (path === '/api/account/materials') return json(materialStorage)
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
  expect(region.text()).toContain('Please use a PC or a wider browser window')
  expect(region.text()).toContain('1024')
  expect(region.find('h1, h2').exists()).toBe(true)
  expect(region.find('a[href="/welcome"]').exists()).toBe(true)
  expect(app.find('[data-test="api-key-input"]').exists()).toBe(false)
}

describe('desktop tool boundary through the real App', () => {
  it.each(tools)('does not prove or mount hidden %s on a direct1023px bookmark', async destination => {
    await resize(1023)
    window.sessionStorage.setItem(STORAGE_KEY, KEY)
    const app = await open(`/#/${destination}`)
    notice(app)
    expect(app.find(`[data-test="${destination}-screen"]`).exists()).toBe(false)
    expect(app.find('[data-test="calculation-controls"]').exists()).toBe(false)
    expect(requests).toEqual([])
    expect(connection.phase).toBe('disconnected')
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

  it('permits exactly1024px and widening a blocked bookmark proves the retained key once', async () => {
    await resize(1023)
    window.sessionStorage.setItem(STORAGE_KEY, KEY)
    const app = await open('/#/bank')
    notice(app)
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
    expect(app.get('[data-test="desktop-width-notice"]').text()).toContain('1024')
    const controls = app.find('[data-test="calculation-controls"]')
    expect(controls.exists() && controls.isVisible()).toBe(false)
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

  it('can navigate from a blocked tool to public Home and back without proving until widened', async () => {
    await resize(1023)
    window.sessionStorage.setItem(STORAGE_KEY, KEY)
    const app = await open('/#/bank')
    await app.get('[data-test="nav-home"]').trigger('click')
    await flushPromises()
    expect(app.get('[data-test="home-screen"]').isVisible()).toBe(true)
    await app.get('[data-test="nav-materials"]').trigger('click')
    await flushPromises()
    notice(app)
    expect(requests).toEqual([])
  })
})
