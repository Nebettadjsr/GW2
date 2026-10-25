import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  STORAGE_KEY,
  connectKey,
  connection,
  disconnect,
  hasStoredKey,
  installAccountSession,
  resetAccountSession,
  restoreConnection,
  retryConnection
} from '../accountSession'
import { getJson, installAccountTransport } from '../http'

/**
 * Remaining connection restore/rejection/outage and in-flight switch branches. `fetch` is scripted
 * per call; stale answers are controlled with explicit promises, never real waits.
 */
let calls: { path: string; headers: Record<string, string> }[]
let handler: (path: string, headers: Record<string, string>) => Response | Promise<Response>

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
const proof = (context: string, expiresAt = '2099-01-01T00:00:00Z'): Response =>
  json({ accessContext: context, expiresAt, grants: ['account'] })
const error = (code: string, status: number): Response => json({ error: code, message: code }, status)

beforeEach(() => {
  calls = []
  resetAccountSession()
  installAccountSession()
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    const headers = { ...(init?.headers as Record<string, string>) }
    calls.push({ path: input, headers })
    return handler(input, headers)
  }))
})

afterEach(() => {
  installAccountTransport(null)
  resetAccountSession()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('key storage', () => {
  it('keeps the key for this page when sessionStorage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked') })
    handler = () => proof('ctx')
    expect(await connectKey('KEY-A')).toBe(true)
    expect(hasStoredKey()).toBe(true)
    await disconnect()
    expect(hasStoredKey()).toBe(false)
  })

  it('reports no stored key when sessionStorage cannot be read', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    expect(hasStoredKey()).toBe(false)
  })

  it('rejects an over-long key without calling the backend', async () => {
    expect(await connectKey('K'.repeat(201))).toBe(false)
    expect(connection.error).toBe('That does not look like a GW2 API key.')
    expect(calls).toHaveLength(0)
  })
})

describe('restore and retry outcomes', () => {
  it('does nothing when no key is retained or the phase is not disconnected', async () => {
    await restoreConnection()
    await retryConnection()
    expect(calls).toHaveLength(0)
    expect(connection.phase).toBe('disconnected')
  })

  it('reports an unreachable backend on restore, keeps the key, and a retry connects', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')
    handler = () => { throw new TypeError('offline') }
    await restoreConnection()
    expect(connection.phase).toBe('unavailable')
    expect(connection.errorIsDefinitive).toBe(false)
    expect(connection.error).toBe('The backend could not be reached. The key is kept; try again.')
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('KEY-A')

    handler = () => proof('ctx')
    await retryConnection()
    expect(connection.phase).toBe('connected')
    expect(connection.error).toBeNull()
    expect(calls).toHaveLength(2)
  })

  it('shows the upstream-unavailable wording on restore', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')
    handler = () => error('PROOF_UNAVAILABLE', 503)
    await restoreConnection()
    expect(connection.phase).toBe('unavailable')
    expect(connection.error).toContain('Guild Wars 2 could not check the key right now')
  })

  it('names an unclassified backend failure by code and message', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')
    handler = () => error('SOMETHING_ELSE', 500)
    await restoreConnection()
    expect(connection.error).toBe('SOMETHING_ELSE: SOMETHING_ELSE')
  })

  it('treats a proof body without an access context as an unexpected answer', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')
    handler = () => json({ nothing: true })
    await restoreConnection()
    expect(connection.phase).toBe('unavailable')
    expect(connection.error).toBe('UNEXPECTED_STATUS: The backend answered the key check unexpectedly.')
  })

  it('accepts a proof that carries no grants', async () => {
    handler = () => json({ accessContext: 'ctx', expiresAt: '2099-01-01T00:00:00Z' })
    expect(await connectKey('KEY-A')).toBe(true)
    expect(connection.grants).toEqual([])
  })

  it('shares one proof between concurrent account requests while restoring', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')
    let release: (r: Response) => void = () => undefined
    handler = (path) => path === '/api/session'
      ? new Promise<Response>((resolve) => { release = resolve })
      : json({ ok: true })
    const restoring = restoreConnection()
    await vi.waitFor(() => expect(connection.phase).toBe('restoring'))
    const first = getJson('/account/bank')
    const second = getJson('/account/materials')
    await vi.waitFor(() => expect(calls.filter((c) => c.path === '/api/session')).toHaveLength(1))
    release(proof('ctx'))
    await Promise.all([restoring, first, second])
    expect(calls.filter((c) => c.path === '/api/session')).toHaveLength(1)
    expect(connection.phase).toBe('connected')
  })

  it('rejects an account request with KEY_REQUIRED when the key vanished', async () => {
    connection.phase = 'unavailable'
    await expect(getJson('/account/bank')).rejects.toMatchObject({ code: 'KEY_REQUIRED' })
    expect(calls).toHaveLength(0)
  })

  it('retries from an unavailable phase when an account request starts', async () => {
    handler = () => { throw new TypeError('offline') }
    expect(await connectKey('KEY-A')).toBe(false)
    expect(connection.phase).toBe('unavailable')
    handler = (path) => (path === '/api/session' ? proof('ctx') : json({ ok: true }))
    await expect(getJson('/account/bank')).resolves.toEqual({ ok: true })
    expect(connection.phase).toBe('connected')
  })
})

