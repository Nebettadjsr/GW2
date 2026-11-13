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
    const recalc = actions(result).find((a) => a.name === 'recalculate')
    expect(recalc).toMatchObject({ terminal: 'completed', dwell: { observed: false, label: 'unobserved' } })
    expect(recalc.finalRender.offsetMs).toBeGreaterThan(recalc.startOffsetMs)
    expect(recalc.initialContent.offsetMs).not.toBeNull()
    expect(actions(result).map((a) => a.name)).toEqual(['open-crafting', 'recalculate', 'inspect-result'])
    expect(result.requestSummary.actionCorrelation.status).toBe('unavailable')
    expect(result.requestSummary.withApplicationRequestId).toBeGreaterThan(0)
    expect(scanForSecrets(readFileSync(file, 'utf8'))).toEqual([])
    expect(readFileSync(file, 'utf8')).not.toContain(KEY)
    expect(result).toMatchObject({ mode: 'preparation-only', qualification: 'not-qualified', configuredConcurrency: 1, executedSessions: 1 })
  }, 60000)

  browserIt('bank-materials keeps the change pending, discards it and writes nothing', async () => {
    const { result } = await runJourney('bank-materials', options(), io)
    expect(result.result.status).toBe('completed')
    expect(actions(result).map((a) => a.name)).toEqual(['open-bank', 'open-materials', 'change-selection-pending', 'navigate-with-pending', 'discard-pending'])
    expect(result.virtualUsers[0].flow.safety).toMatchObject({ violations: [], savedSelectionUnchanged: true, savedSelectionWritesDuringJourney: 0, blockedCommitClicks: 0, commitControlInvoked: false })
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
    expect(byName['section-builds']).toMatchObject({ terminal: 'completed', outcome: 'unavailable-optional-detail' })
    expect(byName['section-equipment'].outcome).toBe('rendered')
  }, 60000)

  browserIt('records an unreachable checkpoint as a timeout, never a fake final render', async () => {
    // a stable interval longer than the timeout can never be satisfied
    const { result } = await runJourney('characters', options({ timeoutMs: 1000, stableMs: 5000, sections: ['equipment'] }), io)
    const first = actions(result)[0]
    expect(first).toMatchObject({ terminal: 'timeout', finalRender: { offsetMs: null } })
    expect(first.finalRender.unavailableReason).toMatch(/not reached/)
    expect(result.result.status).toBe('incomplete')
  }, 60000)
})
