import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { STORAGE_KEY, connection, disconnect, installAccountSession, resetAccountSession } from '@/api/accountSession'
import { installAccountTransport } from '@/api/http'
import ConnectionPanel from '../ConnectionPanel.vue'
import { clearDraft } from '../keyDraft'

/** What the key panel shows and offers in each connection phase, and a submit while it is busy. */
let handler: (path: string) => Response | Promise<Response>
let paths: string[]

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const proof = (): Response => json({ accessContext: 'ctx', expiresAt: '2099-01-01T00:00:00Z', grants: [] })

beforeEach(() => {
  // jsdom has no modal <dialog>; the browser behaviour is covered by real-browser evidence.
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    if (!this.hasAttribute('open')) return
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
  clearDraft()
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

const status = (w: ReturnType<typeof mount>) => w.find('[data-test="connection-status"]').exists() ? w.get('[data-test="connection-status"]').text() : ''

// Routine connected/disconnected states are not narrated; working and outage states are.
it.each([
  ['restoring', 'Reconnecting with the key kept in this browser session…'],
  ['connecting', 'Checking the key…'],
  ['unavailable', 'The saved key could not be checked right now.'],
  ['connected', ''],
  ['disconnected', '']
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
  expect(status(w)).toBe('')
  expect(w.find('[data-test="disconnect"]').exists()).toBe(true)
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

it('opens one modal help dialog at a time, with the official links, and closes by button and Escape with focus return', async () => {
  const w = mount(ConnectionPanel, { attachTo: document.body })
  expect(w.find('[data-test="help-dialog"]').exists()).toBe(false)
  await w.get('[data-test="help-create"]').trigger('click')
  const dialog = w.get('[data-test="help-dialog"]')
  expect(dialog.element.tagName).toBe('DIALOG')
  expect(dialog.attributes('open')).toBeDefined()
  expect(dialog.get('h3').text()).toBe('Get your API key')
  const steps = dialog.findAll('ol > li')
  expect(steps).toHaveLength(4)
  expect(steps[1]!.findAll('strong').map((phrase) => phrase.text())).toEqual(['Create a new key', 'all permissions'])
  expect(steps[1]!.text()).toContain('all permissions')
  expect(steps[2]!.get('strong').text()).toBe('Copy & Paste')
  expect(steps[3]!.text()).toBe('Click Connect.')
  expect(dialog.find('[data-test="permission-list"]').exists()).toBe(false)
  expect(dialog.text()).toContain('Steam or Epic')
  expect(dialog.findAll('a').map((a) => a.attributes('href'))).toEqual(['https://account.arena.net/applications'])
  expect(steps[0]!.get('a').text()).toBe('your ArenaNet account')
  expect(dialog.get('a').attributes('rel')).toContain('noopener')
  expect(dialog.get('[data-test="help-create-safety"]').text()).toBe('Is my API key safe here?')

  await w.get('[data-test="help-close"]').trigger('click')
  expect(w.find('[data-test="help-dialog"]').exists()).toBe(false)
  expect(document.activeElement).toBe(w.get('[data-test="help-create"]').element)

  await w.get('[data-test="help-safety"]').trigger('click')
  expect(w.findAll('[data-test="help-dialog"]')).toHaveLength(1)
  const safety = w.get('[data-test="help-dialog"]')
  expect(safety.get('h3').text()).toBe('Your key is read-only')
  expect(safety.findAll('[data-test="safety-facts"] li').map((l) => l.text())).toEqual([
    'Cannot change your GW2 account: your characters, items and gold stay untouched.',
    'ArenaNet never shares your email, password or payment details through this API key.',
    'You control access: choose permissions for each key and revoke it at any time in your ArenaNet account.',
    'No separate sign-up: no email address or password needed here.',
    'Disconnect removes the key from this browser tab. Delete (planned) will remove your saved account data from our database.'
  ])
  const details = safety.get('[data-test="safety-details"]')
  expect(details.attributes('open')).toBeUndefined()
  expect(details.get('summary').text()).toBe('What this app stores')
  // Five owner-requested facts remain short; the account/storage explanation stays collapsed.
  const visible = safety.text().replace(details.text(), '')
  expect(visible.split(/\s+/).filter(Boolean).length).toBeLessThanOrEqual(120)
  expect(details.findAll('dt').map((label) => label.text())).toEqual([
    'Your API key', 'How we find your account', 'Game data we cache', 'Why we cache it'
  ])
  expect(details.text()).toContain('Saved only in this browser tab')
  expect(details.text()).toContain('Reloading keeps it; a fresh tab normally starts without it')
  expect(details.text()).toContain('Duplicated or restored tabs can keep the key')
  expect(details.text()).toContain('Our server receives it to read your GW2 data, but never saves it')
  expect(details.text()).toContain('GW2 account ID')
  expect(details.text()).toContain('ArenaNet checks your key')
  expect(details.text()).toContain("Another valid key for the same account retrieves the same data, within that key's permissions")
  expect(details.text()).toContain('A revoked key cannot reconnect')
  expect(details.text()).toContain('up to 15 minutes')
  expect(details.text()).not.toContain('Disconnect does not delete this cached data')
  expect(details.text()).toContain('GW2 limits API requests')
  expect(details.text()).toContain('crafting calculations are demanding')
  expect(details.findAll('dd li').map((row) => row.text())).toEqual([
    'Bank items, material quantities and unlocked recipes',
    'Character names and profiles, crafting levels, playtime and deaths',
    'Inventories, equipment and saved builds',
    'Account Luck'
  ])
  expect(details.text()).not.toContain('Replace key')
  expect(safety.findAll('.help-footer a').map((a) => [a.text(), a.attributes('href')])).toEqual([
    ['Manage your key at ArenaNet', 'https://account.arena.net/applications'],
    ['What ArenaNet says about API keys', 'https://help.guildwars2.com/hc/en-us/articles/230899908-Managing-Your-ArenaNet-Account']
  ])
  // The native Escape behaviour closes a modal dialog and fires `close`.
  ;(safety.element as HTMLDialogElement).close()
  await flushPromises()
  expect(w.find('[data-test="help-dialog"]').exists()).toBe(false)
  expect(document.activeElement).toBe(w.get('[data-test="help-safety"]').element)
  w.unmount()
})

it('opens safety help from the creation guide in the same modal and returns focus to the original trigger', async () => {
  const w = mount(ConnectionPanel, { attachTo: document.body })
  await w.get('[data-test="help-create"]').trigger('click')
  const dialog = w.get('[data-test="help-dialog"]').element
  await w.get('[data-test="help-create-safety"]').trigger('click')
  expect(w.findAll('[data-test="help-dialog"]')).toHaveLength(1)
  expect(w.get('[data-test="help-dialog"]').element).toBe(dialog)
  expect(w.get('[data-test="help-dialog"] h3').text()).toBe('Your key is read-only')
  expect(document.activeElement).toBe(w.get('[data-test="help-dialog"] h3').element)
  expect(paths).toEqual([])
  await w.get('[data-test="help-close"]').trigger('click')
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

it('a draft left in a closed panel is gone when it is opened again', async () => {
  const w = mount(ConnectionPanel)
  await input(w).setValue('typed')
  w.unmount()
  expect(value(mount(ConnectionPanel))).toBe('')
})

it('a failed own attempt survives the panel remount that its generation bump causes', async () => {
  let release!: (r: Response) => void
  handler = () => new Promise<Response>((resolve) => { release = resolve })
  const w = mount(ConnectionPanel)
  await input(w).setValue('BAD')
  await w.get('[data-test="toggle-key-visibility"]').trigger('click')
  await w.get('form').trigger('submit')
  w.unmount() // what App does when the attempt bumps the generation
  const next = mount(ConnectionPanel)
  release(json({ error: 'INVALID_CREDENTIAL', message: 'no' }, 401))
  await flushPromises()
  expect(value(next)).toBe('BAD')
  expect(input(next).attributes('type')).toBe('text')
})

it('a disconnect during a pending own attempt discards the draft', async () => {
  let release!: (r: Response) => void
  handler = (path) => (path === '/api/session' ? new Promise<Response>((r) => { release = r }) : new Response(null, { status: 204 }))
  const w = mount(ConnectionPanel)
  await input(w).setValue('KEY-A')
  await w.get('form').trigger('submit')
  await disconnect()
  release(proof())
  await flushPromises()
  expect(value(w)).toBe('')
  expect(input(w).attributes('type')).toBe('password')
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

it('keeps the help links, field and actions in separate rows of one card', () => {
  const w = mount(ConnectionPanel)
  const children = [...w.get('form').element.children].map((c) => c.getAttribute('data-test') ?? c.tagName)
  expect(children).toEqual(['LABEL', 'SPAN', 'key-actions'])
  expect(w.get('form').find('[data-test="help-create"]').exists()).toBe(false)
  expect(w.get('.connection-panel__field').find('a, [data-test^="help"]').exists()).toBe(false)
  expect(w.get('[data-test="key-actions"]').findAll('button').map((b) => b.text())).toEqual(['Connect'])
})
