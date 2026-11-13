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

const TRUNCATED = '…[truncated]'

export function truncate(text, max) {
  const value = String(text ?? '')
  if (value.length <= max || (value.endsWith(TRUNCATED) && value.length <= max + TRUNCATED.length)) return value
  return `${value.slice(0, max)}${TRUNCATED}`
}

/** Fixed-size buffer: keeps the first `max` items and counts what it had to drop. */
export function createBuffer(max) {
  const items = []
  return {
    items,
    dropped: 0,
    push(item) {
      if (items.length < max) items.push(item)
      else this.dropped++
    }
  }
}

/** Bounds a raw page event at ingestion (credential check on the full value) so memory stays small. */
export function boundEvent(event) {
  const bounded = { type: String(event.type).slice(0, 20), atMs: event.atMs, busyMs: Number(event.busyMs) || 0 }
  if (event.selector !== undefined) bounded.selector = truncate(event.selector, LIMITS.maxSelectorLength)
  if (event.url !== undefined) bounded.url = truncate(event.url, LIMITS.maxUrlLength * 4)
  if (event.value !== undefined) bounded.value = safeValue(event.value, event.field ?? {})
  if (event.checked !== undefined) bounded.checked = event.checked === true
  return bounded
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

function safeQuery(searchParams) {
  const kept = [...searchParams].filter(([name, value]) => !SENSITIVE_QUERY.test(name) && !looksLikeCredential(value))
  return kept.length === 0 ? '' : `?${new URLSearchParams(kept).toString()}`
}

/** Hash kept only for application routes (`#/path?query`); credential-looking params and non-route fragments are dropped. */
function safeHash(hash) {
  if (!hash.startsWith('#/')) return ''
  const q = hash.indexOf('?')
  const route = q === -1 ? hash.slice(1) : hash.slice(1, q)
  if (looksLikeCredential(route)) return ''
  return `#${route}${q === -1 ? '' : safeQuery(new URLSearchParams(hash.slice(q + 1)))}`
}

/** URL reduced to origin-relative path; credential-looking query parameters and userinfo are dropped; only `#/route` hashes survive. */
export function safeUrl(raw) {
  let url
  try {
    url = new URL(raw, 'http://placeholder.invalid')
  } catch {
    return REDACTED
  }
  const path = looksLikeCredential(url.pathname) ? REDACTED : url.pathname
  return truncate(`${path}${safeQuery(url.searchParams)}${safeHash(url.hash)}`, LIMITS.maxUrlLength)
}

/** Origin only (scheme://host:port): carries no userinfo, path, query or hash. Empty when unusable. */
export function safeOrigin(raw) {
  try {
    const { origin } = new URL(raw)
    return origin === 'null' || looksLikeCredential(origin) ? '' : origin
  } catch {
    return ''
  }
}

/** Absolute URL for a step: `override` (replay --url) wins over the recorded origin. */
export function resolveStepUrl(scenario, stepUrl, override) {
  const base = safeOrigin(override ?? '') || safeOrigin(scenario.baseUrl ?? '')
  if (base === '') throw new Error('scenario has no baseUrl; pass --url http://host:port to replay')
  return new URL(stepUrl, base).href
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
  if (event.type === 'check') step.checked = event.checked === true
  const gap = previousAtMs === undefined ? undefined : classifyGap(event.atMs - previousAtMs, event.busyMs)
  if (gap !== undefined) {
    step.gap = gap
    step.pause = { kind: 'unclassified', dwellMs: null, observed: false }
  }
  return step
}

/** Bounded, editable scenario from raw events (already ordered by monotonic `atMs`). */
export function buildScenario({
  name, events, checkpoints, synthetic = false, baseUrl = '', startUrl, armedAtMs = 0, droppedEvents = 0
}) {
  const steps = []
  let previous
  // The armed starting page seeds the scenario so click-only recordings can replay from a blank context.
  const seeded = startUrl === undefined ? events : [{ type: 'navigate', url: startUrl, atMs: armedAtMs }, ...events]
  for (const event of seeded.slice(0, LIMITS.maxSteps)) {
    steps.push(buildStep(event, previous))
    previous = event.atMs
  }
  return {
    version: 1,
    name: truncate(name, LIMITS.maxNameLength),
    synthetic,
    baseUrl: safeOrigin(baseUrl),
    truncatedSteps: Math.max(0, seeded.length - LIMITS.maxSteps) + droppedEvents,
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
 * Samples the initial/final rendered checkpoints of `page`. Only state changes are stored (first-true
 * attribution loses nothing), bounded by LIMITS.maxSamples with an explicit dropped count.
 */
export function createObserver(page, { initial, final }, now) {
  const buffer = createBuffer(LIMITS.maxSamples)
  let last
  const read = async (cp) => {
    if (!cp) return false
    const visible = await page.isVisible(cp.selector).catch(() => false)
    const text = visible && cp.text ? await page.textContent(cp.selector).catch(() => '') : ''
    return checkpointMatches(cp, { visible, text })
  }
  return {
    buffer,
    async sample() {
      const entry = { atMs: now(), initial: await read(initial), final: await read(final) }
      if (last === undefined || last.initial !== entry.initial || last.final !== entry.final) buffer.push(entry)
      last = entry
      return entry
    },
    attribution(startMs) {
      const { initial: i, final: f } = { initial, final }
      const result = attributeCheckpoints(buffer.items, startMs, { distinct: checkpointsDistinct(i, f) })
      return { ...result, samplesTruncated: buffer.dropped }
    }
  }
}

/**
 * Replays a scenario on a Playwright-like `page` (goto, click, selectOption, fill, isVisible, textContent).
 * The timer starts at the first step. Checkpoints are polled by rendered content (never `networkidle`)
 * continuously WHILE the actions and waits run and afterwards until completion/timeout, so a saved result that
 * is replaced mid-run is still seen. `options` injects clock/sleep for tests; `options.url` overrides the origin.
 */
export async function replayScenario(page, scenario, options = {}) {
  const now = options.now ?? (() => performance.now())
  const sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)))
  const pollMs = options.pollMs ?? 50
  const timeoutMs = options.timeoutMs ?? 30000
  const replayGaps = options.replayGaps === true
  const stepTimings = []
  const { initial, final } = scenario.checkpoints ?? {}
  const distinct = checkpointsDistinct(initial, final)
  const observer = createObserver(page, { initial, final }, now)
  const startedAt = new Date().toISOString()
  let start
  let poller
  let actionsEnd
  let failed = false

  const complete = () => observer.buffer.items.some((entry) => (distinct ? entry.final : entry.initial))
  const poll = async () => {
    while (!failed) {
      await observer.sample()
      if (actionsEnd !== undefined && (complete() || now() >= actionsEnd + timeoutMs)) break
      await sleep(pollMs)
    }
  }

  try {
    for (const step of scenario.steps) {
      const pause = step.pause
      if (pause?.dwellMs && (pause.kind === 'dwell' || pause.kind === 'game-pause')) await sleep(pause.dwellMs)
      else if (replayGaps && step.gap) await sleep(step.gap.unclassifiedMs)
      if (start === undefined) {
        start = now()
        poller = poll()
      }
      const stepStart = now()
      if (step.type === 'navigate') {
        const target = resolveStepUrl(scenario, step.url, options.url)
        // a navigation observed right after its recorded click is already current: do not reload the app
        const current = typeof page.url === 'function' ? page.url() : ''
        if (current !== target) await page.goto(target)
      } else if (step.type === 'check') await page.setChecked(step.selector, step.checked === true)
      else if (step.type === 'click') await page.click(step.selector)
      else if (step.type === 'select') await page.selectOption(step.selector, step.value)
      else if (step.type === 'input') {
        if (step.value === REDACTED) throw new Error(`step input ${step.selector} was redacted; credential entry is manual only`)
        await page.fill(step.selector, step.value)
      } else throw new Error(`unknown step type: ${step.type}`)
      stepTimings.push({
        type: step.type, selector: step.selector ?? null, url: step.url ?? null,
        offsetMs: Math.round(stepStart - start), actionMs: Math.round(now() - stepStart),
        pause: pause ? { kind: pause.kind, dwellMs: pause.dwellMs, observed: pause.observed === true } : null
      })
    }
  } catch (error) {
    failed = true
    await poller
    throw error
  }
  if (start === undefined) {
    start = now()
    poller = poll()
  }
  actionsEnd = now()
  await poller
  return buildTimingOutput({ scenario, stepTimings, attribution: observer.attribution(start), startedAt })
}
