import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { connection } from '@/api/accountSession'
import { ApiRequestError } from '@/api/http'
import { systemApi, type OperationsBucket, type OperationsPeriod, type OperationsRoute, type OperationsSummary } from '@/api/systemApi'
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
  overall: route(), routeGroups: [], ...over
})
function summary(over: Partial<OperationsSummary> = {}): OperationsSummary {
  const latest = minute('2026-11-14T12:29:00Z')
  return {
    generatedAt: NOW, foregroundAccounts: { state: 'COMPLETE', exactCount: 4, lowerBound: null },
    databaseSize: { state: 'AVAILABLE', bytes: 5 * 1024 * 1024, measuredAt: '2026-11-14T12:28:00Z' },
    historyAvailable: true, retentionTruncated: false, minutes: [latest], latestMinute: latest,
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

const period = (id: string, kind: 'DAY' | 'WEEK', from: string, over: Partial<OperationsPeriod> = {}): OperationsPeriod => ({
  selectionId: id, kind, fromUtc: from, toUtc: new Date(Date.parse(from) + (kind === 'DAY' ? 1 : 7) * 86_400_000).toISOString().replace('.000Z', 'Z'),
  state: 'CLOSED', coverage: 'FULL', truncated: false, pruned: false, retainedBytes: 100, filename: `activity-${id}.csv`, ...over
})
const DEFAULT_PERIODS: OperationsPeriod[] = [
  period('w1', 'WEEK', '2026-11-09T00:00:00Z', { state: 'OPEN', coverage: 'PARTIAL', pruned: true }),
  period('d-open', 'DAY', '2026-11-14T00:00:00Z', { state: 'OPEN', coverage: 'PARTIAL', truncated: true }),
  period('d-empty', 'DAY', '2026-11-13T00:00:00Z', { retainedBytes: 0 }),
  period('d-old', 'DAY', '2026-11-12T00:00:00Z'),
  period('w0', 'WEEK', '2026-11-02T00:00:00Z')
]

async function mountWith(s: OperationsSummary, periods: OperationsPeriod[] = DEFAULT_PERIODS) {
  vi.spyOn(systemApi, 'readOperationsSummary').mockResolvedValue(s)
  vi.spyOn(systemApi, 'readOperationsPeriods').mockResolvedValue({ available: true, periods })
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
    expect(text(truncated, 'activity-5min')).toContain(`≥ ${(10_000).toLocaleString()}`)
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
    expect(rows[1]).toContain('No stored activity (8 minutes)')
    expect(rows[2]).toContain('Partial')
    const many = Array.from({ length: 30 }, (_, i) => minute(`2026-11-14T11:${String(i).padStart(2, '0')}:00Z`))
    const paged = await mountWith(summary({ minutes: many }))
    expect(paged.findAll('[data-test="activity-row"]')).toHaveLength(10)
    expect(paged.find('[data-test="activity-pager"]').text()).toContain('Page 1 of 3')
  })

  it('refreshReturnsToTheNewestPageWhenTheRetainedWindowShrinks', async () => {
    const many = Array.from({ length: 30 }, (_, i) => minute(`2026-11-14T11:${String(i).padStart(2, '0')}:00Z`))
    const w = await mountWith(summary({ minutes: many }))
    await w.find('[data-test="activity-pager"] button:last-child').trigger('click')
    await w.find('[data-test="activity-pager"] button:last-child').trigger('click')
    expect(text(w, 'activity-pager')).toContain('Page 3 of 3')
    vi.mocked(systemApi.readOperationsSummary).mockResolvedValue(summary({ minutes: many.slice(0, 3) }))
    await w.find('[data-test="activity-refresh"]').trigger('click')
    await flushPromises()
    expect(w.findAll('[data-test="activity-row"]')).toHaveLength(3)
    expect(w.find('[data-test="activity-pager"]').exists()).toBe(false)
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
    const manifest = vi.spyOn(systemApi, 'readOperationsPeriods').mockResolvedValue({ available: true, periods: DEFAULT_PERIODS })
    const local = vi.spyOn(Storage.prototype, 'setItem')
    mount(OwnerActivity, { props: {} })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(10 * 60_000)
    expect(read).toHaveBeenCalledTimes(1)
    expect(manifest).toHaveBeenCalledTimes(1)
    expect(local).not.toHaveBeenCalled()
    vi.useRealTimers()
  })

  it('clearsStateAndRejectsLateAnswersOnDisconnectLeaseChangeAndUnmount', async () => {
    let resolve!: (s: OperationsSummary) => void
    const read = vi.spyOn(systemApi, 'readOperationsSummary').mockReturnValueOnce(new Promise((r) => { resolve = r }))
    vi.spyOn(systemApi, 'readOperationsPeriods').mockResolvedValue({ available: true, periods: DEFAULT_PERIODS })
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
    vi.spyOn(systemApi, 'readOperationsPeriods').mockRejectedValue(new ApiRequestError('no', 'OWNER_REQUIRED', 403))
    const w = mount(OwnerActivity, { props: {} })
    await flushPromises()
    expect(text(w, 'activity-error')).toContain('not available')
    expect(w.find('[data-test="activity-facts"]').exists()).toBe(false)
  })

  function stubDownload() {
    const blobs: Blob[] = []
    vi.stubGlobal('URL', Object.assign(URL, {
      createObjectURL: vi.fn((b: Blob) => { blobs.push(b); return 'blob:x' }), revokeObjectURL: vi.fn()
    }))
    const names: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { names.push(this.download) })
    return { blobs, names }
  }

  it('showsAnObservedIdleMinuteAsKnownZeroButAZeroCoverageGapAsNoObservation', async () => {
    const idle = minute('2026-11-14T12:29:00Z', { overall: null, routeGroups: [] })
    expect(text(await mountWith(summary({ latestMinute: idle, minutes: [idle] })), 'activity-requests')).toContain('0 / min (60 s observed)')
    const gap = minute('2026-11-14T12:29:00Z', { observedSeconds: 0, overall: null })
    expect(text(await mountWith(summary({ latestMinute: gap, minutes: [gap] })), 'activity-requests')).toContain('No observation')
  })

  const submit = (w: ReturnType<typeof mount>) => w.find('[data-test="activity-export"]').trigger('submit')
  const options = (w: ReturnType<typeof mount>, group: string) =>
    w.findAll(`optgroup[label="${group}"] option`).map((o) => o.text())

  it('offersGroupedDaysAndWeeksNewestFirstWithUtcLabelsAndCoverageAndNoEmptyPeriods', async () => {
    const w = await mountWith(summary())
    const days = options(w, 'Days')
    expect(days).toEqual([
      '2026-11-14 00:00 UTC – 2026-11-15 00:00 UTC · open, partial, truncated',
      '2026-11-12 00:00 UTC – 2026-11-13 00:00 UTC · closed'
    ])
    expect(options(w, 'Weeks')).toEqual([
      '2026-11-09 00:00 UTC – 2026-11-16 00:00 UTC · open, partial, pruned',
      '2026-11-02 00:00 UTC – 2026-11-09 00:00 UTC · closed'
    ])
    expect(w.findAll('optgroup')).toHaveLength(2)
  })

  it('selectsTheLatestNonemptyDayByDefaultEvenWhenAWeekIsNewer', async () => {
    const w = await mountWith(summary())
    expect((w.find('[data-test="activity-period"]').element as HTMLSelectElement).value).toBe('d-open')
    const onlyOldDay = await mountWith(summary(), [DEFAULT_PERIODS[0]!, DEFAULT_PERIODS[3]!])
    expect((onlyOldDay.find('[data-test="activity-period"]').element as HTMLSelectElement).value).toBe('d-old')
  })

  it('hasNoRawTimestampRangeOrFormatControlsAndKeepsFileRowCompact', async () => {
    const w = await mountWith(summary())
    const form = w.find('[data-test="activity-export"]')
    expect(form.findAll('input')).toHaveLength(0)
    expect(form.findAll('select')).toHaveLength(1)
    expect(form.findAll('button[type="submit"]')).toHaveLength(1)
    expect(form.text()).not.toMatch(/\bJSON\b|Format|From \(UTC\)|To \(UTC\)/)
    expect(form.find('.owner-activity__pick select').exists() && form.find('.owner-activity__pick button').exists()).toBe(true)
    expect(form.findAll(':scope > p.meta')).toHaveLength(1) // at most two visible sentences; detail is collapsed
    expect(form.find('details').attributes('open')).toBeUndefined()
    const help = form.find('details').text()
    for (const fact of ['UTC boundaries', 'partial, truncated or pruned', 'not browser page-load time', 'same minute']) expect(help).toContain(fact)
    expect(form.text()).toContain('five-minute estimate')
    expect(form.text()).toContain('not zero users or an outage')
    expect(form.text()).not.toMatch(/\b[0-9]+ users\b/)
  })

  it('showsQuietNoActivityCopyAndNoDownloadWhenNoNonemptyPeriodExists', async () => {
    const w = await mountWith(summary(), [DEFAULT_PERIODS[2]!])
    expect(w.find('[data-test="activity-export-empty"]').exists()).toBe(true)
    expect(w.find('[data-test="activity-export-submit"]').exists()).toBe(false)
    const down = await mountWith(summary(), [])
    expect(down.find('[data-test="activity-export-submit"]').exists()).toBe(false)
  })

  it('distinguishesAnUnavailableManifestFromNoActivity', async () => {
    vi.spyOn(systemApi, 'readOperationsSummary').mockResolvedValue(summary())
    vi.spyOn(systemApi, 'readOperationsPeriods').mockResolvedValue({ available: false, periods: [] })
    const w = mount(OwnerActivity, { props: {} })
    await flushPromises()
    expect(w.find('[data-test="activity-export-unavailable"]').exists()).toBe(true)
    expect(w.find('[data-test="activity-export-empty"]').exists()).toBe(false)
    expect(w.find('[data-test="activity-facts"]').exists()).toBe(true)
  })

  it('changingTheSelectionNeitherDownloadsNorPolls', async () => {
    const w = await mountWith(summary())
    const dl = vi.spyOn(systemApi, 'downloadOperationsPeriod')
    await w.find('[data-test="activity-period"]').setValue('w0')
    expect(dl).not.toHaveBeenCalled()
  })

  it('downloadsTheSelectedOpaqueIdWithTheServerFilenameAndRevokesTheBlobUrl', async () => {
    const w = await mountWith(summary())
    const { blobs, names } = stubDownload()
    const dl = vi.spyOn(systemApi, 'downloadOperationsPeriod').mockResolvedValue({ text: 'h\nrow\n', filename: 'server-name.csv' })
    await w.find('[data-test="activity-period"]').setValue('w0')
    await submit(w)
    await flushPromises()
    expect(dl.mock.calls[0]?.[0]).toBe('w0')
    expect(names).toEqual(['server-name.csv'])
    expect(blobs[0]?.type).toBe('text/csv')
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:x')
  })

  it('aStartedDownloadKeepsItsSubmittedIdentifierWhenTheSelectionLaterChanges', async () => {
    const w = await mountWith(summary())
    const { names } = stubDownload()
    let release!: (f: { text: string; filename: string | null }) => void
    const dl = vi.spyOn(systemApi, 'downloadOperationsPeriod').mockReturnValue(new Promise((r) => { release = r }))
    await submit(w)
    await w.find('[data-test="activity-period"]').setValue('w0')
    release({ text: 'x', filename: null })
    await flushPromises()
    expect(dl.mock.calls[0]?.[0]).toBe('d-open')
    expect(names).toEqual(['activity-d-open.csv'])
  })

  it('refreshDuringADownloadAbortsItIndependentlyResetsBusyStateAndAllowsANewDownload', async () => {
    const w = await mountWith(summary())
    const { names } = stubDownload()
    let signal: AbortSignal | undefined
    let release!: (f: { text: string; filename: string | null }) => void
    const read = vi.spyOn(systemApi, 'downloadOperationsPeriod').mockImplementationOnce((_id, s) => {
      signal = s
      return new Promise((r) => { release = r })
    })
    await submit(w)
    expect(w.find('[data-test="activity-export-submit"]').attributes('disabled')).toBeDefined()
    await w.find('[data-test="activity-refresh"]').trigger('click')
    await flushPromises()
    expect(signal?.aborted).toBe(true)
    expect(w.find('[data-test="activity-export-submit"]').attributes('disabled')).toBeUndefined()
    release({ text: 'late', filename: null })
    await flushPromises()
    expect(names).toEqual([])
    read.mockResolvedValue({ text: 'fresh', filename: null })
    await submit(w)
    await flushPromises()
    expect(names).toEqual(['activity-d-open.csv'])
  })

  it('aGoneSelectionShowsASafeMessageAndDoesNotSwitchToAnotherPeriod', async () => {
    const w = await mountWith(summary())
    const { names } = stubDownload()
    const dl = vi.spyOn(systemApi, 'downloadOperationsPeriod').mockRejectedValue(new ApiRequestError('gone', 'PERIOD_GONE', 410))
    await submit(w)
    await flushPromises()
    expect(text(w, 'activity-export-error')).toContain('no longer available')
    expect(names).toEqual([])
    expect(dl).toHaveBeenCalledTimes(1)
    expect((w.find('[data-test="activity-period"]').element as HTMLSelectElement).value).toBe('d-open')
  })

  it('clearsChoicesAndSelectionOnDisconnectAndRejectsALateDownload', async () => {
    const w = await mountWith(summary())
    const { names } = stubDownload()
    let late!: (f: { text: string; filename: string | null }) => void
    vi.spyOn(systemApi, 'downloadOperationsPeriod').mockReturnValue(new Promise((r) => { late = r }))
    await submit(w)
    connection.phase = 'disconnected'
    await flushPromises()
    expect(w.find('[data-test="activity-period"]').exists()).toBe(false)
    late({ text: 'x', filename: null })
    await flushPromises()
    expect(names).toEqual([])
  })

  it('anAccountChangeClearsThePreviousAccountsChoicesBeforeTheNewManifestArrives', async () => {
    const w = await mountWith(summary())
    let next!: (m: { available: boolean; periods: OperationsPeriod[] }) => void
    vi.spyOn(systemApi, 'readOperationsPeriods').mockReturnValue(new Promise((r) => { next = r }))
    connection.generation = 2
    await flushPromises()
    expect(w.find('[data-test="activity-period"]').exists()).toBe(false)
    next({ available: true, periods: [period('x1', 'DAY', '2026-11-01T00:00:00Z')] })
    await flushPromises()
    expect((w.find('[data-test="activity-period"]').element as HTMLSelectElement).value).toBe('x1')
  })

  it.each([401, 403])('withdrawsOwnerStateAndNotifiesTheParentOnADenial%i DuringAPendingExport', async (status) => {
    const w = await mountWith(summary())
    stubDownload()
    let reject!: (e: unknown) => void
    vi.spyOn(systemApi, 'downloadOperationsPeriod').mockReturnValue(new Promise((_, r) => { reject = r }))
    await submit(w)
    reject(new ApiRequestError('no', 'OWNER_REQUIRED', status))
    await flushPromises()
    expect(w.emitted('denied')).toHaveLength(1)
    expect(w.find('[data-test="activity-period"]').exists()).toBe(false)
    expect(w.find('[data-test="activity-facts"]').exists()).toBe(false)
    expect(w.find('[data-test="activity-history-table"]').exists()).toBe(false)
    expect(text(w, 'activity-error')).toContain('not available')
  })

  it('exposesAccessibleTableHeadingsAndKeyboardReachableControls', async () => {
    const w = await mountWith(summary())
    expect(w.find('table caption').exists()).toBe(true)
    expect(w.findAll('thead th[scope="col"]').length).toBeGreaterThan(4)
    expect(w.find('#owner-activity-heading').text()).toBe('Application activity')
    expect(w.find('label[for="owner-activity-period"]').exists()).toBe(true)
    expect(w.find('[data-test="activity-history-toggle"]').element.tagName).toBe('SUMMARY')
  })
})
