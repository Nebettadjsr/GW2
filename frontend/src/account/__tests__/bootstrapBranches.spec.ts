import { effectScope } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { connection, resetAccountSession } from '@/api/accountSession'
import { ApiRequestError } from '@/api/http'
import { craftingApi } from '@/api/craftingApi'
import { syncApi } from '@/api/syncApi'
import { selectorOptions } from '@/crafting/__tests__/fixtures'
import {
  BOOTSTRAP_MAX_COVER_RETRIES,
  BOOTSTRAP_MAX_POLLS,
  BOOTSTRAP_POLL_MS,
  useAccountBootstrap
} from '../useAccountBootstrap'

const accepted = (coversRequest?: boolean) => ({
  taskId: 't', operation: 'ACCOUNT_SYNC' as const, statusUrl: '/api/sync/tasks/t', ...(coversRequest === undefined ? {} : { coversRequest })
})
const task = (state: 'RUNNING' | 'SUCCEEDED' | 'FAILED', failure: { message: string } | null = null) => ({
  taskId: 't', operation: 'ACCOUNT_SYNC' as const, state, submittedAt: 'x', startedAt: 'x', finishedAt: null,
  failure
}) as never

beforeEach(() => resetAccountSession())
afterEach(() => { vi.restoreAllMocks(); resetAccountSession() })

function mount(sleep: (ms: number) => Promise<void> = async () => undefined) {
  const scope = effectScope()
  return { scope, bootstrap: scope.run(() => useAccountBootstrap(sleep))! }
}

it('is ready without any synchronization when the account data was already published', async () => {
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockResolvedValue({ ...selectorOptions, pending: false })
  const submit = vi.spyOn(syncApi, 'startProfitDataRefresh')
  const { bootstrap } = mount()
  await bootstrap.run()
  expect(bootstrap.phase.value).toBe('ready')
  expect(submit).not.toHaveBeenCalled()
})

it('ignores a second run while one is in progress', async () => {
  let release: (value: typeof selectorOptions) => void = () => undefined
  const load = vi.spyOn(craftingApi, 'loadSelectorOptions').mockImplementation(() => new Promise((resolve) => { release = resolve }))
  const { bootstrap } = mount()
  const first = bootstrap.run()
  await bootstrap.run()
  expect(load).toHaveBeenCalledTimes(1)
  expect(bootstrap.phase.value).toBe('checking')
  release({ ...selectorOptions, pending: false })
  await first
  expect(bootstrap.phase.value).toBe('ready')
})

it('syncs, polls with the bounded delay and becomes ready once the task succeeded', async () => {
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockResolvedValue({ ...selectorOptions, pending: true })
  vi.spyOn(syncApi, 'startProfitDataRefresh').mockResolvedValue(accepted())
  const poll = vi.spyOn(syncApi, 'readTaskStatus')
    .mockResolvedValueOnce(task('RUNNING')).mockResolvedValueOnce(task('SUCCEEDED'))
  const sleep = vi.fn(async () => undefined)
  const { bootstrap } = mount(sleep)
  await bootstrap.run()
  expect(poll).toHaveBeenCalledTimes(2)
  expect(sleep).toHaveBeenCalledExactlyOnceWith(BOOTSTRAP_POLL_MS)
  expect(bootstrap.phase.value).toBe('ready')
  expect(bootstrap.error.value).toBeNull()
})

it('reports the task failure message and allows an explicit retry', async () => {
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockResolvedValue({ ...selectorOptions, pending: true })
  const submit = vi.spyOn(syncApi, 'startProfitDataRefresh').mockResolvedValue(accepted(true))
  vi.spyOn(syncApi, 'readTaskStatus').mockResolvedValueOnce(task('FAILED', { message: 'GW2 down' }))
  const { bootstrap } = mount()
  await bootstrap.run()
  expect(bootstrap.phase.value).toBe('failed')
  expect(bootstrap.error.value).toBe('Account synchronization failed: GW2 down')

  vi.spyOn(syncApi, 'readTaskStatus').mockResolvedValue(task('SUCCEEDED'))
  await bootstrap.run()
  expect(submit).toHaveBeenCalledTimes(2)
  expect(bootstrap.phase.value).toBe('ready')
  expect(bootstrap.error.value).toBeNull()
})

