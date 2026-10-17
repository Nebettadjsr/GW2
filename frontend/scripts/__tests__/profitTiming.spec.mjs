/**
 * Focused checks for the Crafting Profit measurement attribution (STORY-PERF-004).
 *
 * The live harness (`scripts/profit-page-perf.mjs`) cannot prove its own rules: a real page settles
 * once, in one way, and the cases that matter here — a replacement that changes no displayed value,
 * a placeholder that appears before the table, a response that stopped carrying its envelope — are
 * exactly the ones a real run does not reproduce on demand. So the rules live in `profitTiming.mjs`
 * and are driven here by scripted observation streams: every timestamp below is this file's own.
 *
 * What each group establishes:
 *   - a later input generation is excluded from the initial interval and measured separately,
 *     *including* when every displayed value it renders is identical (`TARGET_ARCHITECTURE.md` §17);
 *   - a loading placeholder, a partially rendered table and a table without its controls cannot
 *     finish a measurement, and the complete-set opening additionally needs the requested count;
 *   - the displayed-value read and the enabled selection control are decided **inside the initial
 *     calculation context**: a page that only becomes interactive once the refreshed replacement
 *     arrives is not a usable cached page, and its interactivity evidence is never borrowed;
 *   - the refresh's start and terminal outcome come from the task status of the work the envelope
 *     advertised, separately from the recalculation and its rendering, and the indicator's
 *     termination is checked against the final result being rendered (UD-015 items 4-6);
 *   - metadata comes from the backend's own answer, and an answer missing it fails the evidence
 *     check instead of being measured (`TEST_STRATEGY.md` §34).
 */
import { describe, expect, it } from 'vitest'
import {
  EvidenceError,
  SETTLE_MS,
  attribute,
  completenessOf,
  inputStateOf,
  missingAccountSources,
  requireCalculationEvidence
} from '../profitTiming.mjs'

const ACCOUNT_SOURCES = ['ACCOUNT_RECIPES', 'BANK', 'CHARACTERS', 'MATERIALS']

const SETTINGS = {
  useOwnMats: true,
  allowBuying: false,
  maxBuyCopper: 10_000,
  listingSell: false,
  listingBuy: false,
  allowDailyCrafts: false
}

function sources(state) {
  return ACCOUNT_SOURCES.map((source) => ({
    source,
    fetchedAt: '2026-10-16T09:00:00Z',
    state
  }))
}

function coverage(covered) {
  return {
    requiredItems: 3_205,
    freshItems: covered ? 3_205 : 2_900,
    staleItems: covered ? 0 : 280,
    missingItems: covered ? 0 : 25,
    oldestFetchedAt: '2026-10-16T08:55:00Z',
    newestFetchedAt: '2026-10-16T09:00:00Z',
    covered
  }
}

function envelope({ calculationId, provisional = false, accountState = 'FRESH', covered = true }) {
  return {
    requestId: `request-${calculationId}`,
    calculationId,
    evaluatedAt: '2026-10-16T09:00:00Z',
    provisional,
    resultAvailable: true,
    accountSources: sources(accountState),
    tradingPost: coverage(covered),
    outstandingWork: provisional
      ? [
          {
            kind: 'ACCOUNT',
            sources: ['CHARACTERS'],
            taskId: 'task-1',
            taskState: 'RUNNING',
            taskStatusUrl: '/api/sync/tasks/task-1',
            reuse: 'ADMITTED'
          }
        ]
      : [],
    failures: []
  }
}

/** Deep-copied, so a case that damages one answer's evidence cannot reach the shared literals. */
function calculation(at, options) {
  return {
    at,
    status: 'CALCULATED',
    rowCount: options.rowCount ?? 3_205,
    settings: { ...SETTINGS },
    scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 },
    freshness: envelope(options)
  }
}

/** A sampled page state, defaulting to a complete, interactive, quiescent table. */
function state(overrides = {}) {
  return {
    rows: 204,
    first: '1234/+14g/7',
    last: '9876/+1g/2',
    displayedValue: '+14g 2s 11c',
    selectionEnabled: true,
    summary: 'Showing 204 of 204 matching recipes · 3205 calculated for this scope',
    scope: '· scope ALL',
    loading: false,
    error: false,
    pending: false,
    controls: true,
    staleIndicator: false,
    refreshFailed: false,
    refreshRetry: false,
    visiblePending: 0,
    ...overrides
  }
}

