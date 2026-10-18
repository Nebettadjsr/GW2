/**
 * Attribution of a Crafting Profit page measurement to the calculation contexts it observed
 * (`TARGET_ARCHITECTURE.md` §17, `TEST_STRATEGY.md` §34, UD-015).
 *
 * The stale-while-revalidate contract made a single quiescence sample unusable on its own: a
 * provisional page is replaced, minutes later, by the refreshed calculation, so "the last observed
 * change of the displayed page" is no longer the end of the initial interval — it is the end of the
 * *refresh*. §17 budgets only the first one and explicitly excludes the second.
 *
 * So the measurement is attributed instead of totalled. This module is the attribution, kept pure
 * and separate from the browser driver so the rules can be exercised against scripted observation
 * streams (`__tests__/profitTiming.spec.mjs`) rather than only against a live page:
 *
 *   - **Contexts come from the backend's own freshness envelope**, never from a request count. Each
 *     distinct `freshness.calculationId` is one input generation; a resubmission that captured the
 *     same generation is the *same* context, because nothing the page displays can have changed.
 *   - **The initial interval ends inside its own context.** Only samples taken before the next
 *     context's response arrived can move it, which is what stops a later replacement from
 *     extending it. Quiescence still decides the moment, and the settle window is still excluded.
 *   - **A placeholder cannot finish a measurement.** Completion requires the rows, the controls, no
 *     loading/pending/error notice, no in-viewport image still loading, a displayed value actually
 *     read off a result row and an enabled selection control — and, for a complete-set opening,
 *     exactly the requested row count. Because completion is decided *inside* the context window,
 *     that interactivity is established while the initial cached result is the thing on screen; a
 *     page that only becomes interactive once the refreshed replacement arrives cannot satisfy it.
 *   - **The reported time is the instant of the sample that satisfied all of that.** A context
 *     completes at its *boundary sample* — the sample from which the displayed state, including the
 *     value read off a row and that row's selection control, stayed unchanged through the settle
 *     window — and that sample must itself be a complete usable page. So the successful
 *     displayed-value read and the enabled-selection check are inside the measured interval, at its
 *     end, rather than evidence gathered three seconds after the number was taken; and any change
 *     to either of them moves the boundary instead of being invisible to it.
 *   - **The refresh is correlated with the work the backend advertised.** Its start and its terminal
 *     outcome come from the task-status answers for the `outstandingWork` entries of the provisional
 *     envelope — not from the indicator disappearing — and the recalculation and its rendering are
 *     reported as separate figures after that terminal outcome.
 *   - **Missing metadata fails loudly.** A response without a usable freshness envelope, effective
 *     settings or row count is an evidence gap, reported as such rather than silently measured
 *     (`TEST_STRATEGY.md` §34: "Record effective settings from an actual backend echo or rendered
 *     control, not a missing reporting hook").
 *
 * Nothing here knows about Playwright, and nothing here waits: it is fed `{at, …}` observations
 * whose `at` is milliseconds since the navigation action, and returns what each phase measured.
 */

/** How long the displayed state must stay unchanged before a context counts as quiescent. */
export const SETTLE_MS = 3_000

/** §17's limit on the initial complete usable page, quoted so a report can classify a reading. */
export const LIMIT_MS = 7_000

/**
 * The account sources a Crafting Profit calculation requires, spelled as the backend reports them:
 * `application.CalculationInputVersions.REQUIRED_ACCOUNT_SOURCES` takes these names from
 * `repo.AccountSyncStateRepository`, where the recipe marker is `ACCOUNT_RECIPES`, and
 * `web.CalculationFreshnessMapper` passes them through unrenamed. Account Luck is deliberately
 * absent: the calculation does not use it.
 */
export const REQUIRED_ACCOUNT_SOURCES = ['ACCOUNT_RECIPES', 'BANK', 'CHARACTERS', 'MATERIALS']

