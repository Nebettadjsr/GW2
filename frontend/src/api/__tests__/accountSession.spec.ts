import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  STORAGE_KEY,
  connectKey,
  connection,
  disconnect,
  installAccountSession,
  resetAccountSession,
  restoreConnection
} from '../accountSession'
import { getJson, installAccountTransport, postJson } from '../http'

/**
 * Key retention, restoration, replacement/disconnect, late old-account replies and the invalid-key
 * versus transient-failure split (STORY-WEB-035). `fetch` is scripted per URL; every assertion is
 * about what the browser sent and what state it ended in.
 */
interface Call {
  path: string
  method: string
  headers: Record<string, string>
}

let calls: Call[]
let handler: (call: Call) => Response | Promise<Response>

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function proof(context: string, expiresAt = '2099-01-01T00:00:00Z'): Response {
  return json({ accessContext: context, expiresAt, grants: ['account'] })
}

const INVALID = (): Response => json({ error: 'INVALID_CREDENTIAL', message: 'The GW2 API key could not be validated' }, 401)
const UNAVAILABLE = (): Response => json({ error: 'PROOF_UNAVAILABLE', message: 'later' }, 503)

beforeEach(() => {
  calls = []
  resetAccountSession()
  installAccountSession()
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    const call = { path: input, method: init?.method ?? 'GET', headers: { ...(init?.headers as Record<string, string>) } }
    calls.push(call)
    return handler(call)
  }))
})

afterEach(() => {
  installAccountTransport(null)
  resetAccountSession()
  vi.unstubAllGlobals()
})

describe('key entry and retention', () => {
  it('proves the key through the backend and keeps it in sessionStorage', async () => {
    handler = () => proof('ctx-a')

    expect(await connectKey('  KEY-A  ')).toBe(true)

    expect(connection.phase).toBe('connected')
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('KEY-A')
    expect(calls).toHaveLength(1)
    expect(calls[0]).toMatchObject({ path: '/api/session', method: 'POST' })
    expect(calls[0]?.headers['X-GW2-API-Key']).toBe('KEY-A')
  })

  it('rejects an empty or malformed entry without calling the backend', async () => {
    handler = () => proof('x')
    expect(await connectKey('   ')).toBe(false)
    expect(await connectKey('two words')).toBe(false)
    expect(calls).toHaveLength(0)
    expect(connection.error).not.toBeNull()
  })

  it('restores a retained key by proving it before any account data is requested', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')
    handler = (call) => (call.path === '/api/session' ? proof('ctx-a') : json({ ok: true }))

    await restoreConnection()
    expect(connection.phase).toBe('connected')
    await getJson('/account/bank')

    expect(calls.map((c) => c.path)).toEqual(['/api/session', '/api/account/bank'])
    expect(calls[1]?.headers['X-Account-Context']).toBe('ctx-a')
    expect(calls[1]?.headers['X-GW2-API-Key']).toBeUndefined()
  })

  it('sends the key only on requests that can admit upstream work, never in the URL', async () => {
    handler = (call) => (call.path === '/api/session' ? proof('ctx-a') : json({ ok: true }))
    await connectKey('KEY-A')

    await postJson('/sync/account', {})
    await postJson('/crafting/profit', {})
    await getJson('/account/materials')

    expect(calls.slice(1).map((c) => [c.path, c.headers['X-GW2-API-Key'] ?? null])).toEqual([
      ['/api/sync/account', 'KEY-A'],
      ['/api/crafting/profit', 'KEY-A'],
      ['/api/account/materials', null]
    ])
    expect(calls.every((c) => !c.path.includes('KEY-A'))).toBe(true)
  })

  it('refuses account requests, without calling the backend, while no key is connected', async () => {
    handler = () => json({})
    await expect(getJson('/account/bank')).rejects.toMatchObject({ code: 'KEY_REQUIRED' })
    expect(calls).toHaveLength(0)
  })
})

describe('invalid key versus transient failure', () => {
  it('removes a definitively rejected stored key and asks for entry again', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'BAD')
    handler = () => INVALID()

    await restoreConnection()

    expect(connection.phase).toBe('disconnected')
    expect(connection.errorIsDefinitive).toBe(true)
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('keeps the key and reports honestly when the upstream check is unavailable', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'KEY-A')
    handler = () => UNAVAILABLE()

    await restoreConnection()

    expect(connection.phase).toBe('unavailable')
    expect(connection.errorIsDefinitive).toBe(false)
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('KEY-A')
  })

  it('discards the key when an account request is answered with a definitive rejection', async () => {
    handler = (call) => (call.path === '/api/session' ? proof('ctx-a') : INVALID())
    await connectKey('KEY-A')

    await expect(postJson('/sync/account', {})).rejects.toMatchObject({ code: 'INVALID_CREDENTIAL' })

    expect(connection.phase).toBe('disconnected')
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('keeps a connected account when a replacement key is refused', async () => {
    handler = () => proof('ctx-a')
    await connectKey('KEY-A')
    const generation = connection.generation
    handler = () => INVALID()

    expect(await connectKey('KEY-B')).toBe(false)

    expect(connection.phase).toBe('connected')
    expect(connection.generation).toBe(generation)
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('KEY-A')
  })
})

