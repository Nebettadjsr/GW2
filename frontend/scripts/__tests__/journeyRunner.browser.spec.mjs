/**
 * Opt-in real-browser regression for the three journeys against the deterministic synthetic origin
 * (GW2_RECORDER_BROWSER=1; needs a Chromium-family browser). Not part of required CI. Proves the runner,
 * schema, final-render attribution and the Bank/Materials no-write guard; it says nothing about the product.
 */
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { runJourney } from '../journey-runner.mjs'
import { scanForSecrets, validateOrigin, validateOptions } from '../journeyCommon.mjs'
import { startSyntheticApp } from '../journeySyntheticApp.mjs'

const browserIt = it.runIf(process.env.GW2_RECORDER_BROWSER === '1')
const out = mkdtempSync(join(tmpdir(), 'gw2-journey-'))
const KEY = '12345678-ABCD-1234-ABCD-12345678901234567890-ABCD-1234-ABCD-123456789012'
let app
const options = (extra = {}) => ({
  ...validateOptions({ origin: validateOrigin(app.origin), headless: true, 'dwell-ms': '20', 'stable-ms': '100', 'timeout-ms': '8000' }), out, bootstrap: 'none', commit: 'test', ...extra
})
const io = { arm: async (page) => { await page.goto(`${app.origin}/#/crafting`) } }
const actions = (run) => run.virtualUsers.flatMap((u) => u.actions)

beforeAll(async () => { if (process.env.GW2_RECORDER_BROWSER === '1') app = await startSyntheticApp() })
afterAll(async () => { await app?.close(); rmSync(out, { recursive: true, force: true }) })