/** The four input-cache states §17 requires to be distinguished, as this harness labels them. */
export const INPUT_STATES = ['fresh', 'expired-account', 'expired-tp', 'both-expired']

/**
 * Task lifecycle states `web.dto.SyncTaskStatusResponse` reports, and the two that end one. A task
 * status is the only thing that says when the advertised refresh really started and finished:
 * the page's indicator says when the *client* stopped waiting, which is a different event.
 */
export const TERMINAL_TASK_STATES = ['SUCCEEDED', 'FAILED']

export class EvidenceError extends Error {
  constructor(message) {
    super(message)
    this.name = 'EvidenceError'
  }
}

/* --------------------------------------------------------------- evidence requirements --------- */

/**
 * Checks one `/api/crafting/profit` answer carries the evidence a measurement is built on, and
 * throws when it does not. Called for every observed calculation, so a response shape that stops
 * carrying the envelope ends the run instead of producing a report with holes in it.
 */
export function requireCalculationEvidence(calculation, where = 'a calculation response') {
  const envelope = calculation?.freshness
  if (envelope === null || envelope === undefined) {
    throw new EvidenceError(`${where} carried no freshness envelope, so its input generation is unknown.`)
  }
  for (const field of ['requestId', 'calculationId', 'evaluatedAt']) {
    if (typeof envelope[field] !== 'string' || envelope[field].length === 0) {
      throw new EvidenceError(`${where} carried no ${field} in its freshness envelope.`)
    }
  }
  if (typeof envelope.provisional !== 'boolean' || typeof envelope.resultAvailable !== 'boolean') {
    throw new EvidenceError(`${where} did not state whether its result is provisional and available.`)
  }
  if (!Array.isArray(envelope.accountSources) || envelope.accountSources.length === 0) {
    throw new EvidenceError(`${where} reported no account-source freshness.`)
  }
  for (const source of REQUIRED_ACCOUNT_SOURCES) {
    if (!envelope.accountSources.some((entry) => entry.source === source)) {
      throw new EvidenceError(`${where} omitted the required account source ${source}.`)
    }
  }
  const prices = envelope.tradingPost
  if (prices === null || prices === undefined || typeof prices.covered !== 'boolean') {
    throw new EvidenceError(`${where} reported no Trading Post coverage.`)
  }
  if (!Array.isArray(envelope.outstandingWork) || !Array.isArray(envelope.failures)) {
    throw new EvidenceError(`${where} reported neither outstanding refresh work nor failures.`)
  }
  // §34's effective-input requirement: the echo itself, not a page hook that may have been renamed.
  const settings = calculation.settings
  if (settings === null || settings === undefined) {
    throw new EvidenceError(`${where} echoed no effective settings, so the measured inputs are unrecorded.`)
  }
  for (const field of [
    'useOwnMats',
    'allowBuying',
    'maxBuyCopper',
    'listingSell',
    'listingBuy',
    'allowDailyCrafts'
  ]) {
    if (settings[field] === undefined || settings[field] === null) {
      throw new EvidenceError(`${where} echoed effective settings without ${field}.`)
    }
  }
  if (calculation.scope === null || calculation.scope === undefined || typeof calculation.scope.kind !== 'string') {
    throw new EvidenceError(`${where} echoed no effective scope.`)
  }
  if (envelope.resultAvailable && typeof calculation.rowCount !== 'number') {
    throw new EvidenceError(`${where} reported a result without a requested row count.`)
  }
  return calculation
}

/** The input-cache state an envelope describes, in the vocabulary §17's scenario list uses. */
export function inputStateOf(envelope) {
  const accountExpired = envelope.accountSources.some(
    (source) => REQUIRED_ACCOUNT_SOURCES.includes(source.source) && source.state !== 'FRESH'
  )
  const pricesExpired = !envelope.tradingPost.covered
  if (accountExpired && pricesExpired) return 'both-expired'
  if (accountExpired) return 'expired-account'
  if (pricesExpired) return 'expired-tp'
  return 'fresh'
}

