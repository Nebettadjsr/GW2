/** Narrow checks for the local interaction recorder (STORY-RESOURCE-001). Synthetic data only. */
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  LIMITS, REDACTED, annotatePause, attributeCheckpoints, baselineSkeleton, buildScenario, buildTimingOutput,
  classifyGap, replayScenario, safeUrl, safeValue
} from '../interactionRecorder.mjs'
import { DEFAULT_OUTPUT_DIR } from '../interaction-record.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const KEY = '12345678-ABCD-1234-ABCD-12345678901234567890-ABCD-1234-ABCD-123456789012'
const CP = { initial: { selector: '#saved', text: 'saved' }, final: { selector: '#final', text: 'final' } }

describe('serialization and credential exclusion', () => {
  const events = [
    { type: 'navigate', url: `http://x.test/a?token=abc&view=1&api_key=${KEY}#access_token=zzz`, atMs: 0 },
    { type: 'input', selector: '#key', value: 'anything', field: { placeholder: 'GW2 API key' }, atMs: 100 },
    { type: 'input', selector: '#pw', value: 'hunter2', field: { type: 'password' }, atMs: 200 },
    { type: 'input', selector: '#free', value: `paste ${KEY}`, field: { name: 'note' }, atMs: 300 },
    { type: 'input', selector: '#hdr', value: 'Authorization: Bearer abcdefghijkl', field: {}, atMs: 400 },
    { type: 'input', selector: '#char', value: 'Mei Ling the Ranger', field: { name: 'character' }, atMs: 500 }
  ]
  const scenario = buildScenario({ name: 'cred', events, checkpoints: CP, baseUrl: 'http://x.test/?key=1' })
  const text = JSON.stringify(scenario)

  it('never serializes keys, cookies, authorization values or password values', () => {
    for (const secret of [KEY, 'hunter2', 'abcdefghijkl', 'zzz', 'token=abc', 'anything']) expect(text).not.toContain(secret)
    expect(scenario.steps.filter((s) => s.value === REDACTED)).toHaveLength(4)
    expect(scenario.steps[0].url).toBe('/a?view=1')
  })

  it('keeps ordinary character labels usable', () => {
    expect(safeValue('Mei Ling the Ranger', { name: 'character' })).toBe('Mei Ling the Ranger')
    expect(scenario.steps.at(-1).value).toBe('Mei Ling the Ranger')
  })

  it('writes owner-local recordings to an ignored directory', () => {
    const root = join(here, '..', '..', '..')
    const ignored = execFileSync('git', ['check-ignore', join(DEFAULT_OUTPUT_DIR, 'x.scenario.json')], { cwd: root }).toString()
    expect(ignored).toContain('.interaction-recordings')
  })

  it('redacts a credential-looking url path', () => {
    expect(safeUrl(`/k/${KEY}`)).toBe(REDACTED)
  })
})

describe('gaps and dwell', () => {
  it('separates waiting from unclassified and never invents dwell', () => {
    expect(classifyGap(1000, 300)).toEqual({ gapMs: 1000, waitingMs: 300, unclassifiedMs: 700 })
    expect(classifyGap(100, 500)).toEqual({ gapMs: 100, waitingMs: 100, unclassifiedMs: 0 })
    const s = buildScenario({
      name: 'g', checkpoints: CP,
      events: [{ type: 'click', selector: 'a', atMs: 10 }, { type: 'click', selector: 'b', atMs: 1010, busyMs: 300 }]
    })
    expect(s.steps[0].gap).toBeUndefined()
    expect(s.steps[1].pause).toEqual({ kind: 'unclassified', dwellMs: null, observed: false })
    expect(annotatePause(s.steps[1], 'dwell', 650).pause).toEqual({ kind: 'dwell', dwellMs: 650, observed: true })
    expect(() => annotatePause(s.steps[1], 'nap', 1)).toThrow()
  })

  it('marks skeleton placeholders as unobserved', () => {
    const skeleton = baselineSkeleton({ placeholderDwellMs: 1000 })
    expect(skeleton.steps.every((s) => s.pause.observed === false && /UNOBSERVED/.test(s.pause.note))).toBe(true)
  })
})