describe('journeys against the synthetic origin', () => {
  browserIt('crafting reaches an attributed final render and a result detail', async () => {
    process.env.GW2_JOURNEY_API_KEY = KEY // must never reach the artifact
    const { result, file } = await runJourney('crafting', options(), io)
    delete process.env.GW2_JOURNEY_API_KEY
    expect(result.result.status).toBe('completed')
    const recalc = actions(result).find((a) => a.name === 'change-setting')
    expect(recalc).toMatchObject({ terminal: 'completed', dwell: { observed: false, label: 'unobserved' } })
    expect(recalc.finalRender.offsetMs).toBeGreaterThan(recalc.startOffsetMs)
    expect(recalc.initialContent.offsetMs).not.toBeNull()
    expect(actions(result).map((a) => a.name)).toEqual(['open-crafting', 'change-setting', 'inspect-result', 'restore-setting'])
    expect(result.requestSummary.actionCorrelation.status).toBe('unavailable')
    expect(result.requestSummary.withApplicationRequestId).toBeGreaterThan(0)
    expect(scanForSecrets(readFileSync(file, 'utf8'))).toEqual([])
    expect(readFileSync(file, 'utf8')).not.toContain(KEY)
    expect(result).toMatchObject({ mode: 'preparation-only', qualification: 'not-qualified', configuredConcurrency: 1, executedSessions: 1 })
  }, 60000)

  browserIt('bank-materials keeps the change pending, discards it and writes nothing', async () => {
    const { result } = await runJourney('bank-materials', options(), io)
    expect(result.result.status).toBe('completed')
    expect(actions(result).map((a) => a.name)).toEqual(['open-bank', 'open-materials', 'change-selection-pending', 'return-to-bank', 'return-to-materials', 'discard-pending'])
    expect(result.virtualUsers[0].flow.safety).toMatchObject({ violations: [], savedSelectionUnchanged: true, savedSelectionWritesDuringJourney: 0, blockedCommitClicks: 0, commitControlInvoked: false })
  }, 60000)

  browserIt('records retained content early and waits for the automatic update to settle', async () => {
    const refreshing = await startSyntheticApp({ bug: 'retained-update' })
    try {
      const { result } = await runJourney('bank-materials', options({ origin: refreshing.origin, stableMs: 50 }), {
        arm: page => page.goto(`${refreshing.origin}/#/crafting`)
      })
      expect(result.result.status).toBe('completed')
      const open = actions(result).find(action => action.name === 'open-bank')
      expect(open.initialContent.offsetMs).not.toBeNull()
      expect(open.finalRender.offsetMs - open.initialContent.offsetMs).toBeGreaterThan(650)
      expect(open.durationMs).toBeGreaterThanOrEqual(850)
    } finally { await refreshing.close() }
  }, 60000)

  browserIt('bank-materials fails when the discard path writes the saved selection', async () => {
    const buggy = await startSyntheticApp({ bug: 'discard-writes' })
    try {
      const { result } = await runJourney('bank-materials', options({ origin: buggy.origin }), { arm: (page) => page.goto(`${buggy.origin}/#/crafting`) })
      expect(result.result.status).toBe('failed')
      expect(result.result.safetyViolations.join(' ')).toMatch(/write path was used 1 time/)
    } finally { await buggy.close() }
  }, 60000)

  browserIt('characters renders roster, identity and sections, treating unavailable optional detail as an observation', async () => {
    const { result } = await runJourney('characters', options(), io)
    expect(result.result.status).toBe('completed')
    const byName = Object.fromEntries(actions(result).map((a) => [a.name, a]))
    expect(Object.keys(byName)).toEqual(['open-characters', 'select-character', 'section-equipment', 'section-inventory', 'section-builds'])
    expect(byName['open-characters'].outcome).toBe('roster-rendered')
    expect(byName['section-equipment']).toMatchObject({ terminal: 'completed', outcome: 'rendered' })
    expect(byName['section-inventory']).toMatchObject({ terminal: 'completed', outcome: 'unavailable' })
    expect(byName['section-builds']).toMatchObject({ terminal: 'completed', outcome: 'empty' })
  }, 60000)

  browserIt('long dwell does not inflate click-to-final latency and initial content is observed without delaying the action', async () => {
    const { result } = await runJourney('crafting', options({ dwellMs: 1500, stableMs: 50 }), io)
    const change = actions(result).find((a) => a.name === 'change-setting')
    expect(change.dwellMs).toBeGreaterThanOrEqual(1500)
    expect(change.durationMs).toBeLessThan(1000) // the 150 ms calculation, not the 1.5 s dwell
    expect(change.offeredToFinalMs).toBeGreaterThanOrEqual(change.dwellMs + change.durationMs - 5)
    expect(change.startOffsetMs - 0).toBeGreaterThan(0)
    // absent initial content: a fresh origin page with no rows yet must not add a probe delay either
    const open = actions(result).find((a) => a.name === 'open-crafting')
    expect(open.durationMs).toBeLessThan(2000)
    expect(open.initialContent).toHaveProperty('offsetMs')
  }, 60000)

  browserIt('a delayed calculation is not final early while unrelated polling continues', async () => {
    const slow = await startSyntheticApp({ bug: 'hide-busy', calcDelayMs: 900 })
    try {
      const { result } = await runJourney('crafting', options({ origin: slow.origin, stableMs: 50 }), { arm: (page) => page.goto(`${slow.origin}/#/crafting`) })
      const change = actions(result).find((a) => a.name === 'change-setting')
      expect(change.terminal).toBe('completed')
      expect(change.durationMs).toBeGreaterThanOrEqual(850)
      expect(slow.apiPaths.filter((p) => p === 'GET /api/sync/status').length).toBeGreaterThan(10)
      const calc = result.requests.find((r) => r.method === 'POST' && r.path === '/api/crafting/profit' && r.latencyMs >= 850)
      expect(result.requests.some(r => r.method === 'POST' && r.path === '/api/crafting/profit' && r.latencyMs < 850)).toBe(true)
      expect(calc.latencyMs).toBeGreaterThanOrEqual(850)
      expect(change.finalRender.offsetMs).toBeGreaterThanOrEqual(calc.endOffsetMs - 1)
    } finally { await slow.close() }
  }, 60000)

  browserIt('keeps an unanswered request in the counts as unfinished at the cutoff', async () => {
    const hung = await startSyntheticApp({ calcDelayMs: 60000 })
    try {
      const { result } = await runJourney('crafting', options({ origin: hung.origin, timeoutMs: 1500, drainMs: 100, stableMs: 50 }), { arm: (page) => page.goto(`${hung.origin}/#/crafting`) })
      expect(actions(result).find((a) => a.name === 'change-setting').terminal).toBe('timeout')
      expect(result.result.status).toBe('incomplete')
      expect(result.requestSummary.unfinishedAtCutoffApi).toBeGreaterThanOrEqual(1)
      expect(result.requests.find((r) => r.method === 'POST')).toMatchObject({ state: 'unfinished-at-cutoff', latencyMs: null })
    } finally { await hung.close() }
  }, 60000)

  browserIt('an unknown character section hook is a timeout and the run is not completed', async () => {
    const odd = await startSyntheticApp({ bug: 'builds-nohook' })
    try {
      const { result } = await runJourney('characters', options({ origin: odd.origin, timeoutMs: 1500, stableMs: 50 }), { arm: (page) => page.goto(`${odd.origin}/#/crafting`) })
      expect(actions(result).find((a) => a.name === 'section-builds')).toMatchObject({ terminal: 'timeout' })
      expect(result.result.status).toBe('incomplete')
    } finally { await odd.close() }
  }, 60000)

  browserIt('counts distinct verified accounts apart from browser sessions', async () => {
    let n = 0
    const distinct = await runJourney('bank-materials', options({ concurrency: 2 }), { arm: async (page) => { await page.goto(`${app.origin}/#/crafting`); n += 1; return { accountId: `acct-${n}` } } })
    expect(distinct.result.accounts).toMatchObject({ browserSessions: 2, distinctVerifiedAccounts: 2, sharedAccountTraffic: false })
    expect(distinct.result.virtualUsers.map((u) => u.virtual_user_id)).toEqual(['vu-1', 'vu-2'])
    const shared = await runJourney('bank-materials', options({ concurrency: 2 }), { arm: async (page) => { await page.goto(`${app.origin}/#/crafting`); return { accountId: 'same' } } })
    expect(shared.result.accounts).toMatchObject({ browserSessions: 2, distinctVerifiedAccounts: 1, sharedAccountTraffic: true })
    expect(JSON.stringify(shared.result)).not.toContain('"same"')
  }, 90000)

  browserIt('records an unreachable checkpoint as a timeout, never a fake final render', async () => {
    // a stable interval longer than the timeout can never be satisfied
    const { result } = await runJourney('characters', options({ timeoutMs: 1000, stableMs: 5000, sections: ['equipment'] }), io)
    const first = actions(result)[0]
    expect(first).toMatchObject({ terminal: 'timeout', finalRender: { offsetMs: null } })
    expect(first.finalRender.unavailableReason).toMatch(/not reached/)
    expect(result.result.status).toBe('incomplete')
  }, 60000)
})