/** Required account sources that never completed: the no-usable-cache case §17 separates out. */
export function missingAccountSources(envelope) {
  return envelope.accountSources
    .filter((source) => REQUIRED_ACCOUNT_SOURCES.includes(source.source) && source.state === 'MISSING')
    .map((source) => source.source)
}

/* --------------------------------------------------------------- completeness ------------------ */

/**
 * Whether one sampled page state is a complete *usable* page under §17 — the predicate that makes a
 * loading placeholder, a half-rendered table, a page without its controls and a page whose values
 * cannot be read or whose rows cannot be selected unable to finish a measurement.
 *
 * The interactivity half is the reason this is a predicate over a *sample* rather than something
 * the driver does once at the end: a sample belongs to exactly one calculation context, so
 * requiring the displayed-value read and the enabled selection control here is what ties them to
 * the initial cached result instead of to whatever replaced it later.
 *
 * @param requestedRowCount the backend's own `rowCount`; enforced only for a complete-set opening,
 *                          because the opening view deliberately displays at most its display limit
 */
export function completenessOf(state, { completeSet = false, requestedRowCount = null } = {}) {
  const missing = []
  if (!(state.rows > 0)) missing.push('no row is displayed')
  if (state.loading) missing.push('the loading notice is still shown')
  if (state.pending) missing.push('the no-usable-inputs pending notice is shown')
  if (state.error) missing.push('a request error is shown')
  if (!state.controls) missing.push('the result controls are absent')
  if (state.visiblePending > 0) missing.push(`${state.visiblePending} in-viewport image(s) still loading`)
  if (typeof state.displayedValue !== 'string' || state.displayedValue.trim().length === 0) {
    missing.push('no displayed value could be read from a result row')
  }
  if (state.selectionEnabled !== true) missing.push('the row selection control is absent or disabled')
  if (completeSet) {
    if (requestedRowCount === null) missing.push('the requested row count is unknown')
    else if (state.rows !== requestedRowCount) {
      missing.push(`${state.rows} of ${requestedRowCount} requested rows are displayed`)
    }
  }
  return { complete: missing.length === 0, missing }
}

/**
 * The part of a sampled state that is *the result on screen*: rows, their identities, the summary,
 * the notices, the controls and what can be read and selected.
 *
 * Deliberately narrower than the quiescence fingerprint, which also carries the refreshing
 * indicator and every icon's loading state. "The final result has been rendered" has to be
 * observable without reading the indicator whose termination is being checked against it, and
 * without an icon finishing its decode being mistaken for the replacement arriving.
 */
export function contentOf(state) {
  return JSON.stringify({
    rows: state.rows,
    first: state.first ?? null,
    last: state.last ?? null,
    summary: state.summary ?? null,
    loading: state.loading,
    pending: state.pending,
    error: state.error,
    controls: state.controls,
    displayedValue: state.displayedValue ?? null,
    selectionEnabled: state.selectionEnabled ?? null
  })
}

/**
 * The key one context's quiescence is judged on: the driver's own displayed-state fingerprint and
 * the result content, which carries the value read off a result row and that row's selection state.
 *
 * Folding the interactivity read in here is what puts it *inside* the measured interval. The
 * reported completion is the instant the state behind this key was first observed, so a value that
 * only became readable — or a selection control that only became enabled — later moves that instant
 * forward instead of being settled over. A driver whose fingerprint already carries both reads
 * produces the same key either way; the rule holds without depending on that.
 */
function quiescenceKeyOf(sample) {
  return `${sample.displayed}|${contentOf(sample.state)}`
}

/* --------------------------------------------------------------- attribution -------------------- */

/**
 * Splits the observed calculations into input generations.
 *
 * Consecutive answers carrying the same `calculationId` are one context: the backend captured the
 * same published inputs, so no displayed value can differ between them and a redundant resubmission
 * must not look like a replacement.
 */
