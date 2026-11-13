/**
 * Pure, browser-free helpers of the actual-app journey runner (STORY-RESOURCE-002): argument validation,
 * the one result schema, bounded recording, final-render stability, request classification and the
 * credential scan applied to every artifact before it is written.
 *
 * The output is `preparation-only` and `not-qualified`: nothing here can produce a supported-user or
 * capacity conclusion. Missing data is `null` with an `unavailableReason`, never zero or a guess.
 */
import { createHash, randomBytes } from 'node:crypto'

export const JOURNEYS = Object.freeze(['crafting', 'bank-materials', 'characters'])
export const RESULT_SCHEMA = 'gw2-journey-run-1'
export const SCENARIO_VERSION = 'provisional-1'
export const QUALIFICATION = Object.freeze({ mode: 'preparation-only', qualification: 'not-qualified' })
export const PROVISIONAL_NOTE = 'Provisional/unobserved flow: not a recording of real user behavior; dwell values carry no user rate.'

export const LIMITS = Object.freeze({
  defaultMaxSessions: 32, hardMaxSessions: 512, maxEvents: 200, maxRequests: 400, maxActions: 40, maxOutputBytes: 262144,
  maxDwellMs: 60000, minTimeoutMs: 1000, maxTimeoutMs: 600000, maxStableMs: 10000, maxTextLength: 120
})
export const DEFAULT_DWELL_MS = 1000
/** Header the operator TLS template adds to API responses; cached responses may carry an old id. */
export const REQUEST_ID_HEADER = 'x-gw2-test-request-id'
export const STORAGE_PREFIX = 'gw2.materialProtection.v1.'
export const CHARACTER_SECTIONS = Object.freeze(['equipment', 'inventory', 'builds'])

const FLAGS = new Set(['headless', 'headed', 'ignore-https-errors', 'help'])
const VALUE_OPTIONS = new Set(['origin', 'concurrency', 'out', 'dwell-profile', 'dwell-ms', 'timeout-ms', 'stable-ms', 'max-sessions', 'drain-ms', 'sections', 'commit', 'run-id', 'bootstrap'])

/** Parses `<journey> --option value ...`; throws a usage error naming the bad value. Never echoes secrets. */
export function parseJourneyArgs(argv) {
  const [journey, ...rest] = argv
  if (!JOURNEYS.includes(journey)) throw new Error(`journey must be one of ${JOURNEYS.join(', ')}`)
  const raw = {}
  for (let i = 0; i < rest.length; i += 1) {
    const token = rest[i]
    if (!token.startsWith('--')) throw new Error(`unexpected argument: ${token.slice(0, LIMITS.maxTextLength)}`)
    const key = token.slice(2)
    if (FLAGS.has(key)) raw[key] = true
    else if (VALUE_OPTIONS.has(key)) {
      const value = rest[i + 1]
      if (value === undefined || value.startsWith('--')) throw new Error(`--${key} needs a value`)
      raw[key] = value
      i += 1
    } else throw new Error(`unknown option: --${key.slice(0, LIMITS.maxTextLength)}`)
  }
  return { journey, options: validateOptions(raw) }
}

function integerInRange(name, text, min, max) {
  if (!/^\d+$/.test(String(text))) throw new Error(`--${name} must be a whole number`)
  const value = Number(text)
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`--${name} must be between ${min} and ${max}`)
  return value
}

export function validateOrigin(text) {
  if (text === undefined || text === '') throw new Error('--origin is required (for example https://host:8443)')
  let url
  try { url = new URL(text) } catch { throw new Error('--origin is not a valid URL') }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error('--origin must use http or https')
  if (url.username !== '' || url.password !== '') throw new Error('--origin must not contain credentials')
  if ((url.pathname !== '/' && url.pathname !== '') || url.search !== '' || url.hash !== '') {
    throw new Error('--origin must be an origin only (no path, query or fragment)')
  }
  return url.origin
}

