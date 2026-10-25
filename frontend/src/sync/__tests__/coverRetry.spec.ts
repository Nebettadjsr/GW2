import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, type EffectScope } from 'vue'
import type { SyncTaskAccepted } from '@/api/types'
import { MAX_COVER_RETRIES, useSyncOperations } from '../useSyncOperations'
import { FakeSyncApi, acceptance, statusOf } from './syncFixtures'

/**
 * A 202 with `coversRequest: false` names a narrower earlier run (STORY-WEB-035, STORY-API-011): its
 * success is waited for and the original request is repeated, within a fixed bound, and it is never
 * reported as the requested full refresh.
 */
let scope: EffectScope

beforeEach(() => {
  vi.useFakeTimers()
  scope = effectScope()
})

afterEach(() => {
  scope.stop()
  vi.useRealTimers()
})

async function settle(): Promise<void> {
  for (let step = 0; step < 20; step += 1) await Promise.resolve()
}

const narrow = (): SyncTaskAccepted => ({ ...acceptance('ACCOUNT_SYNC'), coversRequest: false, retryAfterSeconds: 10 })

describe('narrower active task', () => {
  it('repeats the request after the narrower run ends and tracks the covering task', async () => {
    const api = new FakeSyncApi()
    api.submitHandler = (_, index) =>
      Promise.resolve(index === 0 ? narrow() : { ...acceptance('ACCOUNT_SYNC'), taskId: 'task-full', statusUrl: '/api/sync/tasks/task-full', coversRequest: true })
    api.statusHandler = (url) => Promise.resolve(statusOf(url, 'SUCCEEDED'))
    const sync = scope.run(() => useSyncOperations(api))!

    await sync.trigger('ACCOUNT_SYNC')
    await settle()

    const view = sync.operations.find((operation) => operation.key === 'ACCOUNT_SYNC')!
    expect(api.submissionCount('ACCOUNT_SYNC')).toBe(2)
    expect(view.taskId).toBe('task-full')
    expect(view.isTracking).toBe(false)
    expect(view.status?.state).toBe('SUCCEEDED')
  })

  it('stops with an honest message instead of looping when the narrower run keeps blocking it', async () => {
    const api = new FakeSyncApi()
    api.submitHandler = () => Promise.resolve(narrow())
    const sync = scope.run(() => useSyncOperations(api))!

    await sync.trigger('ACCOUNT_SYNC')
    await settle()

    const view = sync.operations.find((operation) => operation.key === 'ACCOUNT_SYNC')!
    expect(api.submissionCount('ACCOUNT_SYNC')).toBe(MAX_COVER_RETRIES + 1)
    expect(view.isTracking).toBe(false)
    expect(view.lookupError).toMatch(/did not start/)
  })
})
