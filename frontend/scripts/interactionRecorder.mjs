/**
 * Pure logic of the local interaction recorder/replayer (STORY-RESOURCE-001). No browser, no file I/O,
 * so serialization, credential exclusion, gap classification, checkpoint attribution and size bounds
 * are testable in isolation. The Playwright driver is `interaction-record.mjs`.
 *
 * Ordinary character labels and workflow values ARE kept (owner-local files are ignored by git).
 * Only credentials are excluded: password-like fields, key/token-like fields, and values that look
 * like a GW2 API key, bearer token or cookie. Nothing here reads cookies or storage state.
 */

export const LIMITS = Object.freeze({
  maxSteps: 200,
  maxValueLength: 200,
  maxSelectorLength: 200,
  maxUrlLength: 300,
  maxSamples: 500,
  maxNameLength: 80
})

export const REDACTED = '[redacted-credential]'
const SENSITIVE_FIELD = /pass(word|phrase)?|secret|token|api[-_ ]?key|\bkey\b|auth|cookie|credential|login|session/i
const SENSITIVE_VALUE = [
  /[0-9A-F]{8}-(?:[0-9A-F]{4}-){3}[0-9A-F]{20}-(?:[0-9A-F]{4}-){3}[0-9A-F]{12}/i, // GW2 API key
  /\bBearer\s+[A-Za-z0-9._~+/=-]{8,}/i,
  /\b(?:authorization|cookie|set-cookie)\s*[:=]/i,
  /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\./ // JWT
]
const SENSITIVE_QUERY = /pass|secret|token|key|auth|cookie|session|credential/i

export function truncate(text, max) {
  const value = String(text ?? '')
  return value.length <= max ? value : `${value.slice(0, max)}…[truncated]`
}

/** True when a field descriptor (type/name/id/placeholder/label) marks a login or key-entry field. */
export function isSensitiveField(descriptor = {}) {
  if (String(descriptor.type ?? '').toLowerCase() === 'password') return true
  return [descriptor.name, descriptor.id, descriptor.placeholder, descriptor.label, descriptor.autocomplete]
    .some((part) => part !== undefined && part !== null && SENSITIVE_FIELD.test(String(part)))
}

export function looksLikeCredential(value) {
  return SENSITIVE_VALUE.some((pattern) => pattern.test(String(value ?? '')))
}

/** Value to serialize: ordinary text is kept (bounded); credentials are replaced. */
export function safeValue(value, descriptor = {}) {
  if (isSensitiveField(descriptor) || looksLikeCredential(value)) return REDACTED
  return truncate(value, LIMITS.maxValueLength)
}

/** URL reduced to origin-relative path; credential-looking query parameters, userinfo and hash are dropped. */
export function safeUrl(raw) {
  let url
  try {
    url = new URL(raw, 'http://placeholder.invalid')
  } catch {
    return REDACTED
  }
  const kept = [...url.searchParams].filter(([name, value]) => !SENSITIVE_QUERY.test(name) && !looksLikeCredential(value))
  const query = kept.length === 0 ? '' : `?${new URLSearchParams(kept).toString()}`
  const path = looksLikeCredential(url.pathname) ? REDACTED : url.pathname
  return truncate(`${path}${query}`, LIMITS.maxUrlLength)
}

/**
 * Splits an observed gap before an action. `busyMs` is time the page was demonstrably still working on the
 * preceding action (the configured busy indicator was visible); the rest is `unclassified` — never reported
 * as measured think time. An owner may later edit a step to `dwell` or `game-pause` with a dwellMs.
 */
export function classifyGap(gapMs, busyMs = 0) {
  const gap = Math.max(0, Math.round(gapMs))
  const waiting = Math.min(gap, Math.max(0, Math.round(busyMs)))
  return { gapMs: gap, waitingMs: waiting, unclassifiedMs: gap - waiting }
}

