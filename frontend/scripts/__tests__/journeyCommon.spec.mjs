/** Unit checks for the journey runner's pure helpers (STORY-RESOURCE-002). Synthetic data only; no browser. */
import { describe, expect, it } from 'vitest'
import {
  JOURNEYS, LIMITS, actionId, awaitStable, boundResult, boundedList, classifyStatus, generatorHealth, newRunId, parseJourneyArgs,
  resourceObservation, safePath, scanForSecrets, summarizeRequests, validateOrigin, validateRunResult, virtualUserId
} from '../journeyCommon.mjs'

const KEY = '12345678-ABCD-1234-ABCD-12345678901234567890-ABCD-1234-ABCD-123456789012'
const ok = (extra = []) => parseJourneyArgs(['crafting', '--origin', 'https://172.24.59.83:8443', ...extra]).options

describe('argument validation', () => {
  it('accepts the three journeys and defaults to one session', () => {
    expect(JOURNEYS).toEqual(['crafting', 'bank-materials', 'characters'])
    const options = ok()
    expect(options).toMatchObject({ origin: 'https://172.24.59.83:8443', concurrency: 1, ignoreHttpsErrors: false, dwellProfile: 'provisional', bootstrap: 'auto' })
  })
  it('requires an explicit opt-in for self-signed HTTPS', () => {
    expect(ok(['--ignore-https-errors']).ignoreHttpsErrors).toBe(true)
  })
  it('rejects malformed or unsafe values', () => {
    expect(() => parseJourneyArgs(['nope', '--origin', 'http://x'])).toThrow(/journey/)
    for (const bad of ['0', '-1', '1.5', 'abc', '99', '']) expect(() => ok(['--concurrency', bad])).toThrow(/concurrency/)
    expect(() => ok(['--timeout-ms', '10'])).toThrow(/timeout-ms/)
    expect(() => ok(['--dwell-ms', '999999'])).toThrow(/dwell-ms/)
    expect(() => ok(['--sections', 'equipment,bogus'])).toThrow(/sections/)
    expect(() => ok(['--bogus'])).toThrow(/unknown option/)
    expect(() => ok(['--headless', '--headed'])).toThrow(/exclusive/)
    expect(() => ok(['--run-id', '../x'])).toThrow(/run-id/)
  })
  it('refuses more than one live session without the extra authorization flag', () => {
    expect(() => ok(['--concurrency', '2'])).toThrow(/authorized-multi-session/)
    expect(ok(['--concurrency', '2', '--authorized-multi-session']).concurrency).toBe(2)
  })
  it('validates the origin as an origin only, without credentials', () => {
    expect(validateOrigin('http://vm:8080/')).toBe('http://vm:8080')
    for (const bad of ['', 'ftp://x', 'http://u:p@x', 'http://x/path', 'http://x/?k=1', 'http://x/#a', 'not a url']) expect(() => validateOrigin(bad)).toThrow()
    expect(() => validateOrigin(`http://x/?key=${KEY}`)).toThrow(/origin only/)
  })
  it('keeps provisional dwell editable and none disables it', () => {
    expect(ok(['--dwell-ms', '2500']).dwellMs).toBe(2500)
    expect(ok(['--dwell-profile', 'none']).dwellMs).toBe(0)
  })
})

describe('identifiers and timestamps', () => {
  it('derives stable virtual user and action ids', () => {
    expect(virtualUserId(0)).toBe('vu-1')
    expect(actionId('vu-1', 2, 'recalculate')).toBe('vu-1.a2.recalculate')
  })
  it('creates sortable UTC run ids', () => {
    expect(newRunId(new Date('2026-11-13T10:20:30.456Z'), Buffer.from('abc'))).toBe('20261113T102030Z-616263')
  })
})

describe('final-render stability', () => {
  const clock = () => { let t = 0; return { now: () => t, sleep: async (ms) => { t += ms } } }
  it('completes only after the checkpoint holds for the stable interval, timestamping its first appearance', async () => {
    const { now, sleep } = clock()
    const result = await awaitStable({ check: async () => now() >= 200, now, sleep, timeoutMs: 5000, stableMs: 300, pollMs: 50 })
    expect(result).toMatchObject({ ok: true, firstSeenMs: 200, timedOut: false })
    expect(result.confirmedMs).toBeGreaterThanOrEqual(500)
  })
  it('restarts the stable interval when the checkpoint flickers', async () => {
    const { now, sleep } = clock()
    const result = await awaitStable({ check: async () => !(now() >= 100 && now() < 200) && now() >= 50, now, sleep, timeoutMs: 5000, stableMs: 200, pollMs: 50 })
    expect(result.firstSeenMs).toBe(200)
  })
  it('times out with no final render rather than inventing one', async () => {
    const { now, sleep } = clock()
    expect(await awaitStable({ check: async () => false, now, sleep, timeoutMs: 400, stableMs: 0 })).toEqual({ ok: false, firstSeenMs: null, confirmedMs: null, timedOut: true })
  })
})