export function validateOptions(raw) {
  if (raw.headless && raw.headed) throw new Error('--headless and --headed are mutually exclusive')
  // A configurable generator bound (not a discovery ceiling); an explicit --concurrency is itself the request
  // for that many independent sessions, so no extra confirmation flag exists.
  const maxSessions = raw['max-sessions'] === undefined ? LIMITS.defaultMaxSessions : integerInRange('max-sessions', raw['max-sessions'], 1, LIMITS.hardMaxSessions)
  const concurrency = raw.concurrency === undefined ? 1 : integerInRange('concurrency', raw.concurrency, 1, maxSessions)
  const dwellProfile = raw['dwell-profile'] ?? 'provisional'
  if (!['provisional', 'none'].includes(dwellProfile)) throw new Error('--dwell-profile must be provisional or none')
  const sections = raw.sections === undefined ? [...CHARACTER_SECTIONS] : raw.sections.split(',').map((s) => s.trim())
  if (sections.length === 0 || sections.some((s) => !CHARACTER_SECTIONS.includes(s))) {
    throw new Error(`--sections must list any of ${CHARACTER_SECTIONS.join(', ')}`)
  }
  const bootstrap = raw.bootstrap ?? 'auto'
  if (!['auto', 'env-key', 'manual', 'none'].includes(bootstrap)) throw new Error('--bootstrap must be auto, env-key, manual or none')
  const runId = raw['run-id']
  if (runId !== undefined && !/^[\w.-]{1,64}$/.test(runId)) throw new Error('--run-id must be 1-64 word characters')
  return {
    origin: validateOrigin(raw.origin),
    concurrency,
    maxSessions,
    drainMs: raw['drain-ms'] === undefined ? 2000 : integerInRange('drain-ms', raw['drain-ms'], 0, 10000),
    out: raw.out,
    headless: raw.headed === true ? false : raw.headless === true || process.env.GW2_JOURNEY_HEADLESS === '1',
    ignoreHttpsErrors: raw['ignore-https-errors'] === true,
    bootstrap,
    dwellProfile,
    dwellMs: dwellProfile === 'none' ? 0 : raw['dwell-ms'] === undefined ? DEFAULT_DWELL_MS : integerInRange('dwell-ms', raw['dwell-ms'], 0, LIMITS.maxDwellMs),
    timeoutMs: raw['timeout-ms'] === undefined ? 30000 : integerInRange('timeout-ms', raw['timeout-ms'], LIMITS.minTimeoutMs, LIMITS.maxTimeoutMs),
    stableMs: raw['stable-ms'] === undefined ? 500 : integerInRange('stable-ms', raw['stable-ms'], 0, LIMITS.maxStableMs),
    sections,
    commit: raw.commit ?? process.env.GITHUB_SHA ?? 'unknown',
    runId
  }
}

/**
 * Splits an in-memory list of read-only keys (comma/whitespace separated, from the process environment only)
 * into distinct non-empty entries. Never echoes a key; errors name counts only.
 */
export function parseKeyList(text) {
  const keys = [...new Set(String(text ?? '').split(/[\s,;]+/).filter((k) => k !== ''))]
  if (keys.some((k) => k.length > 200 || !/^[\w-]+$/.test(k))) throw new Error('GW2_JOURNEY_API_KEYS contains a malformed entry')
  return keys
}

/** Session i uses key i; with fewer keys than sessions the keys repeat, which is shared-account traffic. */
export const keyForSession = (keys, index) => (keys.length === 0 ? null : keys[index % keys.length])

/** One-way digest of a verified account id; the id itself is never recorded. */
export const hashAccountId = (id) => hashText(`account:${id}`)

/** Browser sessions and distinct verified accounts are separate quantities. */
export function summarizeAccounts(sessionAccountHashes, configuredKeys) {
  const verified = sessionAccountHashes.filter((h) => h !== null)
  const distinct = new Set(verified).size
  return {
    browserSessions: sessionAccountHashes.length,
    sessionsWithVerifiedAccount: verified.length,
    distinctVerifiedAccounts: distinct,
    configuredKeys,
    accountDigests: [...new Set(verified)],
    sharedAccountTraffic: verified.length > distinct,
    note: verified.length === 0 ? 'No account identity was verified (manual/none bootstrap): distinct accounts are unknown, not one.'
      : verified.length > distinct ? 'Some sessions share an account: that is shared-account traffic, not that many independent users.'
        : 'Every verified session used a different account.'
  }
}

export function newRunId(now = new Date(), random = randomBytes(3)) {
  return `${now.toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z')}-${random.toString('hex')}`
}

/** `virtual_user_id`/`action_id` are stable functions of the position, so reruns compare. */
export const virtualUserId = (index) => `vu-${index + 1}`
export const actionId = (userId, sequence, name) => `${userId}.a${sequence}.${name}`

/** Bounded list that counts what it dropped, so truncation is explicit and never silent. */
export function boundedList(max) {
  const items = []
  let dropped = 0
  return {
    items,
    push(item) { if (items.length < max) items.push(item); else dropped += 1 },
    get dropped() { return dropped }
  }
}

export function shortText(value, max = LIMITS.maxTextLength) {
  const text = String(value ?? '').replace(/\s+/g, ' ').trim()
  return text.length > max ? `${text.slice(0, max)}…` : text
}

