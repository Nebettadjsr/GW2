import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  STORAGE_KEY,
  connectKey,
  connection,
  deleteAccountData,
  installAccountSession,
  resetAccountSession
} from '../accountSession'
import { getJson, installAccountTransport } from '../http'
import { DELETION_CHANNEL } from '../accountDeletionSignal'
import { useMaterialProtection, resetMaterialProtection, PREFERENCE_KEY_PREFIX } from '@/materials/materialProtectionState'

const A = '11111111-1111-1111-1111-111111111111'
const B = '22222222-2222-2222-2222-222222222222'

interface Call { path: string; method: string }
let calls: Call[]
let handler: (call: Call) => Response
let account = A

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const proof = (): Response => json({ accountId: account, accessContext: 'ctx', expiresAt: '2099-01-01T00:00:00Z', grants: ['account'] })
const deleted = (): Response => json({ error: 'ACCOUNT_DATA_DELETED', message: 'deleted' }, 401)

beforeEach(() => {
  calls = []
  account = A
  window.localStorage.clear()
  resetMaterialProtection()
  resetAccountSession()
  installAccountSession()
  handler = (call) => (call.path.endsWith('/session') ? proof() : new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    const call = { path: input, method: init?.method ?? 'GET' }
    calls.push(call)
    return handler(call)
  }))
})
afterEach(() => {
  installAccountTransport(null)
  resetAccountSession()
  resetMaterialProtection()
  vi.unstubAllGlobals()
})

describe('deleteAccountData', () => {
  it('on 204 removes only this GUID preference, resets locally and makes no logout request', async () => {
    window.localStorage.setItem(PREFERENCE_KEY_PREFIX + B, '{"version":1,"protectedItemIds":[2],"enabled":true}')
    await connectKey('KEY')
    const mp = useMaterialProtection()
    mp.setPending([5])
    mp.commit()
    expect(window.localStorage.getItem(PREFERENCE_KEY_PREFIX + A)).not.toBeNull()

    expect(await deleteAccountData()).toBe('deleted')

    expect(calls.filter((c) => c.method === 'DELETE')).toHaveLength(1)
    expect(calls.some((c) => c.path.includes('logout'))).toBe(false)
    expect(window.localStorage.getItem(PREFERENCE_KEY_PREFIX + A)).toBeNull()
    expect(window.localStorage.getItem(PREFERENCE_KEY_PREFIX + B)).not.toBeNull()
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(connection.phase).toBe('disconnected')
    expect(connection.accountId).toBeNull()
    expect(connection.grants).toEqual([])
    expect(connection.deletionNotice).toContain('Your Guild Wars 2 account was not changed')
    // The in-memory shadow is gone too: reconnecting restores nothing.
    await connectKey('KEY')
    expect(useMaterialProtection().state.committed).toEqual([])
    expect(connection.deletionNotice).toBeNull()
  })

  it.each([
    ['409', () => json({ error: 'ACCOUNT_WORK_IN_PROGRESS', message: 'x' }, 409), 'work-in-progress'],
    ['503', () => json({ error: 'DATA_STORE_UNAVAILABLE', message: 'x' }, 503), 'failed'],
    ['network', () => { throw new TypeError('offline') }, 'failed']
  ])('%s preserves key, session, preferences and broadcasts nothing', async (_n, answer, expected) => {
    await connectKey('KEY')
    const mp = useMaterialProtection()
    mp.setPending([5])
    mp.commit()
    const posted = vi.fn()
    const channel = new BroadcastChannel(DELETION_CHANNEL)
    channel.onmessage = posted
    handler = (call) => (call.method === 'DELETE' ? answer() : proof())

    expect(await deleteAccountData()).toBe(expected)
    await new Promise((r) => setTimeout(r, 20))
    channel.close()

    expect(posted).not.toHaveBeenCalled()
    expect(connection.phase).toBe('connected')
    expect(connection.accountId).toBe(A)
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('KEY')
    expect(window.localStorage.getItem(PREFERENCE_KEY_PREFIX + A)).not.toBeNull()
    expect(connection.deletionNotice).toBeNull()
  })

  it('broadcasts one GUID-only signal after 204', async () => {
    await connectKey('KEY')
    const messages: unknown[] = []
    const channel = new BroadcastChannel(DELETION_CHANNEL)
    channel.onmessage = (e) => messages.push(e.data)
    await deleteAccountData()
    await new Promise((r) => setTimeout(r, 20))
    channel.close()
    expect(messages).toEqual([{ type: 'account-data-deleted', accountId: A }])
    expect(JSON.stringify(messages)).not.toMatch(/KEY|ctx/)
  })
})

describe('ACCOUNT_DATA_DELETED recovery and cross-tab invalidation', () => {
  beforeEach(() => installAccountTransport(null))

  it('resets locally without proof or retry, and an explicit connect still works', async () => {
    installAccountSession()
    await connectKey('KEY')
    calls.length = 0
    handler = (call) => (call.path.includes('/account/') ? deleted() : proof())

    await expect(getJson('/account/inventory')).rejects.toMatchObject({ code: 'ACCOUNT_DATA_DELETED' })

    expect(calls.map((c) => c.path)).toEqual(['/api/account/inventory'])
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(connection.phase).toBe('disconnected')
    expect(connection.deletionNotice).toContain('Connect your API key again')
    expect(await connectKey('KEY')).toBe(true)
    expect(connection.phase).toBe('connected')
  })

  it('a late response of the previous generation is rejected after deletion', async () => {
    installAccountSession()
    await connectKey('KEY')
    let release: (r: Response) => void = () => undefined
    handler = (call) => (call.method === 'DELETE' ? new Response(null, { status: 204 }) : proof())
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
      if (input.includes('/account/inventory')) return new Promise<Response>((r) => { release = r })
      return handler({ path: input, method: init?.method ?? 'GET' })
    }))
    const late = getJson('/account/inventory')
    await vi.waitFor(() => expect(release).not.toBeNull())
    await new Promise((r) => setTimeout(r, 0))
    await deleteAccountData()
    release(json({ items: [] }))
    await expect(late).rejects.toMatchObject({ code: 'ACCOUNT_CHANGED' })
  })

  it('a tab for the same GUID clears without proof; a different GUID is untouched', async () => {
    await connectKey('KEY')
    calls.length = 0
    const sender = new BroadcastChannel(DELETION_CHANNEL)
    sender.postMessage({ type: 'account-data-deleted', accountId: B })
    await new Promise((r) => setTimeout(r, 20))
    expect(connection.phase).toBe('connected')

    sender.postMessage({ type: 'account-data-deleted', accountId: A })
    await vi.waitFor(() => expect(connection.phase).toBe('disconnected'))
    sender.close()
    expect(calls).toEqual([])
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(connection.deletionNotice).not.toBeNull()
  })
})