function contextsOf(calculations, inherited) {
  const contexts = []
  for (const calculation of calculations) {
    const envelope = calculation.freshness
    const last = contexts.at(-1)
    if (last !== undefined && last.calculationId === envelope.calculationId) {
      last.answers.push(calculation)
      // A later answer for the same generation can still change what is outstanding.
      last.provisional = envelope.provisional
      last.resultAvailable = envelope.resultAvailable
      continue
    }
    contexts.push({
      calculationId: envelope.calculationId,
      firstAt: calculation.at,
      provisional: envelope.provisional,
      resultAvailable: envelope.resultAvailable,
      inputState: inputStateOf(envelope),
      requestedRowCount: calculation.rowCount ?? null,
      answers: [calculation],
      inherited: false
    })
  }

  // An opening that issued no calculation at all — this application re-renders client-held rows on
  // in-application navigation — is attributed to the generation already on screen, not to none.
  if (contexts.length === 0 && inherited !== null && inherited !== undefined) {
    contexts.push({ ...inherited, firstAt: 0, answers: [], inherited: true })
  }

  for (const [index, context] of contexts.entries()) {
    context.windowEnd = contexts[index + 1]?.firstAt ?? Number.POSITIVE_INFINITY
  }
  return contexts
}

/**
 * When one context's displayed state last changed, whether it then stayed unchanged long enough to
 * count as quiescent, and whether the sample it last changed to was a complete usable page.
 *
 * The window starts at the context's own first answer (at the navigation action for the initial
 * one) and ends when the next context's answer arrives — which is exactly what keeps a replacement
 * out of the interval before it. `baseline` is the displayed state at the window's start, so a
 * replacement whose values happen to be identical to the ones already on screen registers as "no
 * change after the response" rather than as an unmeasurable context.
 *
 * The *boundary sample* — the one the reported time names — is the sample the quiescent state was
 * first observed on, and it is the sample completeness is decided on. Everything §17 requires of a
 * complete usable page, the displayed-value read and the enabled selection control included, is
 * therefore true **at the reported instant**; the later sample that merely confirms the settle
 * window elapsed is reported separately and is never the measurement.
 */
