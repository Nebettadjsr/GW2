import { afterEach, describe, expect, it, vi } from 'vitest'
import { installAccountTransport } from '../http'
import { syncApi } from '../syncApi'

/** Full-catalog triggers use the session/context transport, post an empty body and never carry the key. */
afterEach(() => { installAccountTransport(null); vi.unstubAllGlobals() })

describe('full catalog triggers', () => {
  it.each([['items'], ['recipes'], ['trading-post']] as const)('posts %s with an empty body under the session transport and without the key', async (catalog) => {
    const begin = vi.fn(async () => ({ generation: 1, headers: { 'X-Account-Context': 'ctx' } }))
    installAccountTransport({ begin, isCurrent: () => true, recover: async () => false })
    const fetchStub = vi.fn(async () => new Response(JSON.stringify({ taskId: 't', operation: 'X', statusUrl: '/api/sync/tasks/t' }), {
      status: 202, headers: { 'Content-Type': 'application/json' }
    }))
    vi.stubGlobal('fetch', fetchStub)

    await syncApi.startFullCatalog(catalog)

    expect(begin).toHaveBeenCalledWith(false)
    const [path, init] = fetchStub.mock.calls[0] as unknown as [string, RequestInit]
    expect(path).toBe(`/api/sync/full/${catalog}`)
    expect(init.method).toBe('POST')
    expect(init.body).toBe('{}')
    expect((init.headers as Record<string, string>)['X-Account-Context']).toBe('ctx')
  })
})
