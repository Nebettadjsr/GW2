import { afterEach, describe, expect, it, vi } from 'vitest'
import { getJson } from '../http'

describe('account freshness recovery', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

  it('waits for the advertised account task and retries the original request once', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        error: 'ACCOUNT_DATA_STALE', message: 'Refreshing', taskStatusUrl: '/api/sync/tasks/task-1'
      }), { status: 503, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ state: 'SUCCEEDED' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ value: 12 }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getJson<{ value: number }>('/account/bank')).resolves.toEqual({ value: 12 })
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls.map(call => call[0])).toEqual([
      '/api/account/bank', '/api/sync/tasks/task-1', '/api/account/bank'
    ])
  })
})
