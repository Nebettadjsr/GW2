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
      probes: [{ id: 'item', label: 'Item detail', endpoint: '/v2/items/{id}', profile: 'default', status: 'OK', code: 'COMPATIBLE', detail: 'ok' }]
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
const state = (w: VueWrapper) => w.find(sel('api-compat-state')).text()

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

describe('OwnerApiCompatibility (compact status card)', () => {
  it('showsOnlyTitleStatusAndLastChecked_withNoButtonLinksOrTechnicalDetail', async () => {
    vi.spyOn(systemApi, 'readApiCompatibility').mockResolvedValue(complete('OK'))
    const w = mount()
    await flushPromises()
    expect(w.find('h3').text()).toBe('GW2 API')
    expect(state(w)).toBe('Good')
    expect(w.find(sel('api-compat-state')).classes()).toContain('status--success')
    expect(w.find(sel('api-compat-checked-at')).text()).not.toBe('—')
    expect(w.find('button').exists()).toBe(false)
    expect(w.find('a').exists()).toBe(false)
    expect(w.find('details').exists()).toBe(false)
    expect(w.find('ul').exists()).toBe(false)
    expect(w.find('article').classes()).toEqual(expect.arrayContaining(['panel', 'status-card']))
    expect(w.text()).not.toMatch(/Jade|schema|probe|2026-12-01|legacy|unpinned/i)
  })

  it('attentionAndMismatchSayNeedsUpdate_butNeverGood', async () => {
    const read = vi.spyOn(systemApi, 'readApiCompatibility')
    for (const overall of ['ATTENTION', 'MISMATCH'] as const) {
      read.mockResolvedValue(complete(overall))
      const w = mount()
      await flushPromises()
      expect(state(w)).toBe('Needs update')
      expect(w.find(sel('api-compat-state')).classes()).not.toContain('status--success')
      expect(w.find(sel('api-compat-checked-at')).text()).not.toBe('—')
    }
  })

  it('neutralStatesForNotCheckedRunningUnavailableAndStale_neverLookSuccessful', async () => {
    const read = vi.spyOn(systemApi, 'readApiCompatibility')
    const cases: Array<[() => void, string]> = [
      [() => read.mockResolvedValue(NOT_CHECKED), 'Not checked'],
      [() => read.mockResolvedValue(RUNNING), 'Checking…'],
      [() => read.mockResolvedValue(complete('UNAVAILABLE')), 'Unavailable'],
      [() => read.mockResolvedValue(complete('OK', true)), 'Not checked'],
      [() => read.mockRejectedValue(new ApiRequestError('down', 'X', 502)), 'Unavailable']
    ]
    for (const [arrange, text] of cases) {
      arrange()
      const w = mount()
      await flushPromises()
      expect(state(w)).toBe(text)
      expect(w.find(sel('api-compat-state')).classes()).toContain('status--unknown')
      expect(w.find('button').exists()).toBe(false)
      w.unmount()
    }
    // Without a valid result there is no time to show.
    read.mockResolvedValue(NOT_CHECKED)
    const w = mount()
    await flushPromises()
    expect(w.find(sel('api-compat-checked-at')).text()).toBe('—')
  })

  it('updatesFromCachedReadsWhileStayingMounted_withoutAnyStartRequest', async () => {
    const read = vi.spyOn(systemApi, 'readApiCompatibility').mockResolvedValueOnce(NOT_CHECKED)
    const w = mount()
    await flushPromises()
    expect(state(w)).toBe('Not checked')
    read.mockResolvedValueOnce(RUNNING)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(state(w)).toBe('Checking…')
    read.mockResolvedValueOnce(complete('ATTENTION'))
    await vi.advanceTimersByTimeAsync(2_000) // faster cadence only while a check is running
    expect(state(w)).toBe('Needs update')
    read.mockResolvedValueOnce(complete('OK'))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(state(w)).toBe('Good')
    expect(read).toHaveBeenCalledTimes(4)
    expect(Object.keys(systemApi)).not.toContain('startApiCompatibilityCheck') // the browser has no start path
  })

  it('confirmedDenialClearsTheCardAndAsksTheOwnerBoundaryToWithdraw', async () => {
    const read = vi.spyOn(systemApi, 'readApiCompatibility').mockResolvedValueOnce(complete('OK'))
    const w = mount()
    await flushPromises()
    expect(state(w)).toBe('Good')
    read.mockRejectedValue(new ApiRequestError('no', 'OWNER_REQUIRED', 403))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(w.emitted('denied')).toHaveLength(1)
    expect(state(w)).toBe('Not checked')
    const calls = read.mock.calls.length
    await vi.advanceTimersByTimeAsync(120_000)
    expect(read.mock.calls.length).toBe(calls)
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
    expect(state(w)).toBe('Not checked')

    read.mockResolvedValue(RUNNING)
    connection.generation = 3
    await flushPromises()
    const calls = read.mock.calls.length
    w.unmount()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(read.mock.calls.length).toBe(calls)
  })

  it('doesNotReadWhileDisconnected', async () => {
    connection.phase = 'disconnected'
    const read = vi.spyOn(systemApi, 'readApiCompatibility').mockResolvedValue(complete('OK'))
    const w = mount()
    await flushPromises()
    expect(read).not.toHaveBeenCalled()
    expect(state(w)).toBe('Not checked')
  })
})