function settleWithin(context, samples, baseline, { settleMs, completeSet }) {
  const window = samples.filter(
    (sample) => sample.at >= context.firstAt && sample.at < context.windowEnd
  )

  let previous = baseline === null || baseline === undefined ? null : quiescenceKeyOf(baseline)
  const baselineContent = baseline === null || baseline === undefined ? null : contentOf(baseline.state)
  let lastChangeAt = context.firstAt
  let firstRowsAt = null
  let renderedAt = null
  let settled = false
  let last = null
  let settledSample = null
  let boundarySample = null
  let confirmedBoundary = null

  for (const sample of window) {
    last = sample
    if (firstRowsAt === null && sample.state.rows > 0) firstRowsAt = sample.at
    // When this context's own result reached the screen, read without the indicator and without
    // the icons: the content this context renders differs from the content that preceded it.
    if (renderedAt === null && baselineContent !== null && contentOf(sample.state) !== baselineContent) {
      renderedAt = sample.at
    }
    const key = quiescenceKeyOf(sample)
    if (key !== previous) {
      previous = key
      lastChangeAt = sample.at
      boundarySample = sample
      continue
    }
    if (sample.at - lastChangeAt < settleMs) continue
    // Decided on the boundary sample, not on this one: the time this reports is the boundary's.
    const boundary = boundarySample ?? window[0]
    const completeness = completenessOf(boundary.state, {
      completeSet,
      requestedRowCount: context.requestedRowCount
    })
    if (completeness.complete) {
      settled = true
      settledSample = sample
      confirmedBoundary = boundary
      break
    }
  }

  const completedAt = confirmedBoundary === null ? null : Math.round(confirmedBoundary.at)

  const completeness =
    last === null
      ? { complete: false, missing: ['no sample was taken in this context'] }
      : completenessOf(last.state, { completeSet, requestedRowCount: context.requestedRowCount })

  return {
    samples: window.length,
    lastChangeAt: Math.round(lastChangeAt),
    /**
     * What this context measured: the instant of the boundary sample — the complete usable page
     * the settle window then confirmed. Null while the context never completed, so an unfinished
     * context cannot report a time at all. Equal to `lastChangeAt` except in the one case where a
     * context's own first sample already matched the state before it (a replacement that rendered
     * identical content), where the boundary is that first sample rather than the answer's arrival.
     */
    completedAt,
    renderMs: Math.round(lastChangeAt - context.firstAt),
    firstRowsAt: firstRowsAt === null ? null : Math.round(firstRowsAt),
    /**
     * When this context's own content replaced what preceded it, or null when it rendered content
     * identical to it (then only the indicator can have changed, and nothing distinguishes the two
     * on screen). Not computed for the initial context, which has nothing before it.
     */
    renderedAt: renderedAt === null ? null : Math.round(renderedAt),
    settled,
    /** True when the next context's answer arrived before this one had settled. */
    cutByReplacement: !settled && context.windowEnd !== Number.POSITIVE_INFINITY,
    complete: completeness.complete,
    missing: completeness.missing,
    /** When the settle window had elapsed on that boundary — a confirmation, not the measurement. */
    settledAt: settledSample === null ? null : Math.round(settledSample.at),
    /**
     * The interactivity evidence, read off the boundary sample — the one `completedAt` names: the
     * value a result row displayed and whether its selection control was enabled, both true at the
     * reported instant and inside this context's own window. Null while the context never
     * completed, so a context cannot borrow another one's usability or report a time without them.
     */
    displayedValueRead: confirmedBoundary?.state.displayedValue ?? null,
    selectionEnabled: confirmedBoundary === null ? null : confirmedBoundary.state.selectionEnabled === true,
    displayedValueReadAt: completedAt,
    finalState: last?.state ?? null
  }
}

/**
 * Attributes one opening's observations to its contexts and phases.
 *
 * @param {object} input
 * @param {Array} input.calculations  `/api/crafting/profit` answers, `at` ms after the navigation
 * @param {Array} input.samples       sampled page states, `at` ms after the navigation
 * @param {Array} input.taskReads     `/api/sync/tasks/{id}` answers, `at` ms after the navigation
 * @param {object|null} input.inherited  the generation already displayed when this opening started
 * @param {boolean} input.completeSet whether this opening asked for the complete requested set
 * @param {number} input.settleMs
 */
export function attribute({
  calculations,
  samples,
  taskReads = [],
  inherited = null,
  completeSet = false,
  settleMs = SETTLE_MS
}) {
  for (const calculation of calculations) {
    requireCalculationEvidence(calculation, `the calculation answered at ${Math.round(calculation.at)} ms`)
  }
  if (samples.length === 0) throw new EvidenceError('No page state was sampled for this opening.')

  const ordered = [...samples].sort((left, right) => left.at - right.at)
  const contexts = contextsOf([...calculations].sort((left, right) => left.at - right.at), inherited)
  if (contexts.length === 0) {
    throw new EvidenceError(
      'This opening observed no calculation and inherited no displayed generation, so nothing can be ' +
        'attributed to an input generation.'
    )
  }

  /** The sample on screen when a context's answer arrived: the baseline its changes are read against. */
  const stateBefore = (at) => ordered.filter((candidate) => candidate.at < at).at(-1) ?? null

  const measured = contexts.map((context, index) => ({
    ...context,
    phase: index === 0 ? 'initial' : 'replacement',
    ...settleWithin(context, ordered, index === 0 ? null : stateBefore(context.firstAt), {
      settleMs,
      completeSet
    })
  }))

  const initial = measured[0]
  const replacements = measured.slice(1)
  const finalContext = [...measured].reverse().find((context) => !context.provisional) ?? null
  const refresh = refreshPhase(initial, measured, ordered, [...taskReads].sort((l, r) => l.at - r.at))

  return {
    contexts: measured,
    initial,
    replacements,
    final: finalContext,
    refresh,
    violations: violationsOf(initial, measured, ordered, finalContext, refresh),
    /** The settings and scope the backend echoed for the measured request (§34). */
    effective: initial.answers.at(0) ?? null
  }
}