/**
 * One `/api/sync/tasks/{id}` answer, in the shape `web.dto.SyncTaskStatusResponse` returns: the
 * harness's own `at`, and the backend's own lifecycle instants, which are what measure the
 * refresh's execution without any rendering in it.
 */
function taskRead(at, taskId, taskState, overrides = {}) {
  const terminal = taskState === 'SUCCEEDED' || taskState === 'FAILED'
  return {
    at,
    httpStatus: 200,
    taskId,
    operation: 'ACCOUNT_SYNC',
    state: taskState,
    submittedAt: '2026-10-16T09:00:00.000Z',
    startedAt: taskState === 'PENDING' ? null : '2026-10-16T09:00:01.000Z',
    finishedAt: terminal ? '2026-10-16T09:00:31.000Z' : null,
    failure: null,
    ...overrides
  }
}

/**
 * Builds the sample stream the live harness produces: one sample per poll, each carrying the
 * displayed-state fingerprint the attribution compares.
 */
function samplesFrom(entries, { pollMs = 250 } = {}) {
  const samples = []
  for (const entry of entries) {
    for (let at = entry.from; at < entry.to; at += pollMs) {
      samples.push({ at, state: entry.state, displayed: JSON.stringify(entry.state) })
    }
  }
  return samples
}

describe('a later input generation is measured separately from the initial page', () => {
  /**
   * The case §17 exists for: the cached page is complete at 2 500 ms, the refreshed calculation
   * answers 40 s later, and the refreshed table renders values that happen to be *identical* to the
   * provisional ones — only the refreshing indicator goes. Nothing about that replacement may move
   * the initial reading.
   */
  const cached = state({ staleIndicator: true })
  const replaced = state({ staleIndicator: false })

  const observations = {
    calculations: [
      calculation(900, { calculationId: 'generation-stale', provisional: true, accountState: 'STALE' }),
      calculation(41_000, { calculationId: 'generation-fresh' })
    ],
    // The page polled the advertised task until it reported a terminal state at 39 s; the
    // recalculation it then asked for answered at 41 s.
    taskReads: [
      taskRead(1_100, 'task-1', 'RUNNING'),
      taskRead(20_000, 'task-1', 'RUNNING'),
      taskRead(39_000, 'task-1', 'SUCCEEDED')
    ],
    samples: samplesFrom([
      { from: 0, to: 1_500, state: state({ rows: 0, loading: true, controls: false, staleIndicator: false }) },
      { from: 1_500, to: 2_500, state: state({ rows: 120, staleIndicator: true, visiblePending: 4 }) },
      { from: 2_500, to: 41_250, state: cached },
      { from: 41_250, to: 48_000, state: replaced }
    ])
  }

  it('ends the initial interval at the last change inside the initial context', () => {
    const report = attribute(observations)

    expect(report.initial.calculationId).toBe('generation-stale')
    expect(report.initial.lastChangeAt).toBe(2_500)
    expect(report.initial.settled).toBe(true)
    expect(report.initial.provisional).toBe(true)
    expect(report.initial.inputState).toBe('expired-account')
    // The replacement is 44 s later; had it been inside the sample, this would be ~41 250.
    expect(report.initial.lastChangeAt).toBeLessThan(SETTLE_MS + 2_500)
  })

  it('measures the identical-value replacement as its own phase through final rendering', () => {
    const report = attribute(observations)

    expect(report.replacements).toHaveLength(1)
    const replacement = report.replacements[0]
    expect(replacement.calculationId).toBe('generation-fresh')
    expect(replacement.provisional).toBe(false)
    expect(replacement.firstAt).toBe(41_000)
    // The only displayed change the refreshed generation made was dropping the indicator.
    expect(replacement.lastChangeAt).toBe(41_250)
    expect(replacement.renderMs).toBe(250)
    expect(replacement.settled).toBe(true)

    expect(report.refresh.outcome).toBe('replaced')
    expect(report.refresh.admittedAt).toBe(900)
    // Terminal is the task's own terminal state, 39 000 ms — not the 41 250 ms at which the
    // indicator went, which is the client's own event and two seconds later.
    expect(report.refresh.terminalAt).toBe(39_000)
    expect(report.refresh.startToTerminalMs).toBe(38_100)
    expect(report.refresh.indicatorGoneAt).toBe(41_250)
    // The recalculation and its rendering are after the refresh, and reported apart from it.
    expect(report.refresh.terminalToFinalAnswerMs).toBe(2_000)
    expect(report.refresh.navigationToFinalMs).toBe(41_250)
    expect(report.refresh.refreshedRenderMs).toBe(250)
    expect(report.violations).toEqual([])
  })

  /**
   * The same case with the settle window widened past the replacement, so the early quiescence
   * break cannot be what keeps the replacement out: the only thing left is the context window. This
   * is the assertion that fails if the initial interval is ever allowed to see a later generation's
   * samples — which is exactly what the pre-contract harness did, by folding the number of
   * calculations the page had issued into the quiescence fingerprint.
   */
  it('never lets a later generation’s samples into the initial interval', () => {
    const report = attribute({ ...observations, settleMs: 60_000 })

    expect(report.initial.lastChangeAt).toBe(2_500)
    expect(report.initial.settled).toBe(false)
    expect(report.initial.cutByReplacement).toBe(true)
    expect(report.replacements[0].lastChangeAt).toBe(41_250)
  })

  it('treats a resubmission that captured the same generation as the same context', () => {
    const report = attribute({
      calculations: [
        calculation(900, { calculationId: 'generation-stale', provisional: true, accountState: 'STALE' }),
        // The recovery cycle asked again and the backend captured the very same inputs.
        calculation(20_000, { calculationId: 'generation-stale', provisional: true, accountState: 'STALE' }),
        calculation(41_000, { calculationId: 'generation-fresh' })
      ],
      samples: observations.samples
    })

    expect(report.contexts).toHaveLength(2)
    expect(report.initial.answers).toHaveLength(2)
    expect(report.initial.lastChangeAt).toBe(2_500)
  })

  it('reports an initial context that a replacement cut short instead of claiming it settled', () => {
    const report = attribute({
      calculations: [
        calculation(900, { calculationId: 'generation-stale', provisional: true, accountState: 'STALE' }),
        calculation(3_000, { calculationId: 'generation-fresh' })
      ],
      samples: samplesFrom([
        { from: 0, to: 1_500, state: state({ rows: 0, loading: true, controls: false }) },
        { from: 1_500, to: 3_000, state: state({ rows: 204, staleIndicator: true }) },
        { from: 3_000, to: 9_000, state: replaced }
      ])
    })

    expect(report.initial.settled).toBe(false)
    expect(report.initial.cutByReplacement).toBe(true)
    expect(report.violations.join(' | ')).toContain('never became quiescent')
  })

  it('flags an indicator removed before the final result was rendered', () => {
    const report = attribute({
      calculations: [
        calculation(900, { calculationId: 'generation-stale', provisional: true, accountState: 'STALE' }),
        calculation(41_000, { calculationId: 'generation-fresh' })
      ],
      taskReads: [taskRead(1_100, 'task-1', 'RUNNING'), taskRead(39_000, 'task-1', 'SUCCEEDED')],
      samples: samplesFrom([
        { from: 0, to: 2_500, state: state({ rows: 204, staleIndicator: true }) },
        // The animation stops at 2.5 s although the refreshed result only arrives at 41 s.
        { from: 2_500, to: 41_250, state: state({ rows: 204, staleIndicator: false }) },
        { from: 41_250, to: 48_000, state: replaced }
      ])
    })

    expect(report.violations.join(' | ')).toContain('before the final result was rendered')
    expect(report.refresh.indicatorGoneAt).toBe(2_500)
  })

  it('attributes an opening that issued no calculation to the generation already displayed', () => {
    const report = attribute({
      calculations: [],
      inherited: {
        calculationId: 'generation-fresh',
        provisional: false,
        resultAvailable: true,
        inputState: 'fresh',
        requestedRowCount: 3_205
      },
      samples: samplesFrom([
        { from: 0, to: 100, state: state({ rows: 0, controls: false }) },
        { from: 100, to: 5_000, state: replaced }
      ])
    })

    expect(report.initial.inherited).toBe(true)
    expect(report.initial.calculationId).toBe('generation-fresh')
    expect(report.initial.lastChangeAt).toBe(100)
    expect(report.initial.settled).toBe(true)
  })
})

