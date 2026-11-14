import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { connection } from '@/api/accountSession'
import { ApiRequestError } from '@/api/http'
import { systemApi, type OperationsBucket, type OperationsRoute, type OperationsSummary } from '@/api/systemApi'
import OwnerActivity from '../OwnerActivity.vue'

const NOW = '2026-11-14T12:30:00Z'
const route = (over: Partial<OperationsRoute> = {}): OperationsRoute => ({
  group: 'ALL', requests: 30, http2xx3xx: 27, http4xx: 2, http5xx: 1, failedIncomplete: 3, meanMs: 10, maxMs: 40,
  p50: { upperBoundMs: 25, overflow: false, display: '<=25 ms' }, p95: { upperBoundMs: 100, overflow: false, display: '<=100 ms' },
  ...over
})
const minute = (startUtc: string, over: Partial<OperationsBucket> = {}): OperationsBucket => ({
  startUtc, observedSeconds: 60, completeness: 'COMPLETE',
  foregroundAccounts: { state: 'COMPLETE', exactCount: 2, lowerBound: null },
  activityPeak: null, minuteBuckets: null, overall: route(), routeGroups: [], ...over
})
function summary(over: Partial<OperationsSummary> = {}): OperationsSummary {
  const latest = minute('2026-11-14T12:29:00Z')
  return {
    generatedAt: NOW, foregroundAccounts: { state: 'COMPLETE', exactCount: 4, lowerBound: null },
    databaseSize: { state: 'AVAILABLE', bytes: 5 * 1024 * 1024, measuredAt: '2026-11-14T12:28:00Z' },
    historyAvailable: true, retentionTruncated: false, minutes: [latest], hours: [], latestMinute: latest,
    oracleConsole: { configured: false, url: null }, ...over
  }
}
const text = (w: ReturnType<typeof mount>, id: string): string => w.find(`[data-test="${id}"]`).text()

