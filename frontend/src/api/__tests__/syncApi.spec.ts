import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiRequestError } from '../http'
import { UNUSABLE_STATUS_URL_CODE, syncApi } from '../syncApi'

/**
 * Route and body checks for the synchronization client (`CURRENT_ARCHITECTURE.md` 5.7–5.9).
 *
 * `fetch` is stubbed, so these establish what the browser would send and how it reads the answer —
 * not that a backend accepted it.
 */
const ACCEPTED = {
  taskId: '7c9f',
  operation: 'ACCOUNT_SYNC',
  statusUrl: '/api/sync/tasks/7c9f'
}

function stubFetch(status: number, body: unknown): ReturnType<typeof vi.fn> {
  const fetchStub = vi.fn(() =>
    Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' }
      })
    )
  )
  vi.stubGlobal('fetch', fetchStub)
  return fetchStub
}

function requestOf(fetchStub: ReturnType<typeof vi.fn>): { path: string; init: RequestInit } {
  const call = fetchStub.mock.calls[0]
  if (call === undefined) throw new Error('No request was sent.')
  return { path: call[0] as string, init: call[1] as RequestInit }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('syncApi', () => {
  it('postsTheAccountTriggerWithoutAnyRequestField', async () => {
    const fetchStub = stubFetch(202, ACCEPTED)

    const accepted = await syncApi.startAccountSync()

    const { path, init } = requestOf(fetchStub)
    expect(path).toBe('/api/sync/account')
    expect(init.method).toBe('POST')
    expect(init.body).toBe('{}')
    expect(accepted).toEqual(ACCEPTED)
  })

  it('postsTheGlobalTriggerWithoutAnyRequestField', async () => {
    const fetchStub = stubFetch(202, { ...ACCEPTED, operation: 'GLOBAL_SYNC' })

    await syncApi.startGlobalSync()

    const { path, init } = requestOf(fetchStub)
    expect(path).toBe('/api/sync/global')
    expect(init.method).toBe('POST')
    expect(init.body).toBe('{}')
  })

  it('postsTheSelectedPriceRefreshVariantAndNothingElse', async () => {
    const profitFetch = stubFetch(202, { ...ACCEPTED, operation: 'PRICE_REFRESH_PROFIT' })
    await syncApi.startPriceRefresh('PROFIT')
    expect(requestOf(profitFetch).path).toBe('/api/prices/refresh')
    expect(requestOf(profitFetch).init.body).toBe('{"variant":"PROFIT"}')

    const discoveryFetch = stubFetch(202, { ...ACCEPTED, operation: 'PRICE_REFRESH_DISCOVERY' })
    await syncApi.startPriceRefresh('DISCOVERY')
    expect(requestOf(discoveryFetch).init.body).toBe('{"variant":"DISCOVERY"}')
  })

  it('readsTheStatusFromTheLocationTheAcceptanceAdvertised', async () => {
    const status = {
      taskId: '7c9f',
      operation: 'ACCOUNT_SYNC',
      state: 'RUNNING',
      submittedAt: '2026-09-25T10:00:00Z',
      startedAt: '2026-09-25T10:00:01Z',
      finishedAt: null,
      failure: null
    }
    const fetchStub = stubFetch(200, status)

    const read = await syncApi.readTaskStatus(ACCEPTED.statusUrl)

    const { path, init } = requestOf(fetchStub)
    expect(path).toBe(ACCEPTED.statusUrl)
    expect(init.method).toBe('GET')
    expect(read).toEqual(status)
  })

  it('reportsARejectedTriggerWithTheBackendCodeAndStatus', async () => {
    stubFetch(409, {
      error: 'SYNC_ALREADY_RUNNING',
      message: 'An ACCOUNT_SYNC task is already running (task 7c9f).'
    })

    const failure = await syncApi.startAccountSync().catch((cause: unknown) => cause)

    expect(failure).toBeInstanceOf(ApiRequestError)
    expect((failure as ApiRequestError).code).toBe('SYNC_ALREADY_RUNNING')
    expect((failure as ApiRequestError).status).toBe(409)
  })

  it('reportsAnUnknownTaskAsAFailedLookupRatherThanAnOutcome', async () => {
    stubFetch(404, { error: 'TASK_NOT_FOUND', message: 'No task 7c9f is known.' })

    const failure = await syncApi.readTaskStatus(ACCEPTED.statusUrl).catch((cause: unknown) => cause)

    expect(failure).toBeInstanceOf(ApiRequestError)
    expect((failure as ApiRequestError).code).toBe('TASK_NOT_FOUND')
    expect((failure as ApiRequestError).status).toBe(404)
  })

  it('refusesAStatusLocationOutsideTheBackendApiWithoutRequestingIt', async () => {
    const fetchStub = stubFetch(200, {})

    const failure = await syncApi
      .readTaskStatus('https://example.invalid/api/sync/tasks/7c9f')
      .catch((cause: unknown) => cause)

    expect(failure).toBeInstanceOf(ApiRequestError)
    expect((failure as ApiRequestError).code).toBe(UNUSABLE_STATUS_URL_CODE)
    expect(fetchStub).not.toHaveBeenCalled()
  })
})