describe('lease renewal and context mismatch', () => {
  it('renews with the retained key when the backend reports the session gone, then retries once', async () => {
    let session = 0
    let reads = 0
    handler = (call) => {
      if (call.path === '/api/session') return proof(`ctx-${++session}`)
      reads += 1
      return reads === 1
        ? json({ error: 'SESSION_REQUIRED', message: 'Prove' }, 401)
        : json({ value: 7 })
    }
    await connectKey('KEY-A')
    const generation = connection.generation

    await expect(getJson('/account/bank')).resolves.toEqual({ value: 7 })

    expect(calls.map((c) => c.path)).toEqual(['/api/session', '/api/account/bank', '/api/session', '/api/account/bank'])
    expect(calls[3]?.headers['X-Account-Context']).toBe('ctx-2')
    // Same key, same account: no reset of the screens.
    expect(connection.generation).toBe(generation)
  })

  it('reconnects once when another tab changed the shared cookie, and reports a persistent refusal', async () => {
    let session = 0
    handler = (call) => (call.path === '/api/session'
      ? proof(`ctx-${++session}`)
      : json({ error: 'ACCOUNT_ACCESS_DENIED', message: 'no' }, 403))
    await connectKey('KEY-A')

    await expect(getJson('/account/bank')).rejects.toMatchObject({ code: 'ACCOUNT_ACCESS_DENIED' })

    expect(calls.filter((c) => c.path === '/api/account/bank')).toHaveLength(2)
  })

  it('renews a lease close to expiry before sending the next request', async () => {
    let session = 0
    handler = (call) => (call.path === '/api/session'
      ? proof(`ctx-${++session}`, session === 1 ? new Date(Date.now() + 10_000).toISOString() : '2099-01-01T00:00:00Z')
      : json({}))
    await connectKey('KEY-A')

    await getJson('/account/bank')

    expect(calls.map((c) => c.path)).toEqual(['/api/session', '/api/session', '/api/account/bank'])
  })
})

describe('replacement and disconnect', () => {
  it('changes the generation on replacement and drops a late reply of the previous account', async () => {
    let releaseOld: (response: Response) => void = () => undefined
    handler = (call) => {
      if (call.path === '/api/session') return proof(call.headers['X-GW2-API-Key'] === 'KEY-A' ? 'ctx-a' : 'ctx-b')
      return new Promise<Response>((resolve) => { releaseOld = resolve })
    }
    await connectKey('KEY-A')
    const before = connection.generation

    const late = getJson('/account/bank')
    const lateOutcome = late.then(() => 'delivered', (error: { code: string }) => error.code)
    await vi.waitFor(() => expect(calls.some((c) => c.path === '/api/account/bank')).toBe(true))

    expect(await connectKey('KEY-B')).toBe(true)
    expect(connection.generation).toBeGreaterThan(before)
    releaseOld(json({ owner: 'A' }))

    expect(await lateOutcome).toBe('ACCOUNT_CHANGED')
  })

  it('clears local state and the stored key even when the server logout cannot be reached', async () => {
    handler = (call) => {
      if (call.path === '/api/session') return proof('ctx-a')
      throw new TypeError('offline')
    }
    await connectKey('KEY-A')
    const generation = connection.generation

    await disconnect()

    expect(connection.phase).toBe('disconnected')
    expect(connection.generation).toBeGreaterThan(generation)
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(calls.at(-1)).toMatchObject({ path: '/api/session/logout', method: 'POST' })
    expect(calls.at(-1)?.headers['X-Account-Context']).toBe('ctx-a')
    expect(connection.logoutNotice).toMatch(/could not be ended/)
  })

  it('reports no logout problem when the server ended the session', async () => {
    handler = (call) => (call.path === '/api/session' ? proof('ctx-a') : new Response(null, { status: 204 }))
    await connectKey('KEY-A')

    await disconnect()

    expect(connection.logoutNotice).toBeNull()
  })
})
