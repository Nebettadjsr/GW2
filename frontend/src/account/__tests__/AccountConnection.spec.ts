import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { STORAGE_KEY, connection, installAccountSession, resetAccountSession } from '@/api/accountSession'
import { installAccountTransport } from '@/api/http'
import { bankWithEmptySlots, materialStorage } from '@/account/__tests__/accountFixtures'
import { selectorOptions } from '@/crafting/__tests__/fixtures'

/**
 * The integrated browser flow at the network boundary (STORY-WEB-035): masked key entry, restoration
 * before account data, first synchronization when the account was never published, replacement and
 * disconnect without stale data, and a refused key. Backend answers are controlled fixtures.
 */
interface Seen {
  path: string
  method: string
  headers: Record<string, string>
}

let seen: Seen[]
let wrapper: VueWrapper | null = null
let keyAccepted: (key: string) => boolean
let selectorPublished: boolean
let syncDone: boolean
let bankFor: (context: string) => unknown

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function backend(path: string, init: RequestInit | undefined): Response {
  const headers = { ...(init?.headers as Record<string, string>) }
  if (path === '/api/session') {
    const key = headers['X-GW2-API-Key'] ?? ''
    return keyAccepted(key)
      ? json({ accessContext: `ctx-${key}`, expiresAt: '2099-01-01T00:00:00Z', grants: ['account'] })
      : json({ error: 'INVALID_CREDENTIAL', message: 'The GW2 API key could not be validated' }, 401)
  }
  if (path === '/api/session/logout') return new Response(null, { status: 204 })
  if (path === '/api/crafting/selector-options') {
    return selectorPublished || syncDone
      ? json(selectorOptions)
      : json({ defaultScopeKind: 'ALL', disciplines: [], characterOptionCount: null, characterOptions: null }, 202)
  }
  if (path === '/api/sync/account/crafting-profit') {
    syncDone = true
    return json({ taskId: 'task-1', operation: 'ACCOUNT_SYNC', statusUrl: '/api/sync/tasks/task-1', coversRequest: true }, 202)
  }
  if (path === '/api/sync/tasks/task-1') {
    return json({ taskId: 'task-1', operation: 'ACCOUNT_SYNC', state: 'SUCCEEDED', submittedAt: 'x', startedAt: 'x', finishedAt: 'x', failure: null })
  }
  if (path === '/api/account/bank') return json(bankFor(headers['X-Account-Context'] ?? ''))
  if (path === '/api/account/materials') return json(materialStorage)
  if (path === '/api/crafting/profit') return json({ status: 'PENDING', rows: null, scope: { kind: 'ALL' }, settings: {}, freshness: null }, 202)
  return json({ error: 'NOT_FOUND', message: path }, 404)
}

async function open(): Promise<VueWrapper> {
  const App = (await import('../../App.vue')).default
  wrapper = mount(App, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

async function enterKey(app: VueWrapper, key: string): Promise<void> {
  await app.find('[data-test="api-key-input"]').setValue(key)
  await app.find('form.connection-panel__form').trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  seen = []
  keyAccepted = () => true
  selectorPublished = true
  syncDone = false
  bankFor = () => bankWithEmptySlots
  resetAccountSession()
  installAccountSession()
  window.history.replaceState(null, '', '/#/bank')
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    seen.push({ path: input, method: init?.method ?? 'GET', headers: { ...(init?.headers as Record<string, string>) } })
    return backend(input, init)
  }))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  installAccountTransport(null)
  resetAccountSession()
  vi.unstubAllGlobals()
})

describe('account connection flow', () => {
  it('shows a masked entry and loads no account data before a key is connected', async () => {
    const app = await open()

    expect(app.find('[data-test="api-key-input"]').attributes('type')).toBe('password')
    expect(app.find('[data-test="bank-loading"]').exists()).toBe(false)
    expect(seen).toHaveLength(0)
  })

  it('proves the entered key, retains it, then loads account data with the access context', async () => {
    const app = await open()

    await enterKey(app, 'KEY-A')

    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('KEY-A')
    expect(app.find('[data-test="connection-status"]').text()).toMatch(/Connected/)
    const paths = seen.map((request) => request.path)
    expect(paths.indexOf('/api/session')).toBe(0)
    expect(paths).toContain('/api/account/bank')
    expect(seen.find((request) => request.path === '/api/account/bank')?.headers['X-Account-Context']).toBe('ctx-KEY-A')
    expect((app.find('[data-test="api-key-input"]').element as HTMLInputElement).value).toBe('')
  })

  it('restores a retained key after a reload by proving it before any account read', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')

    const app = await open()

    expect(app.find('[data-test="connection-status"]').text()).toMatch(/Connected/)
    const paths = seen.map((request) => request.path)
    expect(paths[0]).toBe('/api/session')
    expect(paths.indexOf('/api/account/bank')).toBeGreaterThan(0)
  })

  it('synchronizes first when the account data was never published, instead of showing a raw error', async () => {
    selectorPublished = false
    const app = await open()

    await enterKey(app, 'KEY-A')

    const paths = seen.map((request) => request.path)
    expect(paths).toContain('/api/sync/account/crafting-profit')
    expect(paths.indexOf('/api/sync/account/crafting-profit')).toBeLessThan(paths.indexOf('/api/account/bank'))
    expect(seen.find((request) => request.path === '/api/sync/account/crafting-profit')?.headers['X-GW2-API-Key']).toBe('KEY-A')
    expect(app.find('[data-test="bootstrap-failed"]').exists()).toBe(false)
  })

  it('never shows the previous account bank after the key is replaced', async () => {
    bankFor = (context) => (context === 'ctx-KEY-A' ? { ...bankWithEmptySlots, slots: bankWithEmptySlots.slots.map((slot, index) => (index === 0 ? { ...slot, count: 987 } : slot)) } : bankWithEmptySlots)
    const app = await open()
    await enterKey(app, 'KEY-A')
    expect(app.text()).toContain('987')

    await enterKey(app, 'KEY-B')

    expect(app.text()).not.toContain('987')
    expect(seen.filter((request) => request.path === '/api/account/bank').map((r) => r.headers['X-Account-Context']))
      .toEqual(['ctx-KEY-A', 'ctx-KEY-B'])
  })

  it('clears the key and every account screen on disconnect, even when logout fails', async () => {
    const app = await open()
    await enterKey(app, 'KEY-A')
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('offline') }))

    await app.find('[data-test="disconnect"]').trigger('click')
    await flushPromises()

    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(connection.phase).toBe('disconnected')
    expect(app.find('[data-test="bank-loading"]').exists()).toBe(false)
    expect(app.find('[data-test="logout-notice"]').exists()).toBe(true)
  })

  it('presents entry again with an error when the key is refused', async () => {
    keyAccepted = () => false
    const app = await open()

    await enterKey(app, 'BAD-KEY')

    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(app.find('[data-test="connection-error"]').text()).toMatch(/not accepted/)
    expect(seen.map((request) => request.path)).toEqual(['/api/session'])
  })
})
