import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiRequestError } from '../http'
import { systemApi } from '../systemApi'

function stubFetch(status: number, body: unknown): ReturnType<typeof vi.fn> {
  const stub = vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' }
  })))
  vi.stubGlobal('fetch', stub)
  return stub
}

afterEach(() => vi.unstubAllGlobals())

describe('systemApi', () => {
  it('readsTheGenericStatusWithoutAnyDetailedField', async () => {
    const stub = stubFetch(200, { globalHealth: 'OK', priceHealth: 'NOT_RUN' })
    const status = await systemApi.readStatus()
    expect(stub.mock.calls[0]?.[0]).toBe('/api/system/status')
    expect(status.globalHealth).toBe('OK')
    expect(status.lastFailure).toBeUndefined()
  })

  it('readsOwnerDetailsFromADistinctRouteAndSurfacesDenialAsAnError', async () => {
    const stub = stubFetch(403, { error: 'OWNER_REQUIRED', message: 'restricted' })
    await expect(systemApi.readOwnerDetails()).rejects.toBeInstanceOf(ApiRequestError)
    expect(stub.mock.calls[0]?.[0]).toBe('/api/system/status/details')
  })
})
