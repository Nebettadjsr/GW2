import { getCurrentInstance, onActivated, onDeactivated, onScopeDispose, ref, watch } from 'vue'
import { connection } from '@/api/accountSession'
import { getAccountMaxAgeMs } from '@/api/http'
import { useSharedSyncOperations, type SharedSyncOperations } from '@/sync/provideSyncOperations'
import type { AccountViewOperation } from '@/sync/operations'

interface RefreshOptions {
  operation: AccountViewOperation
  load: () => Promise<void>
  stale: () => boolean
  timestamps: () => Array<string | null | undefined>
  refresh?: () => Promise<void>
  readFailed?: () => boolean
}

/** One expiry timer for the visible view; its source refresh retains the last usable data. */
export function useAutomaticAccountRefresh(options: RefreshOptions, shared?: SharedSyncOperations) {
  const sync = shared ?? useSharedSyncOperations()
  const account = sync.operations.find(operation => operation.key === options.operation)!
  const generation = connection.generation
  const updating = ref(false)
  const active = ref(true)
  const failed = ref(false)
  let disposed = false
  let failures = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  const cancelWaits = new Set<() => void>()
  const current = () => !disposed && active.value && generation === connection.generation

  async function refreshSource(): Promise<void> {
    if (!current()) return
    // The shell joins an existing account task instead of starting another polling loop.
    await sync.trigger(options.operation)
    if (!current()) return
    if (account.isBusy) await new Promise<void>((resolve, reject) => {
      let stop = () => {}
      const finish = (error?: Error) => {
        stop(); clearTimeout(bound); cancelWaits.delete(cancel)
        error ? reject(error) : resolve()
      }
      const cancel = () => finish()
      const bound = setTimeout(() => finish(new Error('Refresh timed out')), 300_000)
      cancelWaits.add(cancel)
      stop = watch(() => account.isBusy, busy => { if (!busy) finish() })
    })
    if (!current()) return
    if (account.submissionError || account.lookupError || account.status?.state !== 'SUCCEEDED') {
      throw new Error('Account refresh did not finish')
    }
  }

  function schedule(): void {
    clearTimeout(timer)
    if (!current() || updating.value || document.hidden) return
    const dates = options.timestamps().map(value => value ? Date.parse(value) : NaN).filter(Number.isFinite)
    const maxAge = getAccountMaxAgeMs()
    const expires = dates.length && maxAge !== null ? Math.min(...dates) + maxAge - Date.now() : null
    const delay = failed.value ? Math.min(300_000, 60_000 * 2 ** Math.min(failures - 1, 3))
      : options.stale() ? 0 : expires === null ? 60_000 : expires <= 0 ? 30_000 : expires + 1_000
    timer = setTimeout(() => { void run() }, Math.min(delay, 2_147_483_647))
  }

  async function run(): Promise<void> {
    if (!current() || updating.value || document.hidden) return
    updating.value = true
    try {
      await options.load()
      if (!current()) return
      if (options.readFailed?.()) throw new Error('Saved data could not be read')
      if (options.stale()) {
        await (options.refresh ?? refreshSource)()
        if (!current()) return
        await options.load()
        if (options.stale() || options.readFailed?.()) throw new Error('Data is still out of date')
      }
      failures = 0; failed.value = false
    } catch {
      if (current()) { failures += 1; failed.value = true }
    } finally {
      updating.value = false
      schedule()
    }
  }

  watch(() => [options.stale(), ...options.timestamps()], schedule, { immediate: true })
  // A manual refresh in System Status also updates the currently visible data once it completes.
  watch(() => account.isBusy, (busy, wasBusy) => { if (!busy && wasBusy && !updating.value) void run() })
  watch(() => sync.operations.find(operation => operation.key === 'ACCOUNT_SYNC')?.isBusy,
    (busy, wasBusy) => { if (!busy && wasBusy && !updating.value) void run() })
  // KeepAlive preserves the selected character, but leaving it must stop its refresh timer.
  if (getCurrentInstance()) {
    onDeactivated(() => { active.value = false; clearTimeout(timer) })
    onActivated(() => {
      const returning = !active.value
      active.value = true
      if (returning) void run()
    })
  }
  const onVisible = () => { if (document.hidden) clearTimeout(timer); else void run() }
  document.addEventListener('visibilitychange', onVisible)
  onScopeDispose(() => {
    disposed = true; clearTimeout(timer)
    document.removeEventListener('visibilitychange', onVisible)
    for (const cancel of [...cancelWaits]) cancel()
  })
  return { updating, failed, active, run, refreshSource }
}