describe('renewal failures and switching while in flight', () => {
  it('keeps serving a live lease when a renewal after a 401 fails transiently, then reports the outage', async () => {
    handler = () => proof('ctx')
    await connectKey('KEY-A')
    handler = (path) => (path === '/api/session' ? error('PROOF_UNAVAILABLE', 503) : error('SESSION_REQUIRED', 401))
    await expect(getJson('/account/bank')).rejects.toMatchObject({ code: 'PROOF_UNAVAILABLE' })
    expect(connection.phase).toBe('unavailable')
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('KEY-A')
  })

  it('forgets the key when a 401 renewal is definitively rejected', async () => {
    handler = () => proof('ctx')
    await connectKey('KEY-A')
    handler = (path) => (path === '/api/session' ? error('INVALID_CREDENTIAL', 401) : error('SESSION_REQUIRED', 401))
    await expect(getJson('/account/bank')).rejects.toMatchObject({ code: 'INVALID_CREDENTIAL' })
    expect(connection.phase).toBe('disconnected')
    expect(connection.errorIsDefinitive).toBe(true)
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('does not renew for an unrelated refusal', async () => {
    handler = () => proof('ctx')
    await connectKey('KEY-A')
    handler = () => error('SOMETHING_ELSE', 500)
    await expect(getJson('/account/bank')).rejects.toMatchObject({ code: 'SOMETHING_ELSE' })
    expect(calls.filter((c) => c.path === '/api/session')).toHaveLength(1)
    expect(connection.phase).toBe('connected')
  })

  it('does not renew for a refusal that belongs to a replaced account', async () => {
    handler = () => proof('ctx-a')
    await connectKey('KEY-A')
    let release: (r: Response) => void = () => undefined
    handler = (path) => path === '/api/session'
      ? proof('ctx-b')
      : new Promise<Response>((resolve) => { release = resolve })
    const pending = getJson('/account/bank')
    await vi.waitFor(() => expect(calls.some((c) => c.path === '/api/account/bank')).toBe(true))
    await connectKey('KEY-B')
    release(error('SESSION_REQUIRED', 401))
    await expect(pending).rejects.toMatchObject({ code: 'ACCOUNT_CHANGED' })
    expect(connection.phase).toBe('connected')
    expect(calls.filter((c) => c.path === '/api/session')).toHaveLength(2)
  })

  it('discards a stale restore outage after a different account connected', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')
    let release: (r: Response) => void = () => undefined
    handler = () => new Promise<Response>((resolve) => { release = resolve })
    const restoring = restoreConnection()
    await vi.waitFor(() => expect(calls).toHaveLength(1))
    expect(connection.phase).toBe('restoring')
    handler = () => proof('ctx-b')
    await connectKey('KEY-B')
    release(error('PROOF_UNAVAILABLE', 503))
    await restoring
    expect(connection.phase).toBe('connected')
    expect(connection.error).toBeNull()
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('KEY-B')
  })

  it('discards a stale replacement outage after a disconnect', async () => {
    let release: (r: Response) => void = () => undefined
    handler = () => new Promise<Response>((resolve) => { release = resolve })
    const connecting = connectKey('KEY-A')
    await vi.waitFor(() => expect(calls).toHaveLength(1))
    await disconnect()
    release(error('INVALID_CREDENTIAL', 401))
    expect(await connecting).toBe(false)
    expect(connection.phase).toBe('disconnected')
    expect(connection.error).toBeNull()
  })

  it('refuses a request that waited for a proof which the user then disconnected', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')
    let release: (r: Response) => void = () => undefined
    handler = () => new Promise<Response>((resolve) => { release = resolve })
    const restoring = restoreConnection()
    await vi.waitFor(() => expect(connection.phase).toBe('restoring'))
    const request = getJson('/account/bank')
    await vi.waitFor(() => expect(calls).toHaveLength(1))
    await disconnect()
    release(proof('ctx'))
    await restoring
    await expect(request).rejects.toMatchObject({ code: 'KEY_REQUIRED' })
    expect(connection.phase).toBe('disconnected')
    expect(calls.filter((c) => c.path === '/api/account/bank')).toHaveLength(0)
  })

  it('treats a 401 on logout as an ended session', async () => {
    handler = () => proof('ctx')
    await connectKey('KEY-A')
    handler = () => error('SESSION_REQUIRED', 401)
    await disconnect()
    expect(connection.logoutNotice).toBeNull()
  })

  it('reports a logout the server refused with a server error', async () => {
    handler = () => proof('ctx')
    await connectKey('KEY-A')
    handler = () => error('BOOM', 500)
    await disconnect()
    expect(connection.logoutNotice).toContain('could not be ended')
  })

  it('disconnecting with no lease makes no logout call', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')
    await disconnect()
    expect(calls).toHaveLength(0)
    expect(hasStoredKey()).toBe(false)
  })
})