/** Milliseconds between two ISO-8601 instants the backend reported, or null if either is absent. */
function spanMs(from, to) {
  if (typeof from !== 'string' || typeof to !== 'string') return null
  const start = Date.parse(from)
  const end = Date.parse(to)
  if (Number.isNaN(start) || Number.isNaN(end)) return null
  return end - start
}

/**
 * The refresh work the provisional answers advertised, deduplicated by task.
 *
 * Successive provisional answers re-advertise the same task while it runs, and a recovery cycle
 * that resubmits after one task succeeded can be handed a *second* piece of work (Trading Post
 * quotes after the account sources). Both belong to the same refresh, so the list spans every
 * provisional context rather than only the first answer.
 */
function advertisedWork(measured) {
  const advertised = []
  for (const context of measured) {
    if (!context.provisional) continue
    for (const answer of context.answers) {
      for (const work of answer.freshness.outstandingWork) {
        const key = work.taskId ?? `${work.kind}:${work.sources.join('+')}`
        if (advertised.some((entry) => entry.key === key)) continue
        advertised.push({
          key,
          kind: work.kind,
          sources: work.sources ?? [],
          taskId: work.taskId ?? null,
          taskState: work.taskState ?? null,
          reuse: work.reuse ?? null,
          statusUrl: work.taskStatusUrl ?? null,
          advertisedAt: Math.round(answer.at),
          advertisedBy: context.calculationId,
          // `useCraftingProfit.isFollowable`: what the page itself can poll a status for.
          followable:
            (work.taskStatusUrl ?? null) !== null &&
            work.taskState !== 'UNKNOWN' &&
            work.taskState !== 'NONE'
        })
      }
    }
  }
  return advertised
}

/**
 * What the task-status answers said about one advertised piece of work.
 *
 * Two clocks, kept apart on purpose: `at` is this harness's own, milliseconds since the navigation
 * action, and `submittedAt`/`startedAt`/`finishedAt` are the backend's own instants for the task.
 * The second pair is what measures the refresh's *execution* without any of the page's rendering in
 * it; the first is what places that execution inside the opening being measured.
 */
function taskObservation(work, taskReads) {
  const readings = taskReads.filter((read) => read.taskId === work.taskId)
  const terminal = readings.find((read) => TERMINAL_TASK_STATES.includes(read.state)) ?? null
  const first = readings.at(0) ?? null
  const last = readings.at(-1) ?? null

  return {
    ...work,
    readings: readings.length,
    states: [...new Set(readings.map((read) => read.state))],
    firstObservedAt: first === null ? null : Math.round(first.at),
    firstObservedState: first?.state ?? null,
    lastObservedState: last?.state ?? null,
    terminalObservedAt: terminal === null ? null : Math.round(terminal.at),
    terminalState: terminal?.state ?? null,
    failure: terminal?.failure ?? null,
    /** The backend's own lifecycle instants, from the terminal status body. */
    submittedAt: terminal?.submittedAt ?? last?.submittedAt ?? null,
    startedAt: terminal?.startedAt ?? last?.startedAt ?? null,
    finishedAt: terminal?.finishedAt ?? null,
    queuedMs: spanMs(terminal?.submittedAt ?? null, terminal?.startedAt ?? null),
    executionMs: spanMs(terminal?.startedAt ?? null, terminal?.finishedAt ?? null)
  }
}