describe('initial versus final attribution', () => {
  it('does not attribute a late final replacement to the initial saved result', () => {
    const samples = [{ atMs: 100, initial: true, final: false }, { atMs: 600, initial: true, final: false }]
    const a = attributeCheckpoints(samples, 0)
    expect(a).toMatchObject({ initialContentMs: 100, finalRenderMs: null, initialQualified: true, finalQualified: false })
    const late = attributeCheckpoints([...samples, { atMs: 4000, initial: false, final: true }], 0)
    expect(late).toMatchObject({ initialContentMs: 100, finalRenderMs: 4000, finalQualified: true })
  })

  it('treats indistinguishable checkpoints as unqualified final', () => {
    const a = attributeCheckpoints([{ atMs: 5, initial: true, final: true }], 0, { distinct: false })
    expect(a.finalRenderMs).toBeNull()
    expect(a.note).toMatch(/identical/)
  })
})

describe('size bounds', () => {
  it('truncates steps, values and timing output with explicit counts', () => {
    const events = Array.from({ length: LIMITS.maxSteps + 7 }, (_, i) => ({
      type: 'input', selector: '#s', value: 'v'.repeat(LIMITS.maxValueLength + 50), field: {}, atMs: i
    }))
    const s = buildScenario({ name: 'n'.repeat(500), events, checkpoints: CP })
    expect(s.steps).toHaveLength(LIMITS.maxSteps)
    expect(s.truncatedSteps).toBe(7)
    expect(s.steps[0].value.endsWith('[truncated]')).toBe(true)
    expect(s.name.length).toBeLessThanOrEqual(LIMITS.maxNameLength + 20)
    const t = buildTimingOutput({ scenario: s, stepTimings: events, attribution: {}, startedAt: 'x' })
    expect(t.steps).toHaveLength(LIMITS.maxSteps)
    expect(t.truncatedSteps).toBe(7)
    const many = Array.from({ length: LIMITS.maxSamples + 3 }, (_, i) => ({ atMs: i, initial: false, final: false }))
    expect(attributeCheckpoints(many, 0).samplesTruncated).toBe(3)
  })
})

describe('replay against a stubbed page with continuing polling', () => {
  function stubPage({ finalAt, initialAt }) {
    const clock = { t: 0 }
    const calls = []
    let polls = 0
    const page = {
      goto: async (u) => calls.push(['goto', u]),
      click: async (s) => calls.push(['click', s]),
      selectOption: async (s, v) => calls.push(['select', s, v]),
      fill: async (s, v) => calls.push(['fill', s, v]),
      isVisible: async (sel) => {
        if (sel === '#poll-status') return true
        return sel === '#saved' ? clock.t >= initialAt : finalAt !== null && clock.t >= finalAt
      },
      textContent: async (sel) => (sel === '#saved' ? 'saved result' : 'final result'),
      // a never-idle network: this must never be awaited by replay
      waitForLoadState: async () => { throw new Error('networkidle must not be used') },
      get polls() { return polls }
    }
    const deps = {
      now: () => clock.t,
      sleep: async (ms) => { clock.t += ms; polls++ },
      pollMs: 50
    }
    return { page, deps, calls }
  }

  const scenario = JSON.parse(readFileSync(join(here, '..', 'examples', 'interaction-synthetic.scenario.json'), 'utf8'))

  it('completes through the rendered checkpoint and records every step type, pause, initial and final', async () => {
    const { page, deps, calls } = stubPage({ initialAt: 700, finalAt: 2000 })
    const timing = await replayScenario(page, scenario, deps)
    expect(calls.map((c) => c[0])).toEqual(['goto', 'click', 'select', 'fill'])
    expect(timing.attribution.initialContentMs).toBeGreaterThanOrEqual(700 - 800)
    expect(timing.attribution.finalRenderMs).toBeGreaterThan(timing.attribution.initialContentMs)
    expect(timing.steps.map((s) => s.pause?.kind)).toEqual([undefined, 'dwell', 'unclassified', 'game-pause'])
    expect(timing.steps[1].pause.observed).toBe(false)
    expect(timing.synthetic).toBe(true)
  })

  it('leaves final unqualified when only the initial saved result ever renders', async () => {
    const { page, deps } = stubPage({ initialAt: 0, finalAt: null })
    const timing = await replayScenario(page, scenario, { ...deps, timeoutMs: 1000 })
    expect(timing.attribution.initialQualified).toBe(true)
    expect(timing.attribution.finalRenderMs).toBeNull()
    expect(timing.attribution.finalQualified).toBe(false)
  })

  it('refuses to replay a redacted credential input', async () => {
    const { page, deps } = stubPage({ initialAt: 0, finalAt: 0 })
    const bad = { ...scenario, steps: [{ type: 'input', selector: '#k', value: REDACTED }] }
    await expect(replayScenario(page, bad, deps)).rejects.toThrow(/manual only/)
  })
})
