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

const input = (w: ReturnType<typeof mount>) => w.get('[data-test="api-key-input"]')
const value = (w: ReturnType<typeof mount>) => (input(w).element as HTMLInputElement).value

it('opens one help dialog at a time, with the official links, and closes by button and Escape with focus return', async () => {
  const w = mount(ConnectionPanel, { attachTo: document.body })
  expect(w.find('[data-test="help-dialog"]').exists()).toBe(false)
  await w.get('[data-test="help-create"]').trigger('click')
  const dialog = w.get('[data-test="help-dialog"]')
  expect(dialog.attributes('role')).toBe('dialog')
  expect(dialog.attributes('aria-modal')).toBe('true')
  expect(dialog.text()).toContain('account, inventories, characters, unlocks, progression, and builds')
  expect(dialog.text()).toContain('Steam or Epic')
  expect(dialog.findAll('a').map((a) => a.attributes('href'))).toEqual(['https://account.arena.net/applications'])
  expect(dialog.get('a').attributes('rel')).toContain('noopener')
  expect(document.activeElement).toBe(w.get('[data-test="help-close"]').element)

  await w.get('[data-test="help-safety"]').trigger('click')
  expect(w.findAll('[data-test="help-dialog"]')).toHaveLength(1)
  const safety = w.get('[data-test="help-dialog"]')
  expect(safety.text()).toContain('read-only access')
  expect(safety.text()).toContain('does not delete')
  expect(safety.findAll('a').map((a) => a.attributes('href'))).toEqual([
    'https://wiki.guildwars2.com/wiki/API:API_key',
    'https://account.arena.net/applications'
  ])

  await w.get('[data-test="help-close"]').trigger('click')
  expect(w.find('[data-test="help-dialog"]').exists()).toBe(false)
  expect(document.activeElement).toBe(w.get('[data-test="help-safety"]').element)

  await w.get('[data-test="help-create"]').trigger('click')
  await w.get('[data-test="help-dialog"]').trigger('keydown', { key: 'Escape' })
  expect(w.find('[data-test="help-dialog"]').exists()).toBe(false)
  expect(document.activeElement).toBe(w.get('[data-test="help-create"]').element)
  w.unmount()
})

it('help never touches the draft, storage or connection', async () => {
  const w = mount(ConnectionPanel)
  await input(w).setValue('typed')
  const generation = connection.generation
  await w.get('[data-test="help-safety"]').trigger('click')
  expect(value(w)).toBe('typed')
  expect(connection.generation).toBe(generation)
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
})

it('the eye toggles only the typed draft, with a changing label, and does not submit', async () => {
  const w = mount(ConnectionPanel)
  const eye = w.get('[data-test="toggle-key-visibility"]')
  expect(eye.attributes('type')).toBe('button')
  expect([eye.attributes('aria-label'), eye.attributes('aria-pressed'), input(w).attributes('type')]).toEqual([
    'Show API key', 'false', 'password'
  ])
  await input(w).setValue('typed')
  await eye.trigger('click')
  expect([eye.attributes('aria-label'), eye.attributes('aria-pressed'), input(w).attributes('type')]).toEqual([
    'Hide API key', 'true', 'text'
  ])
  expect(paths).toEqual([])
  await eye.trigger('click')
  expect(input(w).attributes('type')).toBe('password')
})

it('a failed submission keeps the draft and its visibility', async () => {
  handler = () => json({ error: 'INVALID_CREDENTIAL', message: 'no' }, 401)
  const w = mount(ConnectionPanel)
  await input(w).setValue('BAD')
  await w.get('[data-test="toggle-key-visibility"]').trigger('click')
  await w.get('form').trigger('submit')
  await flushPromises()
  expect(value(w)).toBe('BAD')
  expect(input(w).attributes('type')).toBe('text')
})

it('an accepted submission and Disconnect clear the draft and mask it again', async () => {
  handler = (path) => (path === '/api/session' ? proof() : new Response(null, { status: 204 }))
  const w = mount(ConnectionPanel)
  await input(w).setValue('KEY-A')
  await w.get('[data-test="toggle-key-visibility"]').trigger('click')
  await w.get('form').trigger('submit')
  await flushPromises()
  expect(value(w)).toBe('')
  expect(input(w).attributes('type')).toBe('password')

  await input(w).setValue('again')
  await w.get('[data-test="toggle-key-visibility"]').trigger('click')
  await w.get('[data-test="disconnect"]').trigger('click')
  await flushPromises()
  expect(value(w)).toBe('')
  expect(input(w).attributes('type')).toBe('password')
})

it('an account change from elsewhere clears and masks the draft, and a remount starts masked', async () => {
  const w = mount(ConnectionPanel)
  await input(w).setValue('typed')
  await w.get('[data-test="toggle-key-visibility"]').trigger('click')
  connection.generation += 1
  await flushPromises()
  expect(value(w)).toBe('')
  expect(input(w).attributes('type')).toBe('password')
  w.unmount()
  expect(input(mount(ConnectionPanel)).attributes('type')).toBe('password')
})

it('never fills the field from a restored or retained key', async () => {
  window.sessionStorage.setItem(STORAGE_KEY, 'KEY-SECRET')
  connection.phase = 'unavailable'
  const w = mount(ConnectionPanel)
  await w.get('[data-test="toggle-key-visibility"]').trigger('click')
  expect(value(w)).toBe('')
  expect(w.html()).not.toContain('KEY-SECRET')
  await w.get('[data-test="help-safety"]').trigger('click')
  expect(w.html()).not.toContain('KEY-SECRET')
})