/**
 * The evidence that the *cached* page was usable has to be produced while the cached page is what
 * is on screen. These cases are the ones a live run cannot stage: a real page is interactive from
 * the moment it renders, so only a scripted stream can show what happens when it is not.
 */
describe('the initial cached page must be interactive in its own context', () => {
  const inert = state({ staleIndicator: true, selectionEnabled: false })
  const unreadable = state({ staleIndicator: true, displayedValue: '' })
  const usableReplacement = state({ displayedValue: '+31g 7s 0c', first: '4321/+31g/9' })

  const calculations = [
    calculation(900, { calculationId: 'generation-stale', provisional: true, accountState: 'STALE' }),
    calculation(30_000, { calculationId: 'generation-fresh' })
  ]
  const taskReads = [taskRead(1_100, 'task-1', 'RUNNING'), taskRead(29_000, 'task-1', 'SUCCEEDED')]

  it('does not complete the initial interval while only the replacement is interactive', () => {
    const report = attribute({
      calculations,
      taskReads,
      samples: samplesFrom([
        { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
        // Rows, controls, no notice, quiescent for 28 s — but the row cannot be selected.
        { from: 1_000, to: 30_250, state: inert },
        { from: 30_250, to: 38_000, state: usableReplacement }
      ])
    })

    expect(report.initial.settled).toBe(false)
    expect(report.initial.missing).toContain('the row selection control is absent or disabled')
    expect(report.initial.displayedValueRead).toBeNull()
    expect(report.initial.selectionEnabled).toBeNull()
    expect(report.violations.join(' | ')).toContain('never became quiescent')
    // The replacement is interactive, and that must not be reported as the cached page's usability.
    expect(report.replacements[0].settled).toBe(true)
    expect(report.replacements[0].displayedValueRead).toBe('+31g 7s 0c')
  })

  it('does not complete the initial interval while no displayed value can be read', () => {
    const report = attribute({
      calculations,
      taskReads,
      samples: samplesFrom([
        { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
        { from: 1_000, to: 30_250, state: unreadable },
        { from: 30_250, to: 38_000, state: usableReplacement }
      ])
    })

    expect(report.initial.settled).toBe(false)
    expect(report.initial.missing).toContain('no displayed value could be read from a result row')
  })

  it('reports the read taken on the sample that completed the initial context, not a later one', () => {
    const cached = state({ staleIndicator: true, displayedValue: '+14g 2s 11c' })
    const report = attribute({
      calculations,
      taskReads,
      samples: samplesFrom([
        { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
        { from: 1_000, to: 30_250, state: cached },
        { from: 30_250, to: 38_000, state: usableReplacement }
      ])
    })

    expect(report.initial.settled).toBe(true)
    expect(report.initial.settledAt).toBe(4_000)
    expect(report.initial.settledAt).toBeLessThan(report.replacements[0].firstAt)
    expect(report.initial.displayedValueRead).toBe('+14g 2s 11c')
    expect(report.initial.selectionEnabled).toBe(true)
    // The value the refreshed page shows is a different reading, kept with its own context.
    expect(report.replacements[0].displayedValueRead).toBe('+31g 7s 0c')
    expect(report.violations).toEqual([])
  })

  /**
   * The reported number has to be the instant a usable page was read, not an instant whose
   * usability was established afterwards: the completion is the *boundary* sample — the state the
   * settle window then confirmed — so the read sits at the end of the measured interval rather than
   * a settle window beyond it.
   */
  it('reports the completion at the boundary sample, with the read at that same instant', () => {
    const cached = state({ staleIndicator: true, displayedValue: '+14g 2s 11c' })
    const report = attribute({
      calculations,
      taskReads,
      samples: samplesFrom([
        { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
        { from: 1_000, to: 30_250, state: cached },
        { from: 30_250, to: 38_000, state: usableReplacement }
      ])
    })

    expect(report.initial.completedAt).toBe(1_000)
    expect(report.initial.displayedValueReadAt).toBe(report.initial.completedAt)
    expect(report.initial.displayedValueRead).toBe('+14g 2s 11c')
    expect(report.initial.selectionEnabled).toBe(true)
    // The confirmation that the window elapsed is three seconds later, and is not the measurement.
    expect(report.initial.settledAt).toBe(4_000)
    expect(report.initial.settledAt - report.initial.completedAt).toBe(SETTLE_MS)
    expect(report.violations).toEqual([])
  })

  it('moves the completion to the instant the displayed value first became readable', () => {
    // Rows, controls and quiescence for four seconds — but nothing can be read off the row, so
    // that state cannot be the boundary even though the settle window elapses on it.
    const report = attribute({
      calculations,
      taskReads,
      samples: samplesFrom([
        { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
        { from: 1_000, to: 5_000, state: unreadable },
        { from: 5_000, to: 30_250, state: state({ staleIndicator: true, displayedValue: '+14g 2s 11c' }) },
        { from: 30_250, to: 38_000, state: usableReplacement }
      ])
    })

    expect(report.initial.completedAt).toBe(5_000)
    expect(report.initial.displayedValueReadAt).toBe(5_000)
    expect(report.initial.displayedValueRead).toBe('+14g 2s 11c')
    expect(report.initial.settledAt).toBe(8_000)
    expect(report.violations).toEqual([])
  })

  it('moves the completion to the instant the row selection control became enabled', () => {
    const report = attribute({
      calculations,
      taskReads,
      samples: samplesFrom([
        { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
        { from: 1_000, to: 6_000, state: inert },
        { from: 6_000, to: 30_250, state: state({ staleIndicator: true }) },
        { from: 30_250, to: 38_000, state: usableReplacement }
      ])
    })

    expect(report.initial.completedAt).toBe(6_000)
    expect(report.initial.displayedValueReadAt).toBe(6_000)
    expect(report.initial.selectionEnabled).toBe(true)
    expect(report.initial.settledAt).toBe(9_000)
    expect(report.violations).toEqual([])
  })

  it('reports no time at all for a context that never completed', () => {
    const report = attribute({
      calculations,
      taskReads,
      samples: samplesFrom([
        { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
        { from: 1_000, to: 30_250, state: inert },
        { from: 30_250, to: 38_000, state: usableReplacement }
      ])
    })

    expect(report.initial.completedAt).toBeNull()
    expect(report.initial.displayedValueReadAt).toBeNull()
    expect(report.replacements[0].completedAt).toBe(30_250)
    expect(report.replacements[0].displayedValueReadAt).toBe(30_250)
  })

  it('names the interactivity requirements in what a sampled state is missing', () => {
    expect(completenessOf(state({ selectionEnabled: false, displayedValue: null })).missing).toEqual([
      'no displayed value could be read from a result row',
      'the row selection control is absent or disabled'
    ])
  })
})

/**
 * UD-015 items 4-6 and §17's third figure: what the refresh did, read from the backend's own task
 * status for the work the envelope advertised — and kept apart from the recalculation it causes and
 * from that result's rendering.
 */
describe('the refresh is measured against the work the envelope advertised', () => {
  const cached = state({ staleIndicator: true })
  const refreshed = state({ displayedValue: '+31g 7s 0c', first: '4321/+31g/9' })

  /** Account work finishes at 39 s; the Trading Post work the next provisional answer admits at 52 s. */
  const twoPieces = {
    calculations: [
      calculation(900, { calculationId: 'generation-stale', provisional: true, accountState: 'STALE' }),
      {
        ...calculation(40_000, {
          calculationId: 'generation-half',
          provisional: true,
          covered: false
        }),
        freshness: {
          ...envelope({ calculationId: 'generation-half', provisional: true, covered: false }),
          outstandingWork: [
            {
              kind: 'TRADING_POST',
              sources: [],
              taskId: 'task-2',
              taskState: 'PENDING',
              taskStatusUrl: '/api/sync/tasks/task-2',
              reuse: 'ADMITTED'
            }
          ]
        }
      },
      calculation(55_000, { calculationId: 'generation-fresh' })
    ],
    taskReads: [
      taskRead(1_100, 'task-1', 'RUNNING'),
      taskRead(39_000, 'task-1', 'SUCCEEDED'),
      taskRead(41_000, 'task-2', 'PENDING', { operation: 'PRICE_REFRESH_PROFIT', submittedAt: '2026-10-16T09:00:40.000Z' }),
      taskRead(52_000, 'task-2', 'SUCCEEDED', {
        operation: 'PRICE_REFRESH_PROFIT',
        submittedAt: '2026-10-16T09:00:40.000Z',
        startedAt: '2026-10-16T09:00:41.000Z',
        finishedAt: '2026-10-16T09:00:52.000Z'
      })
    ],
    samples: samplesFrom([
      { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
      { from: 1_000, to: 55_250, state: cached },
      { from: 55_250, to: 62_000, state: refreshed }
    ])
  }

  it('follows every piece of advertised work to its own terminal task state', () => {
    const report = attribute(twoPieces)

    expect(report.refresh.advertised.map((task) => task.taskId)).toEqual(['task-1', 'task-2'])
    expect(report.refresh.advertised.map((task) => task.kind)).toEqual(['ACCOUNT', 'TRADING_POST'])
    expect(report.refresh.advertised.map((task) => task.terminalState)).toEqual([
      'SUCCEEDED',
      'SUCCEEDED'
    ])
    expect(report.refresh.advertised[1].advertisedAt).toBe(40_000)
    expect(report.refresh.outcome).toBe('replaced')
    // Terminal is the last piece of work finishing, not the first.
    expect(report.refresh.terminalAt).toBe(52_000)
    expect(report.refresh.startToTerminalMs).toBe(51_100)
    expect(report.violations).toEqual([])
  })

  it('separates refresh execution from the recalculation and its rendering', () => {
    const report = attribute(twoPieces)
    const refresh = report.refresh

    // The backend's own instants: 09:00:01 (account started) → 09:00:52 (prices finished).
    expect(refresh.taskExecutionMs).toBe(51_000)
    expect(refresh.advertised[0].executionMs).toBe(30_000)
    expect(refresh.advertised[1].executionMs).toBe(11_000)
    expect(refresh.advertised[1].queuedMs).toBe(1_000)
    // Terminal work → the recalculation answered → that result rendered: three separate figures,
    // none of which is inside the initial 4 000 ms reading.
    expect(refresh.terminalToFinalAnswerMs).toBe(3_000)
    expect(refresh.refreshedRenderMs).toBe(250)
    expect(refresh.navigationToFinalMs).toBe(55_250)
    expect(report.initial.lastChangeAt).toBe(1_000)
    expect(report.initial.settled).toBe(true)
  })

  it('verifies the indicator ended only after the final result was rendered', () => {
    const report = attribute(twoPieces)

    expect(report.refresh.finalRenderedAt).toBe(55_250)
    expect(report.refresh.finalRenderDistinguishable).toBe(true)
    expect(report.refresh.indicatorGoneAt).toBe(55_250)
    expect(report.violations).toEqual([])

    // The same stream with the indicator dropped one sample before the refreshed rows appear.
    const early = attribute({
      ...twoPieces,
      samples: samplesFrom([
        { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
        { from: 1_000, to: 55_000, state: cached },
        { from: 55_000, to: 55_250, state: state({ staleIndicator: false }) },
        { from: 55_250, to: 62_000, state: refreshed }
      ])
    })

    expect(early.refresh.indicatorGoneAt).toBe(55_000)
    expect(early.refresh.finalRenderedAt).toBe(55_250)
    expect(early.violations.join(' | ')).toContain('before the final result was rendered')
  })

  it('reports a failed refresh from the task status, with the retry the page offers', () => {
    const report = attribute({
      calculations: [
        calculation(900, { calculationId: 'generation-stale', provisional: true, accountState: 'STALE' })
      ],
      taskReads: [
        taskRead(1_100, 'task-1', 'RUNNING'),
        taskRead(22_000, 'task-1', 'FAILED', {
          finishedAt: '2026-10-16T09:00:22.000Z',
          failure: { error: 'UPSTREAM_UNAVAILABLE', message: 'the GW2 API did not answer' }
        })
      ],
      samples: samplesFrom([
        { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
        { from: 1_000, to: 22_500, state: cached },
        { from: 22_500, to: 30_000, state: state({ refreshFailed: true, refreshRetry: true }) }
      ])
    })

    expect(report.refresh.outcome).toBe('failed')
    expect(report.refresh.advertised[0].terminalState).toBe('FAILED')
    expect(report.refresh.advertised[0].failure.error).toBe('UPSTREAM_UNAVAILABLE')
    expect(report.refresh.retryOffered).toBe(true)
    expect(report.refresh.navigationToFinalMs).toBeNull()
    // The cached rows stayed, so the initial reading is still the complete usable page it was.
    expect(report.initial.settled).toBe(true)
    expect(report.initial.lastChangeAt).toBe(1_000)
  })

  it('refuses to infer the refresh from the indicator when the advertised work was never polled', () => {
    const report = attribute({
      calculations: [
        calculation(900, { calculationId: 'generation-stale', provisional: true, accountState: 'STALE' }),
        calculation(30_000, { calculationId: 'generation-fresh' })
      ],
      taskReads: [],
      samples: samplesFrom([
        { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
        { from: 1_000, to: 30_250, state: cached },
        { from: 30_250, to: 38_000, state: refreshed }
      ])
    })

    expect(report.refresh.terminalAt).toBeNull()
    expect(report.refresh.startToTerminalMs).toBeNull()
    expect(report.refresh.uncorrelated).toEqual(['task-1'])
    expect(report.refresh.outcome).toBe('replaced-unconfirmed')
    expect(report.violations.join(' | ')).toContain('no task status was observed for advertised')
    expect(report.violations.join(' | ')).toContain('terminal outcome is not established')
  })
})

describe('a placeholder or partial table cannot finish a measurement', () => {
  it('keeps waiting while only the loading placeholder is quiescent', () => {
    const report = attribute({
      calculations: [calculation(900, { calculationId: 'generation-fresh' })],
      samples: samplesFrom([
        // Quiescent for 10 s — but it is the placeholder, so it must not end the measurement.
        { from: 0, to: 10_000, state: state({ rows: 0, loading: true, controls: false }) },
        { from: 10_000, to: 16_000, state: state() }
      ])
    })

    expect(report.initial.lastChangeAt).toBe(10_000)
    expect(report.initial.settled).toBe(true)
    expect(report.initial.firstRowsAt).toBe(10_000)
  })

  it('does not finish on the first rows, nor while an in-viewport image is loading', () => {
    const report = attribute({
      calculations: [calculation(900, { calculationId: 'generation-fresh' })],
      samples: samplesFrom([
        { from: 0, to: 1_000, state: state({ rows: 0, loading: true, controls: false }) },
        // First rows at 1 000 ms, then quiescent for 5 s with icons still loading.
        { from: 1_000, to: 6_000, state: state({ rows: 60, visiblePending: 12 }) },
        { from: 6_000, to: 12_000, state: state() }
      ])
    })

    expect(report.initial.firstRowsAt).toBe(1_000)
    expect(report.initial.lastChangeAt).toBe(6_000)
    expect(report.initial.settled).toBe(true)
  })

  it('requires the result controls before a quiescent table counts as complete', () => {
    const report = attribute({
      calculations: [calculation(900, { calculationId: 'generation-fresh' })],
      samples: samplesFrom([
        { from: 0, to: 9_000, state: state({ controls: false }) },
        { from: 9_000, to: 15_000, state: state() }
      ])
    })

    expect(report.initial.lastChangeAt).toBe(9_000)
    expect(report.initial.settled).toBe(true)
  })

  it('requires every requested row for a complete-set opening', () => {
    const partial = {
      calculations: [calculation(900, { calculationId: 'generation-fresh', rowCount: 3_205 })],
      samples: samplesFrom([{ from: 0, to: 20_000, state: state({ rows: 3_000 }) }]),
      completeSet: true
    }

    const truncated = attribute(partial)
    expect(truncated.initial.settled).toBe(false)
    expect(truncated.initial.missing.join(' ')).toContain('3000 of 3205 requested rows')
    expect(truncated.violations.join(' | ')).toContain('never became quiescent')

    const whole = attribute({
      ...partial,
      samples: samplesFrom([
        { from: 0, to: 4_000, state: state({ rows: 3_000 }) },
        { from: 4_000, to: 12_000, state: state({ rows: 3_205 }) }
      ])
    })
    expect(whole.initial.settled).toBe(true)
    expect(whole.initial.lastChangeAt).toBe(4_000)
  })

  it('never reports a pending no-usable-inputs page as a complete page', () => {
    const report = attribute({
      calculations: [
        {
          at: 900,
          status: 'PENDING',
          rowCount: null,
          settings: SETTINGS,
          scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 },
          freshness: {
            ...envelope({ calculationId: 'generation-none', provisional: true }),
            resultAvailable: false,
            accountSources: ACCOUNT_SOURCES.map((source) => ({ source, fetchedAt: null, state: 'MISSING' }))
          }
        }
      ],
      samples: samplesFrom([
        { from: 0, to: 20_000, state: state({ rows: 0, pending: true, staleIndicator: true }) }
      ])
    })

    expect(report.initial.resultAvailable).toBe(false)
    expect(report.initial.settled).toBe(false)
    expect(report.initial.complete).toBe(false)
    expect(missingAccountSources(report.initial.answers[0].freshness)).toEqual(ACCOUNT_SOURCES)
    // The no-usable-cache case is not reported as a budget breach of a page that never existed.
    expect(report.violations.join(' | ')).not.toContain('the initial page was not complete')
  })

  it('states exactly what a sampled state is missing', () => {
    expect(completenessOf(state())).toEqual({ complete: true, missing: [] })
    expect(completenessOf(state({ rows: 0, loading: true, controls: false })).missing).toEqual([
      'no row is displayed',
      'the loading notice is still shown',
      'the result controls are absent'
    ])
    expect(completenessOf(state({ error: true })).missing).toEqual(['a request error is shown'])
  })
})

describe('the measured inputs come from the backend answer, and a gap fails', () => {
  it('reports the echoed settings and scope of the measured request', () => {
    const report = attribute({
      calculations: [calculation(900, { calculationId: 'generation-fresh' })],
      samples: samplesFrom([{ from: 0, to: 6_000, state: state() }])
    })

    expect(report.effective.settings).toEqual(SETTINGS)
    expect(report.effective.scope.kind).toBe('ALL')
    expect(report.effective.rowCount).toBe(3_205)
    expect(report.initial.requestedRowCount).toBe(3_205)
  })

  it('classifies each required input-cache state from the envelope itself', () => {
    expect(inputStateOf(envelope({ calculationId: 'a' }))).toBe('fresh')
    expect(inputStateOf(envelope({ calculationId: 'b', accountState: 'STALE' }))).toBe('expired-account')
    expect(inputStateOf(envelope({ calculationId: 'c', covered: false }))).toBe('expired-tp')
    expect(inputStateOf(envelope({ calculationId: 'd', accountState: 'STALE', covered: false }))).toBe(
      'both-expired'
    )
  })

  it.each([
    ['no envelope at all', (answer) => delete answer.freshness],
    ['no calculation id', (answer) => delete answer.freshness.calculationId],
    ['no request id', (answer) => delete answer.freshness.requestId],
    ['no evaluation instant', (answer) => delete answer.freshness.evaluatedAt],
    ['no provisional flag', (answer) => delete answer.freshness.provisional],
    ['no account-source freshness', (answer) => (answer.freshness.accountSources = [])],
    [
      'an omitted required account source',
      (answer) => (answer.freshness.accountSources = answer.freshness.accountSources.slice(1))
    ],
    ['no Trading Post coverage', (answer) => delete answer.freshness.tradingPost],
    ['no outstanding-work list', (answer) => delete answer.freshness.outstandingWork],
    ['no effective settings', (answer) => delete answer.settings],
    ['effective settings without the sell basis', (answer) => delete answer.settings.listingSell],
    ['no effective scope', (answer) => delete answer.scope],
    ['no requested row count', (answer) => delete answer.rowCount]
  ])('fails the evidence check on a response with %s', (_label, damage) => {
    const answer = calculation(900, { calculationId: 'generation-fresh' })
    damage(answer)

    expect(() => requireCalculationEvidence(answer)).toThrow(EvidenceError)
    expect(() =>
      attribute({
        calculations: [answer],
        samples: samplesFrom([{ from: 0, to: 6_000, state: state() }])
      })
    ).toThrow(EvidenceError)
  })

  it('fails rather than measures when nothing was sampled or nothing can be attributed', () => {
    expect(() =>
      attribute({ calculations: [calculation(900, { calculationId: 'generation-fresh' })], samples: [] })
    ).toThrow(/No page state was sampled/)

    expect(() =>
      attribute({ calculations: [], samples: samplesFrom([{ from: 0, to: 1_000, state: state() }]) })
    ).toThrow(/no calculation and inherited no displayed generation/)
  })
})
