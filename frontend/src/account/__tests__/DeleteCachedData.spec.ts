import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { STORAGE_KEY, connectKey, connection, installAccountSession, resetAccountSession } from '@/api/accountSession'
import { installAccountTransport } from '@/api/http'
import ConnectionPanel from '../ConnectionPanel.vue'
import { clearDraft } from '../keyDraft'

const GUID = '33333333-3333-3333-3333-333333333333'
let deletes: number
let answer: () => Response | Promise<Response>

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(async () => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    if (!this.hasAttribute('open')) return
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
  clearDraft()
  deletes = 0
  answer = () => new Response(null, { status: 204 })
  resetAccountSession()
  installAccountSession()
  vi.stubGlobal('fetch', vi.fn(async (_path: string, init?: RequestInit) => {
    if (init?.method === 'DELETE') { deletes += 1; return answer() }
    return json({ accountId: GUID, accessContext: 'ctx', expiresAt: '2099-01-01T00:00:00Z', grants: [] })
  }))
  await connectKey('KEY')
})
afterEach(() => {
  installAccountTransport(null)
  resetAccountSession()
  vi.unstubAllGlobals()
})

const mountPanel = () => mount(ConnectionPanel, { attachTo: document.body })
const open = async (w: ReturnType<typeof mountPanel>) => {
  await w.get('[data-test="delete-cached-data"]').trigger('click')
  await flushPromises()
}

it('shows the action only for a verified connected account, apart from Disconnect', async () => {
  const w = mountPanel()
  expect(w.get('[data-test="delete-cached-data"]').text()).toBe('Delete cached data')
  expect(w.get('[data-test="disconnect"]').text()).toBe('Disconnect')
  connection.accountId = null
  await flushPromises()
  expect(w.find('[data-test="delete-cached-data"]').exists()).toBe(false)
  w.unmount()
})

it('opens without a request, focuses the heading and cancels via Cancel, Escape and backdrop', async () => {
  const w = mountPanel()
  const trigger = w.get('[data-test="delete-cached-data"]').element
  await open(w)
  const dialog = w.get('[data-test="delete-dialog"]')
  expect(dialog.text()).toContain('Delete cached account data?')
  expect(dialog.text()).toContain('It does not affect your Guild Wars 2 account.')
  expect(document.activeElement).toBe(dialog.get('h3').element)
  expect(deletes).toBe(0)

  await w.get('[data-test="delete-cancel"]').trigger('click')
  expect(w.find('[data-test="delete-dialog"]').exists()).toBe(false)
  expect(document.activeElement).toBe(trigger)

  await open(w)
  const esc = new Event('cancel', { cancelable: true })
  w.get('[data-test="delete-dialog"]').element.dispatchEvent(esc)
  expect(esc.defaultPrevented).toBe(false)
  await w.get('[data-test="delete-dialog"]').trigger('click')
  expect(w.find('[data-test="delete-dialog"]').exists()).toBe(false)
  expect(deletes).toBe(0)
  w.unmount()
})

it('traps Tab between Cancel and Delete', async () => {
  const w = mountPanel()
  await open(w)
  const cancel = w.get('[data-test="delete-cancel"]').element as HTMLElement
  const confirm = w.get('[data-test="delete-confirm"]').element as HTMLElement
  confirm.focus()
  await w.get('[data-test="delete-dialog"]').trigger('keydown', { key: 'Tab' })
  expect(document.activeElement).toBe(cancel)
  await w.get('[data-test="delete-dialog"]').trigger('keydown', { key: 'Tab', shiftKey: true })
  expect(document.activeElement).toBe(confirm)
  w.unmount()
})

it('issues one request while busy, blocks dismissal, then shows the notice only after 204', async () => {
  let release: (r: Response) => void = () => undefined
  answer = () => new Promise<Response>((r) => { release = r })
  const w = mountPanel()
  await open(w)
  await w.get('[data-test="delete-confirm"]').trigger('click')
  await w.get('[data-test="delete-confirm"]').trigger('click')
  await flushPromises()
  expect(deletes).toBe(1)
  expect((w.get('[data-test="delete-cancel"]').element as HTMLButtonElement).disabled).toBe(true)
  const esc = new Event('cancel', { cancelable: true })
  w.get('[data-test="delete-dialog"]').element.dispatchEvent(esc)
  expect(esc.defaultPrevented).toBe(true)
  await w.get('[data-test="delete-dialog"]').trigger('click')
  expect(w.find('[data-test="delete-dialog"]').exists()).toBe(true)
  expect(w.find('[data-test="deletion-notice"]').exists()).toBe(false)

  release(new Response(null, { status: 204 }))
  await flushPromises()
  expect(w.find('[data-test="delete-dialog"]').exists()).toBe(false)
  expect(w.get('[data-test="deletion-notice"]').text()).toBe(
    'Cached account data was deleted. Your Guild Wars 2 account was not changed.')
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
  w.unmount()
})

it.each([
  ['409', () => json({ error: 'ACCOUNT_WORK_IN_PROGRESS', message: 'x' }, 409), 'Account work is still finishing'],
  ['503', () => json({ error: 'DATA_STORE_UNAVAILABLE', message: 'x' }, 503), 'could not be deleted right now']
])('%s keeps the modal open with an announced error and the connection', async (_n, response, text) => {
  answer = response
  const w = mountPanel()
  await open(w)
  await w.get('[data-test="delete-confirm"]').trigger('click')
  await flushPromises()
  const error = w.get('[data-test="delete-error"]')
  expect(error.attributes('role')).toBe('alert')
  expect(error.text()).toContain(text)
  expect(connection.phase).toBe('connected')
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('KEY')
  expect(w.find('[data-test="deletion-notice"]').exists()).toBe(false)
  // A retry needs a fresh explicit confirmation; nothing is scheduled.
  await flushPromises()
  expect(deletes).toBe(1)
  w.unmount()
})
