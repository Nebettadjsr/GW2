import { afterEach, describe, expect, it, vi } from 'vitest'
import { getJson, getStateJson } from '../http'

const body = (status: number, b: unknown) => new Response(JSON.stringify(b), { status, headers: { 'Content-Type': 'application/json' } })
afterEach(() => vi.unstubAllGlobals())

describe('getStateJson per-request state bodies', () => {
  it.each([403, 404, 503])('returns a structured %i state body as data', async (status) => {
    vi.stubGlobal('fetch', vi.fn(async () => body(status, { state: 'UNAVAILABLE', reason: 'R' })))
    await expect(getStateJson('/account/characters')).resolves.toMatchObject({ state: 'UNAVAILABLE' })
  })

  it('still throws for an unstructured error body', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('oops', { status: 500 })))
    await expect(getStateJson('/account/characters')).rejects.toMatchObject({ code: 'UNEXPECTED_STATUS' })
  })

  it('does not leak state-body acceptance to an overlapping ordinary request on the same path', async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => { release = r })
    vi.stubGlobal('fetch', vi.fn(async () => { await gate; return body(503, { state: 'UNAVAILABLE' }) }))
    const stateful = getStateJson('/account/characters')
    const ordinary = getJson('/account/characters')
    const ordinaryResult = ordinary.then(() => 'resolved', () => 'threw')
    release()
    await expect(stateful).resolves.toMatchObject({ state: 'UNAVAILABLE' })
    expect(await ordinaryResult).toBe('threw')
    // After the stateful call finished, ordinary calls keep throwing.
    vi.stubGlobal('fetch', vi.fn(async () => body(503, { state: 'UNAVAILABLE' })))
    await expect(getJson('/account/characters')).rejects.toBeInstanceOf(Error)
  })
})
