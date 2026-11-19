import { flushPromises, mount as vtuMount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { connection } from '@/api/accountSession'
import { ApiRequestError } from '@/api/http'
import { systemApi, type ApiCompatibilityView } from '@/api/systemApi'
import OwnerApiCompatibility from '../OwnerApiCompatibility.vue'
import { deferred } from './syncFixtures'

const NOT_CHECKED: ApiCompatibilityView = {
  stale: false,
  result: { state: 'NOT_CHECKED', checkedAt: null, durationMs: 0, overall: null, recipeProfile: null, versions: [], newerVersions: [], probes: [] }
}
const RUNNING: ApiCompatibilityView = { stale: false, result: { ...NOT_CHECKED.result, state: 'RUNNING' } }
function complete(overall: 'OK' | 'ATTENTION' | 'UNAVAILABLE' | 'MISMATCH', stale = false): ApiCompatibilityView {
  return {
    stale,
    result: {
      state: 'COMPLETE', checkedAt: '2026-11-19T10:00:00Z', durationMs: 2100, overall,
      recipeProfile: 'legacy default (unpinned)',
      versions: [{ version: '2022-03-09T02:00:00.000Z', description: 'Change ingredients', reviewed: true }],
      newerVersions: ['2026-12-01T00:00:00.000Z'],
      probes: [
        { id: 'item', label: 'Item detail', endpoint: '/v2/items/{id}', profile: 'default (unpinned)', status: 'OK', code: 'COMPATIBLE', detail: 'Consumed fields have the expected types.' },
        { id: 'jade-default', label: 'Jade recipe, current default', endpoint: '/v2/recipes/13839', profile: 'legacy default (unpinned)', status: 'ATTENTION', code: 'KNOWN_COVERAGE_GAP', detail: 'Known app coverage gap, not an upstream mismatch.' }
      ]
    }
  }
}
const mounted: VueWrapper[] = []
/** Every wrapper is unmounted after each test so none reacts to a later test's connection changes. */
function mount(): VueWrapper {
  const w = vtuMount(OwnerApiCompatibility)
  mounted.push(w)
  return w
}
const sel = (id: string) => `[data-test="${id}"]`

beforeEach(() => {
  vi.useFakeTimers()
  connection.phase = 'connected'
  connection.generation = 1
  connection.leaseVersion = 1
})
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  connection.phase = 'disconnected'
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('OwnerApiCompatibility', () => {
  it('showsNotCheckedHonestlyWithScopeAndLinks_andReadsOnlyTheCache', async () => {
    const read = vi.spyOn(systemApi, 'readApiCompatibility').mockResolvedValue(NOT_CHECKED)
    const start = vi.spyOn(systemApi, 'startApiCompatibilityCheck')
    const w = mount()
    await flushPromises()
    expect(w.find(sel('api-compat-state')).text()).toBe('Not checked')
    expect(w.find(sel('api-compat-check')).text()).toBe('Check API compatibility')
    expect(w.find(sel('api-compat-scope')).text()).toContain('authenticated account endpoint payloads are not live-verified')
    expect(w.find(sel('api-compat-directory-link')).attributes('href')).toContain('/v2.json')
    expect(w.find(sel('api-compat-changelog-link')).attributes('href')).toContain('API:Changelog')
    expect(w.find(sel('api-compat-summary')).exists()).toBe(false)
    expect(start).not.toHaveBeenCalled()
    expect(read).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(read).toHaveBeenCalledTimes(1) // no timer polling while nothing runs
  })

  it('actionToRunningToResult_pollsCacheAndDisablesTheButtonWhileRunning', async () => {
    const read = vi.spyOn(systemApi, 'readApiCompatibility').mockResolvedValueOnce(NOT_CHECKED)
    const start = vi.spyOn(systemApi, 'startApiCompatibilityCheck').mockResolvedValue(RUNNING)
    const w = mount()
    await flushPromises()
    await w.find(sel('api-compat-check')).trigger('click')
    await flushPromises()
    expect(start).toHaveBeenCalledTimes(1)
    expect(w.find(sel('api-compat-state')).text()).toBe('Running…')
    expect(w.find(sel('api-compat-check')).attributes('disabled')).toBeDefined()
    await w.find(sel('api-compat-check')).trigger('click')
    expect(start).toHaveBeenCalledTimes(1)
    read.mockResolvedValueOnce(RUNNING).mockResolvedValueOnce(complete('ATTENTION'))
    await vi.advanceTimersByTimeAsync(2000)
    expect(w.find(sel('api-compat-state')).text()).toBe('Running…')
    await vi.advanceTimersByTimeAsync(2000)
    expect(w.find(sel('api-compat-state')).text()).toBe('Needs attention')
    expect(w.find(sel('api-compat-checked-at')).text()).toBe('2026-11-19 10:00 UTC')
    expect(w.find(sel('api-compat-profile')).text()).toBe('legacy default (unpinned)')
    expect(w.find(sel('api-compat-newer')).text()).toContain('2026-12-01')
    expect(w.find(sel('api-compat-probe-jade-default')).text()).toContain('KNOWN_COVERAGE_GAP'.length ? 'ATTENTION' : '')
    expect(w.find(sel('api-compat-probe-jade-default')).text()).toContain('not an upstream mismatch')
    expect(w.find(sel('api-compat-check')).attributes('disabled')).toBeUndefined()
    const reads = read.mock.calls.length
    await vi.advanceTimersByTimeAsync(20_000)
    expect(read.mock.calls.length).toBe(reads) // polling stops once complete
  })

  it('unavailableAndStaleResultsNeverLookSuccessful', async () => {
    const read = vi.spyOn(systemApi, 'readApiCompatibility').mockResolvedValue(complete('UNAVAILABLE'))
    const w = mount()
    await flushPromises()
    expect(w.find(sel('api-compat-state')).text()).toBe('Could not be fully checked')
    expect(w.find(sel('api-compat-state')).classes()).not.toContain('api-compat__state--ok')
    read.mockResolvedValue(complete('OK', true))
    const stale = mount()
    await flushPromises()
    expect(stale.find(sel('api-compat-state')).text()).toContain('stale')
    expect(stale.find(sel('api-compat-state')).classes()).not.toContain('api-compat__state--ok')
    read.mockRejectedValue(new ApiRequestError('down', 'X', 502))
    const failed = mount()
    await flushPromises()
    expect(failed.find(sel('api-compat-state')).text()).toBe('Not checked')
    expect(failed.find(sel('api-compat-error')).exists()).toBe(true)
  })

  it('duplicateConflictIsExplainedAndReReadsTheCachedState', async () => {
    const read = vi.spyOn(systemApi, 'readApiCompatibility').mockResolvedValue(NOT_CHECKED)
    vi.spyOn(systemApi, 'startApiCompatibilityCheck').mockRejectedValue(new ApiRequestError('busy', 'SYNC_ALREADY_RUNNING', 409))
    const w = mount()
    await flushPromises()
    await w.find(sel('api-compat-check')).trigger('click')
    await flushPromises()
    expect(w.find(sel('api-compat-error')).text()).toContain('already running')
    expect(read).toHaveBeenCalledTimes(2)
  })

  it('confirmedDenialClearsResultsAndAsksTheOwnerBoundaryToWithdraw', async () => {
    vi.spyOn(systemApi, 'readApiCompatibility').mockResolvedValueOnce(complete('OK'))
    vi.spyOn(systemApi, 'startApiCompatibilityCheck').mockRejectedValue(new ApiRequestError('no', 'OWNER_REQUIRED', 403))
    const w = mount()
    await flushPromises()
    expect(w.find(sel('api-compat-summary')).exists()).toBe(true)
    await w.find(sel('api-compat-check')).trigger('click')
    await flushPromises()
    expect(w.emitted('denied')).toHaveLength(1)
    expect(w.find(sel('api-compat-summary')).exists()).toBe(false)
  })

  it('accountSwitchDiscardsALateAnswerAndUnmountStopsPolling', async () => {
    const late = deferred<ApiCompatibilityView>()
    const read = vi.spyOn(systemApi, 'readApiCompatibility').mockReturnValueOnce(late.promise)
    const w = mount()
    connection.generation = 2
    read.mockResolvedValue(NOT_CHECKED)
    await flushPromises()
    late.resolve(complete('OK'))
    await flushPromises()
    expect(w.find(sel('api-compat-summary')).exists()).toBe(false)
    expect(w.find(sel('api-compat-state')).text()).toBe('Not checked')

    read.mockResolvedValue(RUNNING)
    connection.generation = 3
    await flushPromises()
    const calls = read.mock.calls.length
    w.unmount()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(read.mock.calls.length).toBe(calls)
  })
})