beforeEach(() => {
  connection.phase = 'connected'
  connection.generation = 1
  connection.leaseVersion = 1
})
afterEach(() => {
  connection.phase = 'disconnected'
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function mountWith(s: OperationsSummary) {
  vi.spyOn(systemApi, 'readOperationsSummary').mockResolvedValue(s)
  const w = mount(OwnerActivity, { props: { activeLast60Minutes: 17 } })
  await flushPromises()
  return w
}

describe('OwnerActivity', () => {
  it('showsSeparateFiveAndSixtyMinuteValuesWithoutOnlinePlayerWording', async () => {
    const w = await mountWith(summary())
    expect(text(w, 'activity-5min')).toContain('4')
    expect(text(w, 'activity-5min')).toContain('Distinct verified GW2 accounts with foreground activity; estimate.')
    expect(text(w, 'activity-60min')).toBe('17')
    expect(w.text()).not.toMatch(/online|concurrent|connected tabs|open sessions/i)
    expect(text(w, 'activity-latency')).toContain('does not include browser rendering')
    expect(text(w, 'activity-responses')).toContain('4xx 2')
    expect(text(w, 'activity-responses')).toContain('5xx 1')
    expect(text(w, 'activity-responses')).toContain('failed/incomplete 3')
  })

  it('distinguishesAvailableStaleUnavailableAndActualZeroDatabaseSize', async () => {
    const at = '2026-11-14T12:00:00Z'
    expect(text(await mountWith(summary({ databaseSize: { state: 'AVAILABLE', bytes: 0, measuredAt: at } })), 'activity-db')).toContain('0 B')
    expect(text(await mountWith(summary({ databaseSize: { state: 'STALE', bytes: 2048, measuredAt: at } })), 'activity-db')).toMatch(/2\.0 KiB.*Stale/)
    const unavailable = text(await mountWith(summary({ databaseSize: { state: 'UNAVAILABLE', bytes: null, measuredAt: null } })), 'activity-db')
    expect(unavailable).toContain('Unavailable')
    expect(unavailable).not.toContain('0 B')
  })

  it('showsPartialAndTruncatedLowerBoundsAndOverflowAsOverFiveSeconds', async () => {
    const truncated = await mountWith(summary({ foregroundAccounts: { state: 'TRUNCATED', exactCount: null, lowerBound: 10000 } }))
    expect(text(truncated, 'activity-5min')).toContain('≥ 10,000')
    expect(text(truncated, 'activity-5min')).toContain('lower bound')
    const partial = await mountWith(summary({ foregroundAccounts: { state: 'PARTIAL_STARTUP', exactCount: 3, lowerBound: null } }))
    expect(text(partial, 'activity-5min')).toContain('≥ 3')
    expect(text(partial, 'activity-5min')).toContain('Partial')
    const slow = route({ p50: { upperBoundMs: 5000, overflow: true, display: '>5 s' }, p95: { upperBoundMs: 5000, overflow: true, display: '>5 s' } })
    const w = await mountWith(summary({ latestMinute: minute('2026-11-14T12:29:00Z', { overall: slow }) }))
    expect(text(w, 'activity-latency')).toContain('p95 >5 s')
    expect(text(w, 'activity-latency')).not.toMatch(/Infinity|≤ 0 ms/)
  })

  it('doesNotPresentAnOldBucketOrZeroCoverageAsACurrentRate', async () => {
    const old = minute('2026-11-14T10:00:00Z')
    const stale = await mountWith(summary({ latestMinute: old, minutes: [old] }))
    expect(text(stale, 'activity-requests')).toContain('No recent observation')
    expect(text(stale, 'activity-requests')).toContain('2026-11-14 10:00 UTC')
    expect(text(stale, 'activity-latency')).not.toContain('p95')
    const noCoverage = minute('2026-11-14T12:29:00Z', { observedSeconds: 0, overall: route({ requests: 0 }) })
    const w = await mountWith(summary({ latestMinute: noCoverage, minutes: [noCoverage] }))
    expect(text(w, 'activity-requests')).toContain('No observation')
    expect(text(w, 'activity-requests')).not.toMatch(/^0/)
  })

  it('rendersGapsAsNoObservationAndObservedZeroAsZeroInAPaginatedTable', async () => {
    const zero = minute('2026-11-14T12:29:00Z', { overall: route({ requests: 0, http4xx: 0, http5xx: 0, failedIncomplete: 0, p50: null, p95: null }) })
    const before = minute('2026-11-14T12:20:00Z', { completeness: 'PARTIAL' })
    const w = await mountWith(summary({ minutes: [before, zero], latestMinute: zero }))
    const rows = w.findAll('[data-test="activity-row"], [data-test="activity-gap"]').map((r) => r.text())
    expect(rows[0]).toContain('12:29 UTC')
    expect(rows[0]).toContain('0 ')
    expect(rows[1]).toContain('No observation (8 minutes)')
    expect(rows[2]).toContain('Partial')
    const many = Array.from({ length: 30 }, (_, i) => minute(`2026-11-14T11:${String(i).padStart(2, '0')}:00Z`))
    const paged = await mountWith(summary({ minutes: many }))
    expect(paged.findAll('[data-test="activity-row"]')).toHaveLength(10)
    expect(paged.find('[data-test="activity-pager"]').text()).toContain('Page 1 of 3')
  })

  it('showsHourlyActivityAsPeakNeverATotal', async () => {
    const hour: OperationsBucket = minute('2026-11-14T11:00:00Z', {
      foregroundAccounts: null, minuteBuckets: 60,
      activityPeak: { maxReportedActive: 9, anyPartialStartup: false, anyTruncated: true, anyUnavailable: false }
    })
    const w = await mountWith(summary({ hours: [hour] }))
    await w.find('[data-test="activity-view-hour"]').trigger('click')
    const row = w.find('[data-test="activity-row"]').text()
    expect(row).toContain('peak ≥ 9')
    expect(row).toContain('Truncated')
  })

  it('showsConfiguredOracleLinkOrTheTruthfulUnconfiguredText', async () => {
    const off = await mountWith(summary())
    expect(text(off, 'activity-oracle')).toBe('Host CPU and memory history: Oracle Monitoring link not configured.')
    const on = await mountWith(summary({ oracleConsole: { configured: true, url: 'https://cloud.oracle.com/monitoring' } }))
    const a = on.find('[data-test="activity-oracle"] a')
    expect(a.text()).toBe('Open Oracle host metrics')
    expect(a.attributes('href')).toBe('https://cloud.oracle.com/monitoring')
  })

  it('doesNotPollAndStoresNothingInBrowserStorage', async () => {
    vi.useFakeTimers()
    const read = vi.spyOn(systemApi, 'readOperationsSummary').mockResolvedValue(summary())
    const local = vi.spyOn(Storage.prototype, 'setItem')
    mount(OwnerActivity, { props: {} })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(10 * 60_000)
    expect(read).toHaveBeenCalledTimes(1)
    expect(local).not.toHaveBeenCalled()
    vi.useRealTimers()
  })

  it('clearsStateAndRejectsLateAnswersOnDisconnectLeaseChangeAndUnmount', async () => {
    let resolve!: (s: OperationsSummary) => void
    const read = vi.spyOn(systemApi, 'readOperationsSummary').mockReturnValueOnce(new Promise((r) => { resolve = r }))
    const w = mount(OwnerActivity, { props: {} })
    await flushPromises()
    connection.leaseVersion = 2
    read.mockResolvedValue(summary())
    await flushPromises()
    expect(w.find('[data-test="activity-facts"]').exists()).toBe(true)
    resolve(summary({ foregroundAccounts: { state: 'COMPLETE', exactCount: 999, lowerBound: null } }))
    await flushPromises()
    expect(text(w, 'activity-5min')).toContain('4')
    expect(text(w, 'activity-5min')).not.toContain('999')
    connection.phase = 'disconnected'
    await flushPromises()
    expect(w.find('[data-test="activity-facts"]').exists()).toBe(false)
    let late!: (s: OperationsSummary) => void
    connection.phase = 'connected'
    read.mockReturnValueOnce(new Promise((r) => { late = r }))
    await flushPromises()
    w.unmount()
    late(summary())
    await flushPromises()
  })

  it('turnsADenialIntoANeutralMessageWithoutData', async () => {
    vi.spyOn(systemApi, 'readOperationsSummary').mockRejectedValue(new ApiRequestError('no', 'OWNER_REQUIRED', 403))
    const w = mount(OwnerActivity, { props: {} })
    await flushPromises()
    expect(text(w, 'activity-error')).toContain('not available')
    expect(w.find('[data-test="activity-facts"]').exists()).toBe(false)
  })

  it('downloadsThroughABlobUrlThatIsRevokedImmediatelyAndRejectsAfterDisconnect', async () => {
    const w = await mountWith(summary())
    const create = vi.fn(() => 'blob:x')
    const revoke = vi.fn()
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke }))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const exportRead = vi.spyOn(systemApi, 'readOperationsExport').mockResolvedValue('a,b\n')
    await w.find('[data-test="activity-export"]').trigger('submit')
    await flushPromises()
    expect(exportRead.mock.calls[0]?.slice(0, 2)).toEqual(['csv', 'minute'])
    expect(create).toHaveBeenCalledTimes(1)
    expect(click).toHaveBeenCalledTimes(1)
    expect(revoke).toHaveBeenCalledWith('blob:x')
    let late!: (s: string) => void
    exportRead.mockReturnValueOnce(new Promise((r) => { late = r }))
    await w.find('[data-test="activity-export"]').trigger('submit')
    connection.phase = 'disconnected'
    await flushPromises()
    late('x')
    await flushPromises()
    expect(create).toHaveBeenCalledTimes(1)
  })

  it('exposesAccessibleTableHeadingsAndKeyboardReachableControls', async () => {
    const w = await mountWith(summary())
    expect(w.find('table caption').exists()).toBe(true)
    expect(w.findAll('thead th[scope="col"]').length).toBeGreaterThan(4)
    expect(w.find('#owner-activity-heading').text()).toBe('Application activity')
    expect(w.find('[data-test="activity-view-minute"]').attributes('aria-pressed')).toBe('true')
    expect(w.find('[data-test="activity-history-toggle"]').element.tagName).toBe('SUMMARY')
  })
})
