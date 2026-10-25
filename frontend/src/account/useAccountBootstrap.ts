import { ref } from 'vue'
import { ApiRequestError } from '@/api/http'
import { craftingApi } from '@/api/craftingApi'
import { syncApi } from '@/api/syncApi'
import type { SyncTaskAccepted } from '@/api/types'

/**
 * First-use synchronization for a connected account (`TARGET_ARCHITECTURE.md` 8.1, 9.1).
 *
 * The selector options are the cheapest read that reveals whether the account's characters were ever
 * published. When they were not, the account's data is missing rather than empty, so one keyed
 * Crafting Profit data refresh is run and awaited, then the options are read once more. Everything is
 * bounded: a fixed number of task lookups and of "narrower task was running" retries, and one pass
 * per mount — a failure is reported with a retry button, never retried in a loop.
 */
export const BOOTSTRAP_POLL_MS = 2_000
export const BOOTSTRAP_MAX_POLLS = 150
export const BOOTSTRAP_MAX_COVER_RETRIES = 3

export type BootstrapPhase = 'checking' | 'syncing' | 'ready' | 'failed'

export function useAccountBootstrap(sleep: (ms: number) => Promise<void> = defaultSleep) {
  const phase = ref<BootstrapPhase>('checking')
  const error = ref<string | null>(null)
  let running = false

  async function run(): Promise<void> {
    if (running) return
    running = true
    phase.value = 'checking'
    error.value = null
    try {
      const options = await craftingApi.loadSelectorOptions()
      if (options.pending) {
        phase.value = 'syncing'
        await refreshAccountData()
      }
      phase.value = 'ready'
    } catch (cause) {
      error.value = describe(cause)
      phase.value = 'failed'
    } finally {
      running = false
    }
  }

  async function refreshAccountData(): Promise<void> {
    for (let attempt = 0; attempt <= BOOTSTRAP_MAX_COVER_RETRIES; attempt += 1) {
      const accepted = await syncApi.startProfitDataRefresh()
      await awaitTask(accepted)
      // A narrower earlier run does not stand in for this refresh: wait for it, then ask again.
      if (accepted.coversRequest !== false) return
    }
    throw new Error('Another refresh kept running, so the account synchronization did not start. Try again.')
  }

  async function awaitTask(accepted: SyncTaskAccepted): Promise<void> {
    for (let poll = 0; poll < BOOTSTRAP_MAX_POLLS; poll += 1) {
      const status = await syncApi.readTaskStatus(accepted.statusUrl)
      if (status.state === 'SUCCEEDED') return
      if (status.state === 'FAILED') {
        throw new Error(
          status.failure ? `Account synchronization failed: ${status.failure.message}` : 'Account synchronization failed.'
        )
      }
      await sleep(BOOTSTRAP_POLL_MS)
    }
    throw new Error('Account synchronization is taking longer than expected. Try again later.')
  }

  return { phase, error, run }
}

function describe(cause: unknown): string {
  if (cause instanceof ApiRequestError) return `${cause.code}: ${cause.message}`
  return cause instanceof Error ? cause.message : String(cause)
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}
