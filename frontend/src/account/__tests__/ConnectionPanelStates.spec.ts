import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { STORAGE_KEY, connection, installAccountSession, resetAccountSession } from '@/api/accountSession'
import { installAccountTransport } from '@/api/http'
import ConnectionPanel from '../ConnectionPanel.vue'

/** What the key panel shows and offers in each connection phase, and a submit while it is busy. */
let handler: (path: string) => Response | Promise<Response>
let paths: string[]

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const proof = (): Response => json({ accessContext: 'ctx', expiresAt: '2099-01-01T00:00:00Z', grants: [] })

beforeEach(() => {
  paths = []
  resetAccountSession()
  installAccountSession()
  vi.stubGlobal('fetch', vi.fn(async (path: string) => { paths.push(path); return handler(path) }))
})
afterEach(() => {
  installAccountTransport(null)
  resetAccountSession()
  vi.unstubAllGlobals()
})

const status = (w: ReturnType<typeof mount>) => w.get('[data-test="connection-status"]').text()

it.each([
  ['restoring', 'Reconnecting with the key kept in this browser session…'],
  ['connecting', 'Checking the key…'],
  ['unavailable', 'The saved key could not be checked right now.'],
  ['connected', 'Connected to your Guild Wars 2 account.'],
  ['disconnected', 'Not connected. Enter a read-only GW2 API key to load your account data.']
] as const)('says in words that the connection is %s', async (phase, text) => {
  const w = mount(ConnectionPanel)
  connection.phase = phase
  await flushPromises()
  expect(status(w)).toBe(text)
  const working = phase === 'restoring' || phase === 'connecting'
  expect(w.get('[data-test="api-key-input"]').attributes('disabled') !== undefined).toBe(working)
  expect(w.find('[data-test="retry-connection"]').exists()).toBe(phase === 'unavailable')
  expect(w.find('[data-test="disconnect"]').exists()).toBe(phase === 'connected' || phase === 'unavailable')
  expect(w.get('[data-test="connect"]').text()).toBe(phase === 'connected' ? 'Replace key' : 'Connect')
})

it('does not submit while a restore is in progress', async () => {
  const w = mount(ConnectionPanel)
  connection.phase = 'restoring'
  await w.get('[data-test="api-key-input"]').setValue('KEY-A')
  await w.get('form').trigger('submit')
  expect(paths).toEqual([])
})

it('keeps the typed key and shows the error when the key is refused', async () => {
  handler = () => json({ error: 'INVALID_CREDENTIAL', message: 'no' }, 401)
  const w = mount(ConnectionPanel)
  await w.get('[data-test="api-key-input"]').setValue('BAD')
  await w.get('form').trigger('submit')
  await flushPromises()
  expect(w.get('[data-test="connection-error"]').text()).toContain('was not accepted')
  expect((w.get('[data-test="api-key-input"]').element as HTMLInputElement).value).toBe('BAD')
  expect(w.get('[data-test="connect"]').attributes('disabled')).toBeUndefined()
})

it('offers a retry after an outage and connects when it succeeds, then disconnect clears the field', async () => {
  window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')
  connection.phase = 'unavailable'
  handler = (path) => (path === '/api/session' ? proof() : new Response(null, { status: 204 }))
  const w = mount(ConnectionPanel)
  await w.get('[data-test="retry-connection"]').trigger('click')
  await flushPromises()
  expect(status(w)).toBe('Connected to your Guild Wars 2 account.')
  await w.get('[data-test="api-key-input"]').setValue('half typed')
  await w.get('[data-test="disconnect"]').trigger('click')
  await flushPromises()
  expect((w.get('[data-test="api-key-input"]').element as HTMLInputElement).value).toBe('')
  expect(paths).toEqual(['/api/session', '/api/session/logout'])
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
})

it('shows the logout notice when the server session could not be ended', async () => {
  handler = (path) => (path === '/api/session' ? proof() : json({ error: 'X', message: 'y' }, 500))
  const w = mount(ConnectionPanel)
  await w.get('[data-test="api-key-input"]').setValue('KEY-A')
  await w.get('form').trigger('submit')
  await flushPromises()
  expect((w.get('[data-test="api-key-input"]').element as HTMLInputElement).value).toBe('')
  await w.get('[data-test="disconnect"]').trigger('click')
  await flushPromises()
  expect(w.get('[data-test="logout-notice"]').text()).toContain('could not be ended')
})
