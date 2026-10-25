import { effectScope } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { connection, resetAccountSession } from '@/api/accountSession'
import { craftingApi } from '@/api/craftingApi'
import { syncApi } from '@/api/syncApi'
import { selectorOptions } from '@/crafting/__tests__/fixtures'
import { useAccountBootstrap } from '../useAccountBootstrap'

beforeEach(() => resetAccountSession())
afterEach(() => { vi.restoreAllMocks(); resetAccountSession() })

it('stops a discarded workspace before the next task poll or sync submission', async () => {
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockResolvedValue({ ...selectorOptions, pending: true })
  const submit = vi.spyOn(syncApi, 'startProfitDataRefresh').mockResolvedValue({
    taskId: 'old-task', operation: 'ACCOUNT_SYNC', statusUrl: '/api/sync/tasks/old-task', coversRequest: false
  })
  const poll = vi.spyOn(syncApi, 'readTaskStatus').mockResolvedValue({
    taskId: 'old-task', operation: 'ACCOUNT_SYNC', state: 'RUNNING', submittedAt: 'x',
    startedAt: 'x', finishedAt: null, failure: null
  })
  let releaseSleep: () => void = () => undefined
  let sleeping = false
  const scope = effectScope()
  const bootstrap = scope.run(() => useAccountBootstrap(() => new Promise<void>((resolve) => {
    sleeping = true
    releaseSleep = resolve
  })))!
  const run = bootstrap.run()
  await vi.waitFor(() => expect(sleeping).toBe(true))
  scope.stop()
  releaseSleep()
  await run
  expect(poll).toHaveBeenCalledTimes(1)
  expect(submit).toHaveBeenCalledTimes(1)
})

it('does not bootstrap a new account from an old selector response', async () => {
  let release: (value: typeof selectorOptions) => void = () => undefined
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockImplementation(() => new Promise((resolve) => { release = resolve }))
  const submit = vi.spyOn(syncApi, 'startProfitDataRefresh')
  const scope = effectScope()
  const bootstrap = scope.run(() => useAccountBootstrap())!
  const run = bootstrap.run()
  connection.generation += 1
  release({ ...selectorOptions, pending: true })
  await run
  expect(submit).not.toHaveBeenCalled()
  scope.stop()
})