const SECRET_PATTERNS = [
  [/[0-9A-F]{8}-(?:[0-9A-F]{4}-){3}[0-9A-F]{20}-(?:[0-9A-F]{4}-){3}[0-9A-F]{12}/i, 'gw2-api-key'],
  [/\bbearer\s+[\w.~+/=-]{8,}/i, 'authorization-value'],
  [/["']?authorization["']?\s*[:=]/i, 'authorization-header'],
  [/["']?(?:set-)?cookie["']?\s*[:=]\s*["']?[^"'\s,}]/i, 'cookie'],
  [/["']cookies["']\s*:\s*\[/i, 'storage-state'],
  [/["']origins["']\s*:\s*\[/i, 'storage-state'],
  [/["']localStorage["']\s*:\s*\[/i, 'storage-state'],
  [/(?:access_token|api[_-]?key|access[_-]?key|session[_-]?(?:id)?|csrf)["']?\s*[:=]\s*["']?[\w-]{8,}/i, 'credential-field']
]

/** Names (never values) of credential-looking material found in serialized output; empty means clean. */
export function scanForSecrets(text) {
  const found = new Set()
  for (const [pattern, name] of SECRET_PATTERNS) if (pattern.test(text)) found.add(name)
  return [...found]
}

/** Query strings and fragments are never recorded; a credential-shaped path segment is redacted. */
export function safePath(raw, base = 'http://x.invalid') {
  try {
    const url = new URL(raw, base)
    const path = url.pathname.split('/').map((segment) => (scanForSecrets(segment).length > 0 ? '[redacted]' : segment)).join('/')
    return path.slice(0, 200)
  } catch { return '[unparseable]' }
}

export function hashText(text) {
  return createHash('sha256').update(text).digest('hex').slice(0, 16)
}

/**
 * Waits until `check()` has held continuously for `stableMs`. Returns when the rendered checkpoint first held
 * (`firstSeenMs`, the final-render time) and when stability was confirmed. Never consults network idleness.
 */
export async function awaitStable({ check, now, sleep, timeoutMs, stableMs, pollMs = 50, startMs = now() }) {
  let heldSince = null
  while (now() - startMs <= timeoutMs) {
    const holds = await check()
    const t = now()
    if (holds) {
      if (heldSince === null) heldSince = t
      if (t - heldSince >= stableMs) return { ok: true, firstSeenMs: heldSince, confirmedMs: t, timedOut: false }
    } else heldSince = null
    await sleep(pollMs)
  }
  return { ok: false, firstSeenMs: null, confirmedMs: null, timedOut: true }
}

/** Classifies one browser-observed HTTP outcome; the boundary label keeps it from being read as wire traffic. */
export function classifyStatus(status, failure) {
  if (failure) return 'transport-error'
  if (status === null || status === undefined) return 'no-response'
  if (status >= 500) return 'server-error'
  if (status >= 400) return 'client-error'
  if (status >= 300) return 'redirect'
  return 'ok'
}

/** Final state of a request record once the drain is over: anything still pending is unfinished, never dropped. */
export function settleAtCutoff(record) {
  if (record.state === 'inflight') {
    record.state = 'unfinished-at-cutoff'
    record.errorClass = 'unfinished'
  }
  return record
}

export const REQUEST_BOUNDARY = 'browser-observed-incoming'

export function latencyStats(values) {
  if (values.length === 0) return { count: 0, unavailableReason: 'no completed requests observed' }
  const sorted = [...values].sort((a, b) => a - b)
  const at = (q) => sorted[Math.min(sorted.length - 1, Math.ceil(q * sorted.length) - 1)]
  return { count: values.length, minMs: sorted[0], p50Ms: at(0.5), p95Ms: at(0.95), maxMs: sorted.at(-1) }
}

/**
 * Summarizes browser-observed requests for the run. Linkage to an action is `unavailable`: the application
 * exposes no action identifier, and timestamp overlap is reported only as overlap, never as correlation.
 */
export function summarizeRequests(requests, droppedRequests, actions) {
  const api = requests.filter((r) => r.path.startsWith('/api/'))
  const byClass = {}
  for (const r of api) byClass[r.errorClass] = (byClass[r.errorClass] ?? 0) + 1
  return {
    boundary: REQUEST_BOUNDARY,
    offeredCounting: 'A request is recorded when the browser offers it; unfinished ones are kept and classified at the cutoff after a bounded drain.',
    notWireRequests: 'Browser-observed; cache hits, service workers and background polling are labelled, not counted as backend wire requests.',
    offeredApi: api.length,
    completedApi: api.filter((r) => r.state === 'completed').length,
    failedApi: api.filter((r) => r.state === 'failed').length,
    unfinishedAtCutoffApi: api.filter((r) => r.state === 'unfinished-at-cutoff').length,
    byErrorClass: byClass,
    latency: latencyStats(api.filter((r) => r.state === 'completed' && r.latencyMs !== null).map((r) => r.latencyMs)),
    cacheStatusUnknownOrCached: api.filter((r) => r.fromCache !== false).length,
    withApplicationRequestId: api.filter((r) => r.requestId !== null).length,
    actionCorrelation: {
      status: 'unavailable',
      unavailableReason: 'the application exposes no per-action identifier; timestamp overlap is not used as correlation',
      windowOverlapOnly: actions.map((a) => ({
        actionId: a.action_id,
        apiRequestsStartedInWindow: api.filter((r) => a.startOffsetMs !== null && a.endOffsetMs !== null && r.startOffsetMs >= a.startOffsetMs && r.startOffsetMs <= a.endOffsetMs).length
      }))
    },
    outboundGw2: { value: null, unavailableReason: 'outbound GW2 calls are not visible from the browser; backend network traffic is not labelled GW2 traffic' },
    truncatedRequests: droppedRequests
  }
}

/** Fixed unavailable-resource block; the guest observer output is aligned by UTC window, kept separate. */
export function resourceObservation(window) {
  const reason = 'the generator cannot observe the guest; run scripts/guest_observe.py on the VM and align it with this run window'
  return {
    alignmentWindow: window,
    wholeVm: { boundary: 'whole-host-linux', value: null, unavailableReason: reason },
    containers: { boundary: 'container-stack', value: null, unavailableReason: reason },
    disk: { value: null, unavailableReason: 'not collected by the generator' },
    network: { value: null, unavailableReason: 'not collected by the generator' },
    database: { value: null, unavailableReason: 'not collected by the generator' },
    note: 'Whole-VM and per-container readings are separate boundaries; host-minus-container is not attributed overhead.'
  }
}

/** Fixed, bounded description of the load generator itself; never a statement about the guest. */
export function generatorHealth(sample) {
  return {
    boundary: 'load-generator-process',
    nodeCpuMs: sample.cpuMs,
    nodeRssBytes: sample.rssBytes,
    maxEventLoopLagMs: sample.maxLagMs,
    pageErrors: sample.pageErrors,
    browserDisconnected: sample.browserDisconnected,
    note: 'Generator saturation or browser failure is not VM saturation.'
  }
}

/** Returns an object that fits `maxBytes`: arrays are cut with an explicit truncation record. */
export function boundResult(result, maxBytes = LIMITS.maxOutputBytes) {
  const whole = { ...result, truncation: { truncated: false, reason: null, droppedFields: [] } }
  // finish() writes compact JSON plus one newline: include both metadata and newline.
  const fits = (value) => Buffer.byteLength(JSON.stringify(value)) + 1 <= maxBytes
  if (fits(whole)) return whole
  const trimmed = { ...result }
  const dropped = []
  for (const field of ['requests', 'events']) {
    if (trimmed[field] !== undefined) {
      dropped.push(`${field}:${trimmed[field].length}`)
      trimmed[field] = []
    }
    trimmed.truncation = { truncated: true, reason: `output exceeded ${maxBytes} bytes`, droppedFields: dropped }
    if (fits(trimmed)) return trimmed
  }
  throw new Error(`result still exceeds ${maxBytes} bytes after truncation`)
}

const REQUIRED_TOP = ['schema', 'runId', 'accounts', 'journey', 'scenario', 'commit', 'targetOrigin', 'configuredConcurrency', 'startedAtUtc', 'endedAtUtc', 'virtualUsers', 'requests', 'resources', 'generatorHealth', 'result', 'mode', 'qualification', 'truncation']

/** Structural check shared by the tests and the writer: one schema for all three journeys. */
export function validateRunResult(result) {
  const problems = []
  for (const key of REQUIRED_TOP) if (!(key in result)) problems.push(`missing ${key}`)
  if (result.mode !== QUALIFICATION.mode || result.qualification !== QUALIFICATION.qualification) problems.push('not preparation-only/not-qualified')
  for (const user of result.virtualUsers ?? []) {
    if (!user.virtual_user_id) problems.push('virtual user without id')
    for (const action of user.actions ?? []) {
      for (const key of ['action_id', 'name', 'offeredAtUtc', 'startedAtUtc', 'startOffsetMs', 'dwellMs', 'initialContent', 'finalRender', 'durationMs', 'terminal']) {
        if (!(key in action)) problems.push(`action ${action.action_id ?? '?'} missing ${key}`)
      }
      for (const point of [action.initialContent, action.finalRender]) {
        if (point && point.offsetMs === null && !point.unavailableReason) problems.push(`action ${action.action_id} null checkpoint without unavailableReason`)
      }
    }
  }
  const secrets = scanForSecrets(JSON.stringify(result))
  if (secrets.length > 0) problems.push(`credential-looking content: ${secrets.join(',')}`)
  return problems
}
