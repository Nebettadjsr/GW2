import { afterEach, expect, it, vi } from 'vitest'
import { defineComponent, h, onMounted } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { mkdirSync, writeFileSync } from 'node:fs'
import { connection, STORAGE_KEY } from '@/api/accountSession'
import { systemApi } from '@/api/systemApi'
import { provideSyncOperations } from '../provideSyncOperations'
import { FakeSyncApi, statusOf } from './syncFixtures'
import SyncScreen from '../SyncScreen.vue'
import CraftingProfitScreen from '@/crafting/CraftingProfitScreen.vue'
import { FakeCraftingApi, DEFAULT_SETTINGS, profitResponse, profitableRow, provisionalFreshness } from '@/crafting/__tests__/fixtures'

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); window.sessionStorage.clear(); connection.phase = 'disconnected' })
function save(name: string, value: unknown) {
  mkdirSync('quality-reports/followup', { recursive: true })
  writeFileSync(`quality-reports/followup/${name}.json`, JSON.stringify(value, null, 2))
}

it('observes hidden-tab polls and overlapping slow reads without changing production', async () => {
  vi.useFakeTimers()
  connection.phase = 'connected'
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => true })
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
  let calls = 0, active = 0, peak = 0
  vi.spyOn(systemApi, 'readStatus').mockImplementation(() => {
    calls++; active++; peak = Math.max(peak, active)
    return new Promise(resolve => setTimeout(() => {
      active--; resolve({ running: false } as Awaited<ReturnType<typeof systemApi.readStatus>>)
    }, 12000))
  })
  const host = defineComponent({ setup() { provideSyncOperations(new FakeSyncApi()); return () => h(SyncScreen) } })
  const wrapper = mount(host)
  document.dispatchEvent(new Event('visibilitychange'))
  await vi.advanceTimersByTimeAsync(20000)
  await flushPromises()
  expect(calls).toBe(5)
  expect(peak).toBe(3)
  save('hidden-polling', { logicalElapsedMs: 20000, simulatedResponseMs: 12000, documentHidden: document.hidden,
    requests: calls, peakOverlapping: peak, boundary: 'Mounted production SyncScreen, fake clock and controlled API; browser background timer throttling not simulated' })
  wrapper.unmount()
  vi.clearAllTimers()
  delete (document as unknown as Record<string, unknown>).hidden
  delete (document as unknown as Record<string, unknown>).visibilityState
})

it('observes shell and Profit recovery polling the same task independently', async () => {
  vi.useFakeTimers()
  window.sessionStorage.setItem(STORAGE_KEY, 'fixture-read-only-key')
  const api = new FakeCraftingApi()
  const sync = new FakeSyncApi()
  sync.statusHandler = async url => statusOf(url, 'RUNNING')
  api.profitHandler = async () => profitResponse([profitableRow], 'ALL', DEFAULT_SETTINGS,
    provisionalFreshness({ outstandingWork: [{ kind: 'ACCOUNT', taskId: 'task-ACCOUNT_SYNC',
      taskStatusUrl: '/api/sync/tasks/task-ACCOUNT_SYNC', sources: ['bank'], state: 'RUNNING' }], failures: [] }))
  const host = defineComponent({ setup() {
    const shared = provideSyncOperations(sync)
    onMounted(() => { void shared.trigger('ACCOUNT_SYNC') })
    return () => h(CraftingProfitScreen, { api, refreshApi: sync })
  } })
  const wrapper = mount(host)
  await flushPromises()
  await vi.advanceTimersByTimeAsync(6000)
  await flushPromises()
  expect(sync.submissions).toEqual(['ACCOUNT_SYNC'])
  expect(sync.statusRequests.length).toBeGreaterThan(3)
  expect(new Set(sync.statusRequests)).toEqual(new Set(['/api/sync/tasks/task-ACCOUNT_SYNC']))
  save('duplicate-task-polling', { logicalElapsedMs: 6000, triggerCount: sync.submissions.length,
    statusRequests: sync.statusRequests.length, uniqueTasks: [...new Set(sync.statusRequests)],
    boundary: 'Mounted shell tracker and Profit recovery on identical running task, fake clock, no live backend' })
  wrapper.unmount(); vi.clearAllTimers()
})