/** Builds one scenario step from a raw observed event. */
export function buildStep(event, previousAtMs) {
  const descriptor = event.field ?? {}
  const step = { type: event.type, selector: truncate(event.selector ?? '', LIMITS.maxSelectorLength) }
  if (event.type === 'navigate') {
    step.url = safeUrl(event.url)
    delete step.selector
  }
  if (event.type === 'select' || event.type === 'input') step.value = safeValue(event.value, descriptor)
  const gap = previousAtMs === undefined ? undefined : classifyGap(event.atMs - previousAtMs, event.busyMs)
  if (gap !== undefined) {
    step.gap = gap
    step.pause = { kind: 'unclassified', dwellMs: null, observed: false }
  }
  return step
}

/** Bounded, editable scenario from raw events (already ordered by monotonic `atMs`). */
export function buildScenario({ name, events, checkpoints, synthetic = false, baseUrl = '' }) {
  const steps = []
  let previous
  for (const event of events.slice(0, LIMITS.maxSteps)) {
    steps.push(buildStep(event, previous))
    previous = event.atMs
  }
  return {
    version: 1,
    name: truncate(name, LIMITS.maxNameLength),
    synthetic,
    baseUrl: safeUrl(baseUrl).replace(/\?.*$/, ''),
    truncatedSteps: Math.max(0, events.length - LIMITS.maxSteps),
    checkpoints,
    steps
  }
}

/** Marks a step's pause as an owner-edited dwell/game-pause. */
export function annotatePause(step, kind, dwellMs) {
  if (!['dwell', 'game-pause'].includes(kind)) throw new Error(`unknown pause kind: ${kind}`)
  if (!Number.isFinite(dwellMs) || dwellMs < 0) throw new Error('dwellMs must be a non-negative number')
  return { ...step, pause: { kind, dwellMs: Math.round(dwellMs), observed: true } }
}

/** Skeleton before owner actions exist: placeholder dwell values are visibly unobserved. */
export function baselineSkeleton({ name = 'baseline-skeleton', placeholderDwellMs = 0 } = {}) {
  const step = (type, selector, extra = {}) => ({
    type, selector, ...extra,
    pause: { kind: 'dwell', dwellMs: placeholderDwellMs, observed: false, note: 'UNOBSERVED placeholder — not a measured value' }
  })
  return {
    version: 1,
    name,
    synthetic: true,
    baseUrl: '',
    truncatedSteps: 0,
    checkpoints: {
      initial: { selector: 'TODO-saved-content-selector', text: null },
      final: { selector: 'TODO-final-render-selector', text: null }
    },
    steps: [step('click', 'TODO-selector')]
  }
}

/** A checkpoint matches the rendered state: visible, and (when given) containing the text. */
export function checkpointMatches(checkpoint, state) {
  if (checkpoint === undefined || checkpoint === null || !state.visible) return false
  if (checkpoint.text === undefined || checkpoint.text === null || checkpoint.text === '') return true
  return String(state.text ?? '').includes(checkpoint.text)
}

/** Two checkpoints that cannot be told apart cannot attribute a final result separately from the initial one. */
export function checkpointsDistinct(initial, final) {
  if (!initial || !final) return false
  return !(initial.selector === final.selector && (initial.text ?? '') === (final.text ?? ''))
}

/**
 * Attributes samples `{ atMs, initial: boolean, final: boolean }` (checkpoint states at that instant,
 * ordered by monotonic time, measured from `startMs`). Final completion is only a sample where the FINAL
 * checkpoint held; the initial result's timestamp is never reused for it. Absent → `null` + `unqualified`.
 */