describe('request classification and correlation', () => {
  const request = (path, start, extra = {}) => ({ path, completed: true, latencyMs: 20, startOffsetMs: start, errorClass: 'ok', requestId: null, fromCache: null, ...extra })
  it('classifies status classes', () => {
    expect([classifyStatus(200), classifyStatus(404), classifyStatus(503), classifyStatus(302), classifyStatus(undefined), classifyStatus(200, true)])
      .toEqual(['ok', 'client-error', 'server-error', 'redirect', 'no-response', 'transport-error'])
  })
  it('reports counts and latency but never infers action correlation from overlapping time', () => {
    const summary = summarizeRequests(
      [request('/api/a', 10, { requestId: 'r1' }), request('/api/b', 20, { errorClass: 'server-error' }), request('/index.html', 5)], 2,
      [{ action_id: 'vu-1.a1.x', startOffsetMs: 0, endOffsetMs: 100 }])
    expect(summary).toMatchObject({ offeredApi: 2, completedApi: 2, withApplicationRequestId: 1, truncatedRequests: 2, byErrorClass: { ok: 1, 'server-error': 1 } })
    expect(summary.actionCorrelation.status).toBe('unavailable')
    expect(summary.actionCorrelation.unavailableReason).toMatch(/not used as correlation/)
    expect(summary.actionCorrelation.windowOverlapOnly[0].apiRequestsStartedInWindow).toBe(2)
    expect(summary.outboundGw2.value).toBeNull()
    expect(summary.outboundGw2.unavailableReason).toBeTruthy()
  })
  it('states unavailable latency instead of zero when nothing completed', () => {
    expect(summarizeRequests([], 0, []).latency).toEqual({ count: 0, unavailableReason: 'no completed requests observed' })
  })
  it('drops query strings and credential-shaped path segments', () => {
    expect(safePath(`https://vm/api/x?access_token=zzz&k=${KEY}`)).toBe('/api/x')
    expect(safePath(`/api/${KEY}/y`)).toBe('/api/[redacted]/y')
  })
})

describe('bounded output and explicit truncation', () => {
  it('keeps small results whole and states truncation false', () => {
    expect(boundResult({ requests: [1], events: [] }, 1000).truncation).toEqual({ truncated: false, reason: null, droppedFields: [] })
  })
  it('cuts bulky arrays and classifies the truncation', () => {
    const result = boundResult({ requests: Array.from({ length: 200 }, () => 'x'.repeat(50)), events: [], rest: 'ok' }, 500)
    expect(result.requests).toEqual([])
    expect(result.truncation).toMatchObject({ truncated: true, droppedFields: ['requests:200'] })
  })
  it('counts dropped items in bounded lists', () => {
    const list = boundedList(2)
    ;[1, 2, 3, 4].forEach((n) => list.push(n))
    expect(list.items).toEqual([1, 2])
    expect(list.dropped).toBe(2)
  })
  it('has bounded limits', () => { expect(LIMITS.maxOutputBytes).toBeLessThanOrEqual(1048576) })
})

describe('unavailable fields and schema', () => {
  const action = { action_id: 'vu-1.a1.x', name: 'x', offeredAtUtc: 'u', startedAtUtc: 'u', startOffsetMs: 0, initialContent: { offsetMs: null, unavailableReason: 'none seen' }, finalRender: { offsetMs: 5 }, durationMs: 5, terminal: 'completed' }
  const base = () => ({
    schema: 'gw2-journey-run-1', runId: 'r', journey: 'crafting', scenario: {}, commit: 'c', targetOrigin: 'http://x', configuredConcurrency: 1, startedAtUtc: 'u', endedAtUtc: 'u',
    virtualUsers: [{ virtual_user_id: 'vu-1', actions: [action] }], requests: [], resources: resourceObservation({ startUtc: 'a', endUtc: 'b' }),
    generatorHealth: generatorHealth({ cpuMs: 1, rssBytes: 2, maxLagMs: 3, pageErrors: 0, browserDisconnected: false }), result: {},
    mode: 'preparation-only', qualification: 'not-qualified', truncation: { truncated: false }
  })
  it('accepts a complete run and keeps VM, containers and generator apart', () => {
    const run = base()
    expect(validateRunResult(run)).toEqual([])
    expect(run.resources.wholeVm.boundary).not.toBe(run.resources.containers.boundary)
    expect(run.resources.wholeVm.value).toBeNull()
    expect(run.resources.wholeVm.unavailableReason).toBeTruthy()
    expect(run.generatorHealth.boundary).toBe('load-generator-process')
  })
  it('rejects null checkpoints without a reason, missing fields and qualification claims', () => {
    const run = base()
    run.virtualUsers[0].actions[0].finalRender = { offsetMs: null }
    run.qualification = 'qualified'
    delete run.runId
    expect(validateRunResult(run)).toEqual(expect.arrayContaining(['missing runId', 'not preparation-only/not-qualified', 'action vu-1.a1.x null checkpoint without unavailableReason']))
  })
})

describe('credential scan', () => {
  it('flags representative credential material by name only', () => {
    expect(scanForSecrets(`key ${KEY}`)).toContain('gw2-api-key')
    expect(scanForSecrets('Authorization: Bearer abcdefghijkl')).toEqual(expect.arrayContaining(['authorization-header', 'authorization-value']))
    expect(scanForSecrets('Cookie: sid=abc')).toContain('cookie')
    expect(scanForSecrets('{"cookies": [], "origins": []}')).toContain('storage-state')
    expect(scanForSecrets('{"session_id":"abcdef123456"}')).toContain('credential-field')
  })
  it('passes ordinary run output and makes the schema validator reject a leak', () => {
    expect(scanForSecrets('{"journey":"crafting","status":"completed","path":"/api/crafting/profit"}')).toEqual([])
    const leaked = { schema: 'x', mode: 'preparation-only', qualification: 'not-qualified', note: `key ${KEY}` }
    expect(validateRunResult(leaked).some((p) => p.startsWith('credential-looking content'))).toBe(true)
  })
})