/**
 * The refresh itself: from the answer that admitted the work to the terminal outcome of that work,
 * established from the task-status answers rather than from the indicator going away — and reported
 * apart from the recalculation and the rendering that follow it. Never merged into the initial
 * interval, and never turned into a budget: §17 establishes no numerical refresh-speed threshold.
 */
function refreshPhase(initial, measured, samples, taskReads) {
  if (!initial.provisional) return null

  const advertised = advertisedWork(measured)
  const tasks = advertised.map((work) => taskObservation(work, taskReads))
  const followable = tasks.filter((task) => task.followable)
  const observed = followable.filter((task) => task.readings > 0)
  const terminated = observed.filter((task) => task.terminalObservedAt !== null)
  const failedTask = terminated.find((task) => task.terminalState === 'FAILED') ?? null

  const indicatorSeenAt = samples.find((sample) => sample.state.staleIndicator)?.at ?? null
  const afterIndicator =
    indicatorSeenAt === null ? [] : samples.filter((sample) => sample.at >= indicatorSeenAt)
  const goneSample = afterIndicator.find((sample) => !sample.state.staleIndicator) ?? null
  const failedSample = samples.find((sample) => sample.state.refreshFailed) ?? null
  const finalContext = [...measured].reverse().find((context) => !context.provisional) ?? null

  // Terminal once every piece of followable advertised work reported a terminal task state.
  const allTerminal = followable.length > 0 && terminated.length === followable.length
  const terminalAt = allTerminal
    ? Math.max(...terminated.map((task) => task.terminalObservedAt))
    : null

  let outcome = 'still-running'
  if (failedTask !== null || failedSample !== null) outcome = 'failed'
  else if (allTerminal) outcome = finalContext === null ? 'work-finished' : 'replaced'
  // The page moved on, but the advertised work never reported a terminal state of its own: the
  // replacement is visible and the refresh's own outcome is not established. Said, not smoothed
  // over — this is the inference the indicator alone would have hidden.
  else if (finalContext !== null && goneSample !== null) outcome = 'replaced-unconfirmed'

  const admittedAt = Math.round(initial.firstAt)
  const finalRenderedAt =
    finalContext === null ? null : (finalContext.renderedAt ?? Math.round(finalContext.firstAt))

  return {
    /** The answer that admitted the work — the start of the refresh, on this opening's clock. */
    admittedAt,
    advertised: tasks,
    followableCount: followable.length,
    observedCount: observed.length,
    /** Advertised, followable, and never polled: the refresh could not be correlated with it. */
    uncorrelated: followable.filter((task) => task.readings === 0).map((task) => task.key),
    terminalAt,
    terminalStates: terminated.map((task) => `${task.kind}:${task.terminalState}`),
    /** Work admitted → its terminal task outcome. Nothing about rendering is inside this figure. */
    startToTerminalMs: terminalAt === null ? null : terminalAt - admittedAt,
    /** The backend's own execution span for the advertised work, across the tasks it ran. */
    taskExecutionMs: (() => {
      const starts = terminated.map((task) => task.startedAt).filter((value) => value !== null)
      const ends = terminated.map((task) => task.finishedAt).filter((value) => value !== null)
      if (starts.length === 0 || ends.length === 0) return null
      return spanMs(
        starts.reduce((earliest, value) => (Date.parse(value) < Date.parse(earliest) ? value : earliest)),
        ends.reduce((latest, value) => (Date.parse(value) > Date.parse(latest) ? value : latest))
      )
    })(),
    outcome,
    /** Terminal work → the recalculation it caused answered: the refresh is already over here. */
    terminalToFinalAnswerMs:
      terminalAt === null || finalContext === null ? null : Math.round(finalContext.firstAt) - terminalAt,
    /** The refreshed calculation's own rendering, measured inside its own context. */
    refreshedRenderMs: finalContext === null ? null : finalContext.renderMs,
    /** Navigation action → the refreshed result rendered; §17's third separate figure. */
    navigationToFinalMs: finalContext === null ? null : finalContext.lastChangeAt,
    finalRenderedAt,
    /** Distinguishable only when the replacement rendered different content from the cached one. */
    finalRenderDistinguishable: finalContext === null ? null : finalContext.renderedAt !== null,
    indicatorSeenAt: indicatorSeenAt === null ? null : Math.round(indicatorSeenAt),
    indicatorGoneAt: goneSample === null ? null : Math.round(goneSample.at),
    retryOffered: failedSample === null ? null : failedSample.state.refreshRetry === true
  }
}