it('reports a task failure without detail', async () => {
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockResolvedValue({ ...selectorOptions, pending: true })
  vi.spyOn(syncApi, 'startProfitDataRefresh').mockResolvedValue(accepted())
  vi.spyOn(syncApi, 'readTaskStatus').mockResolvedValue(task('FAILED'))
  const { bootstrap } = mount()
  await bootstrap.run()
  expect(bootstrap.error.value).toBe('Account synchronization failed.')
})

it('gives up after the bounded number of task lookups', async () => {
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockResolvedValue({ ...selectorOptions, pending: true })
  vi.spyOn(syncApi, 'startProfitDataRefresh').mockResolvedValue(accepted())
  const poll = vi.spyOn(syncApi, 'readTaskStatus').mockResolvedValue(task('RUNNING'))
  const { bootstrap } = mount()
  await bootstrap.run()
  expect(poll).toHaveBeenCalledTimes(BOOTSTRAP_MAX_POLLS)
  expect(bootstrap.phase.value).toBe('failed')
  expect(bootstrap.error.value).toBe('Account synchronization is taking longer than expected. Try again later.')
})

it('asks again after a narrower run, and stops after the bounded number of retries', async () => {
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockResolvedValue({ ...selectorOptions, pending: true })
  const submit = vi.spyOn(syncApi, 'startProfitDataRefresh').mockResolvedValue(accepted(false))
  vi.spyOn(syncApi, 'readTaskStatus').mockResolvedValue(task('SUCCEEDED'))
  const { bootstrap } = mount()
  await bootstrap.run()
  expect(submit).toHaveBeenCalledTimes(BOOTSTRAP_MAX_COVER_RETRIES + 1)
  expect(bootstrap.phase.value).toBe('failed')
  expect(bootstrap.error.value).toContain('Another refresh kept running')
})

it('becomes ready when a repeated request is covered after a narrower run', async () => {
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockResolvedValue({ ...selectorOptions, pending: true })
  const submit = vi.spyOn(syncApi, 'startProfitDataRefresh')
    .mockResolvedValueOnce(accepted(false)).mockResolvedValueOnce(accepted(true))
  vi.spyOn(syncApi, 'readTaskStatus').mockResolvedValue(task('SUCCEEDED'))
  const { bootstrap } = mount()
  await bootstrap.run()
  expect(submit).toHaveBeenCalledTimes(2)
  expect(bootstrap.phase.value).toBe('ready')
})

it('shows a backend failure by code and message, and a non-Error cause as text', async () => {
  const load = vi.spyOn(craftingApi, 'loadSelectorOptions')
    .mockRejectedValueOnce(new ApiRequestError('down', 'BACKEND_UNREACHABLE', null))
    .mockRejectedValueOnce('plain text')
  const { bootstrap } = mount()
  await bootstrap.run()
  expect(bootstrap.error.value).toBe('BACKEND_UNREACHABLE: down')
  await bootstrap.run()
  expect(bootstrap.error.value).toBe('plain text')
  expect(load).toHaveBeenCalledTimes(2)
  expect(bootstrap.phase.value).toBe('failed')
})

it('does not report a failure of the previous account after a switch', async () => {
  let fail: (cause: unknown) => void = () => undefined
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockImplementation(() => new Promise((_, reject) => { fail = reject }))
  const { bootstrap } = mount()
  const run = bootstrap.run()
  connection.generation += 1
  fail(new Error('old account failed'))
  await run
  expect(bootstrap.error.value).toBeNull()
  expect(bootstrap.phase.value).toBe('checking')
})

it('works without an enclosing effect scope', async () => {
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockResolvedValue({ ...selectorOptions, pending: false })
  const bootstrap = useAccountBootstrap()
  await bootstrap.run()
  expect(bootstrap.phase.value).toBe('ready')
})

it('uses a real timer for the default poll delay', async () => {
  vi.useFakeTimers()
  vi.spyOn(craftingApi, 'loadSelectorOptions').mockResolvedValue({ ...selectorOptions, pending: true })
  vi.spyOn(syncApi, 'startProfitDataRefresh').mockResolvedValue(accepted())
  const poll = vi.spyOn(syncApi, 'readTaskStatus')
    .mockResolvedValueOnce(task('RUNNING')).mockResolvedValueOnce(task('SUCCEEDED'))
  const bootstrap = useAccountBootstrap()
  const run = bootstrap.run()
  await vi.advanceTimersByTimeAsync(BOOTSTRAP_POLL_MS - 1)
  expect(poll).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(1)
  await run
  expect(poll).toHaveBeenCalledTimes(2)
  expect(bootstrap.phase.value).toBe('ready')
  vi.useRealTimers()
})