export function attributeCheckpoints(samples, startMs, { distinct = true } = {}) {
  const bounded = samples.slice(0, LIMITS.maxSamples)
  const firstInitial = bounded.find((sample) => sample.initial)
  const firstFinal = distinct ? bounded.find((sample) => sample.final) : undefined
  const elapsed = (sample) => (sample === undefined ? null : Math.round(sample.atMs - startMs))
  return {
    initialContentMs: elapsed(firstInitial),
    finalRenderMs: elapsed(firstFinal),
    initialQualified: firstInitial !== undefined,
    finalQualified: firstFinal !== undefined,
    note: !distinct
      ? 'initial and final checkpoints are identical; final render is unqualified'
      : firstFinal === undefined
        ? 'final checkpoint never observed; final render is unqualified (timer was not stopped by network or DOM quiet)'
        : firstInitial !== undefined && firstFinal.atMs < firstInitial.atMs
          ? 'final checkpoint observed before initial content checkpoint'
          : '',
    samplesTruncated: Math.max(0, samples.length - LIMITS.maxSamples)
  }
}

/** Bounded timing output. */
export function buildTimingOutput({ scenario, stepTimings, attribution, startedAt }) {
  return {
    version: 1,
    scenario: scenario.name,
    synthetic: scenario.synthetic === true,
    startedAt,
    attribution,
    steps: stepTimings.slice(0, LIMITS.maxSteps),
    truncatedSteps: Math.max(0, stepTimings.length - LIMITS.maxSteps),
    note: 'Dwell values with observed=false are unobserved placeholders; this output makes no capacity claim.'
  }
}

/**
 * Replays a scenario on a Playwright-like `page` (goto, click, selectOption, fill, isVisible, textContent).
 * The timer starts at the first step; checkpoints are polled by rendered content, never `networkidle`, so a
 * page that keeps polling in the background still completes. `deps` injects clock/sleep for tests.
 */
export async function replayScenario(page, scenario, options = {}) {
  const now = options.now ?? (() => performance.now())
  const sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)))
  const pollMs = options.pollMs ?? 50
  const timeoutMs = options.timeoutMs ?? 30000
  const replayGaps = options.replayGaps === true
  const stepTimings = []
  const samples = []
  const { initial, final } = scenario.checkpoints ?? {}
  const distinct = checkpointsDistinct(initial, final)
  const startedAt = new Date().toISOString()
  let start

  async function sample() {
    const read = async (cp) => {
      if (!cp) return false
      const visible = await page.isVisible(cp.selector).catch(() => false)
      const text = visible && cp.text ? await page.textContent(cp.selector).catch(() => '') : ''
      return checkpointMatches(cp, { visible, text })
    }
    const entry = { atMs: now(), initial: await read(initial), final: await read(final) }
    samples.push(entry)
    return entry
  }

  for (const step of scenario.steps) {
    const pause = step.pause
    if (pause?.dwellMs && (pause.kind === 'dwell' || pause.kind === 'game-pause')) await sleep(pause.dwellMs)
    else if (replayGaps && step.gap) await sleep(step.gap.unclassifiedMs)
    if (start === undefined) start = now()
    const stepStart = now()
    if (step.type === 'navigate') await page.goto(`${scenario.baseUrl ?? ''}${step.url}`)
    else if (step.type === 'click') await page.click(step.selector)
    else if (step.type === 'select') await page.selectOption(step.selector, step.value)
    else if (step.type === 'input') {
      if (step.value === REDACTED) throw new Error(`step input ${step.selector} was redacted; credential entry is manual only`)
      await page.fill(step.selector, step.value)
    } else throw new Error(`unknown step type: ${step.type}`)
    stepTimings.push({
      type: step.type, selector: step.selector ?? null, url: step.url ?? null,
      actionMs: Math.round(now() - stepStart),
      pause: pause ? { kind: pause.kind, dwellMs: pause.dwellMs, observed: pause.observed === true } : null
    })
  }
  if (start === undefined) start = now()

  const deadline = start + timeoutMs
  while (now() < deadline) {
    const entry = await sample()
    if (entry.final && distinct) break
    if (!distinct && entry.initial) break
    await sleep(pollMs)
  }
  return buildTimingOutput({ scenario, stepTimings, attribution: attributeCheckpoints(samples, start, { distinct }), startedAt })
}