/**
 * Contract breaches the observations themselves prove, returned rather than thrown so one report
 * can carry every one of them.
 */
function violationsOf(initial, measured, samples, finalContext, refresh) {
  const violations = []

  if (!initial.settled) {
    violations.push(
      `the initial context ${initial.calculationId} never became quiescent within its own window ` +
        `(${initial.missing.join('; ') || 'replaced before settling'})`
    )
  }
  if (!initial.complete && initial.resultAvailable) {
    violations.push(`the initial page was not complete: ${initial.missing.join('; ')}`)
  }
  // The reported time has to be the instant a usable page was actually read, not a time with its
  // usability established afterwards. True by construction above; asserted because it is the whole
  // claim the reported number makes.
  if (initial.settled) {
    if (
      initial.displayedValueRead === null ||
      initial.selectionEnabled !== true ||
      initial.displayedValueReadAt !== initial.completedAt
    ) {
      violations.push(
        `the initial reading of ${initial.completedAt} ms is not the instant a displayed value was ` +
          `read (${JSON.stringify(initial.displayedValueRead)} at ${initial.displayedValueReadAt} ms) ` +
          `with the row selection control enabled (${initial.selectionEnabled})`
      )
    }
  }

  if (initial.provisional && refresh !== null) {
    if (refresh.indicatorSeenAt === null) {
      violations.push(
        'a provisional result was displayed without the stale-data refreshing indicator (UD-015 item 4)'
      )
    }
    // UD-015 item 5: the indicator goes only once the refreshed result has been *rendered* — not
    // when its answer arrived, and not when the client stopped waiting for the work.
    if (finalContext !== null && refresh.indicatorGoneAt !== null) {
      if (refresh.indicatorGoneAt < refresh.finalRenderedAt) {
        violations.push(
          `the refreshing indicator was removed at ${refresh.indicatorGoneAt} ms, before the final ` +
            `result was rendered at ${refresh.finalRenderedAt} ms ` +
            `(${refresh.finalRenderDistinguishable ? 'rendered content changed then' : 'the replacement rendered identical content, so its answer is the earliest provable rendering'}` +
            '; UD-015 item 5)'
        )
      }
    }
    // Without a status answer for the work the envelope advertised, the refresh's start and its
    // terminal outcome would be inferred from the indicator — which is the client's own state.
    if (refresh.uncorrelated.length > 0) {
      violations.push(
        `no task status was observed for advertised refresh work ${refresh.uncorrelated.join(', ')}, ` +
          'so its execution cannot be correlated with the refresh being measured'
      )
    }
    if (refresh.followableCount === 0) {
      violations.push(
        'a provisional result advertised no followable refresh work, so no refresh execution could ' +
          'be measured for it'
      )
    }
    if (refresh.outcome === 'replaced-unconfirmed') {
      violations.push(
        'the result was replaced while the advertised refresh work never reported a terminal task ' +
          `state (${refresh.observedCount} of ${refresh.followableCount} followed, states ` +
          `${refresh.advertised.map((task) => `${task.kind}:${task.lastObservedState}`).join(', ')}), ` +
          'so its terminal outcome is not established'
      )
    }
  }

  for (const context of measured.slice(1)) {
    if (!context.settled && !context.cutByReplacement) {
      violations.push(
        `the replacement context ${context.calculationId} never became quiescent ` +
          `(${context.missing.join('; ')})`
      )
    }
  }

  return violations
}
