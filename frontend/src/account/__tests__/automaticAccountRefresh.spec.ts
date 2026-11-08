import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, reactive, nextTick, type EffectScope } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { connection } from '@/api/accountSession'
import { useAutomaticAccountRefresh } from '../useAutomaticAccountRefresh'
import type { SharedSyncOperations } from '@/sync/provideSyncOperations'
import type { AccountViewOperation } from '@/sync/operations'

vi.mock('@/api/http', async importOriginal => ({
  ...await importOriginal<object>(), getAccountMaxAgeMs: () => 10_000
}))
let scope: EffectScope
const now = Date.parse('2026-11-08T12:00:00Z')

function setup(initial = 'STALE', key: AccountViewOperation = 'BANK_SYNC') {
  const backend = { state: initial, at: new Date(now).toISOString(), amount: 10 }
  const view = reactive({ state: initial, at: backend.at, amount: 10 })
  const operation = reactive({ key, isBusy: false, status: null as null | {state:string}, submissionError:null, lookupError:null })
  const trigger = vi.fn(async () => { if (!operation.isBusy) operation.isBusy = true })
  const shared = {operations:[operation], trigger} as unknown as SharedSyncOperations
  const load = vi.fn(async () => { Object.assign(view,backend) })
  scope = effectScope()
  const automatic = scope.run(() => useAutomaticAccountRefresh({
    operation: key, load, stale:()=>view.state==='STALE', timestamps:()=>[view.at]
  },shared))!
  return { backend, view, operation, trigger, load, automatic }
}
beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(now); vi.spyOn(document,'hidden','get').mockReturnValue(false) })
afterEach(() => { scope?.stop(); vi.useRealTimers(); vi.restoreAllMocks() })

describe('automatic account refresh', () => {
  it.each<AccountViewOperation>(['BANK_SYNC', 'MATERIALS_SYNC', 'CHARACTERS_SYNC'])('refreshes only %s and keeps old data until publication', async key => {
    const t = setup('STALE', key)
    await vi.advanceTimersByTimeAsync(0)
    expect(t.trigger).toHaveBeenCalledTimes(1)
    expect(t.trigger).toHaveBeenCalledWith(key)
    expect(t.automatic.updating.value).toBe(true)
    expect(t.view.amount).toBe(10)
    await t.automatic.run()
    expect(t.trigger).toHaveBeenCalledTimes(1)
    Object.assign(t.backend,{state:'CURRENT',amount:25})
    t.operation.status={state:'SUCCEEDED'}; t.operation.isBusy=false
    await flushPromises()
    expect(t.view.amount).toBe(25)
    expect(t.automatic.updating.value).toBe(false)
    expect(t.automatic.failed.value).toBe(false)
  })

  it('rechecks on expiry, pauses hidden views and catches up when visible', async () => {
    const t = setup('CURRENT')
    await vi.advanceTimersByTimeAsync(10_999)
    expect(t.load).not.toHaveBeenCalled()
    const hidden = vi.spyOn(document,'hidden','get')
    hidden.mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(t.load).not.toHaveBeenCalled()
    t.backend.state='STALE'
    hidden.mockReturnValue(false)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(t.load).toHaveBeenCalledTimes(1)
    expect(t.trigger).toHaveBeenCalledTimes(1)
  })

  it('retains data after a failure and backs off before trying again', async () => {
    const t = setup()
    await vi.advanceTimersByTimeAsync(0)
    t.operation.status={state:'FAILED'}; t.operation.isBusy=false
    await flushPromises()
    expect(t.view.amount).toBe(10)
    expect(t.automatic.failed.value).toBe(true)
    await vi.advanceTimersByTimeAsync(59_999)
    expect(t.trigger).toHaveBeenCalledTimes(1)
    t.backend.state='CURRENT'
    await vi.advanceTimersByTimeAsync(1)
    expect(t.trigger).toHaveBeenCalledTimes(1)
    expect(t.automatic.failed.value).toBe(false)
  })

  it('ignores completion after an account switch', async () => {
    const t = setup()
    await vi.advanceTimersByTimeAsync(0)
    const reads = t.load.mock.calls.length
    connection.generation += 1
    t.operation.status={state:'SUCCEEDED'}; t.operation.isBusy=false
    await nextTick(); await flushPromises()
    expect(t.load).toHaveBeenCalledTimes(reads)
    expect(t.view.amount).toBe(10)
  })
})
