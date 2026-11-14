import { afterEach, describe, expect, it, vi } from 'vitest'
import { ACCOUNT_CHANGED_CODE, ApiRequestError, installAccountTransport, type AccountTransport } from '../http'
import { systemApi } from '../systemApi'

function stubFetch(status: number, body: unknown): ReturnType<typeof vi.fn> {
  const stub = vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' }
  })))
  vi.stubGlobal('fetch', stub)
  return stub
}

afterEach(() => { vi.unstubAllGlobals(); installAccountTransport(null) })

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

  const denied = () => new Response(JSON.stringify({ error: 'SESSION_REQUIRED', message: 'prove' }), { status: 401 })
  const transport = (over: Partial<AccountTransport> = {}): AccountTransport => ({
    begin: async () => ({ generation: 1, headers: { 'X-T': '1' } }), isCurrent: () => true,
    recover: async () => true, ...over
  })

  it('recoversAnExportSessionRequiredOnceThroughTheAccountTransport', async () => {
    const stub = vi.fn().mockResolvedValueOnce(denied()).mockResolvedValueOnce(new Response('a,b\n', { status: 200, headers: { 'Content-Disposition': 'attachment; filename="activity-day-2026-11-13.csv"' } }))
    vi.stubGlobal('fetch', stub)
    const recover = vi.fn(async () => true)
    installAccountTransport(transport({ recover }))
    await expect(systemApi.downloadOperationsPeriod('id 1')).resolves.toEqual({ text: 'a,b\n', filename: 'activity-day-2026-11-13.csv' })
    expect(recover).toHaveBeenCalledTimes(1)
    expect(stub).toHaveBeenCalledTimes(2)
    expect(String(stub.mock.calls[0]?.[0])).toBe('/api/system/operations/download?selection=id+1')
  })

  it('doesNotRetryAnExportMoreThanOnceOrWhenRecoveryDeclines', async () => {
    const stub = vi.fn(() => Promise.resolve(denied()))
    vi.stubGlobal('fetch', stub)
    installAccountTransport(transport())
    await expect(systemApi.downloadOperationsPeriod('d1')).rejects.toMatchObject({ status: 401 })
    expect(stub).toHaveBeenCalledTimes(2)
    stub.mockClear()
    installAccountTransport(transport({ recover: async () => false }))
    await expect(systemApi.downloadOperationsPeriod('d1')).rejects.toMatchObject({ status: 401 })
    expect(stub).toHaveBeenCalledTimes(1)
  })

  it('rejectsAnOldAccountDenialAsAccountChangedWithoutRecovering', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(denied())))
    const recover = vi.fn(async () => true)
    installAccountTransport(transport({ isCurrent: () => false, recover }))
    await expect(systemApi.downloadOperationsPeriod('d1')).rejects.toMatchObject({ code: ACCOUNT_CHANGED_CODE })
    expect(recover).not.toHaveBeenCalled()
  })
})
