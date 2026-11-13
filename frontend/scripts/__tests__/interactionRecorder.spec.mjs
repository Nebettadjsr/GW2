/** Narrow checks for the local interaction recorder (STORY-RESOURCE-001). Synthetic data only. */
import { execFileSync } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  LIMITS, REDACTED, boundEvent, createBuffer, resolveStepUrl, annotatePause, attributeCheckpoints, baselineSkeleton, buildScenario, buildTimingOutput,
  classifyGap, replayScenario, safeUrl, safeValue
} from '../interactionRecorder.mjs'
import { DEFAULT_OUTPUT_DIR, recordSession, replay } from '../interaction-record.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const KEY = '12345678-ABCD-1234-ABCD-12345678901234567890-ABCD-1234-ABCD-123456789012'
const CP = { initial: { selector: '#saved', text: 'saved' }, final: { selector: '#final', text: 'final' } }
const browserIt = it.runIf(process.env.GW2_RECORDER_BROWSER === '1')

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

describe('origin, in-memory bounds and sampling during actions', () => {
  it('keeps the safe origin, seeds the armed start page and allows a replay override', () => {
    const s = buildScenario({
      name: 'o', checkpoints: CP, baseUrl: 'http://user:pw@127.0.0.1:5173/p?token=1', startUrl: 'http://127.0.0.1:5173/a?key=1&v=2',
      armedAtMs: 5, events: [{ type: 'click', selector: '#go', atMs: 50 }]
    })
    expect(s.baseUrl).toBe('http://127.0.0.1:5173')
    expect(s.steps.map((x) => x.type)).toEqual(['navigate', 'click'])
    expect(s.steps[0].url).toBe('/a?v=2')
    expect(JSON.stringify(s)).not.toContain('pw')
    expect(resolveStepUrl(s, '/a?v=2')).toBe('http://127.0.0.1:5173/a?v=2')
    expect(resolveStepUrl(s, '/a', 'http://localhost:9')).toBe('http://localhost:9/a')
    expect(() => resolveStepUrl({ baseUrl: '' }, '/a')).toThrow(/--url/)
  })

  it('bounds the in-memory buffers and reports dropped counts', () => {
    const buffer = createBuffer(2)
    for (let i = 0; i < 5; i++) buffer.push(i)
    expect(buffer.items).toHaveLength(2)
    expect(buffer.dropped).toBe(3)
    const e = boundEvent({ type: 'input', atMs: 1, selector: 's'.repeat(999), value: `x${KEY}`, field: { name: 'note' } })
    expect(e.value).toBe(REDACTED)
    expect(e.selector.length).toBeLessThan(LIMITS.maxSelectorLength + 20)
    expect(buildScenario({ name: 'd', checkpoints: CP, events: [], droppedEvents: 4 }).truncatedSteps).toBe(4)
  })

  it('sees a saved result that is replaced while the actions are still running', async () => {
    let t = 0
    const sleep = async (ms) => { t += ms; await Promise.resolve() }
    const page = {
      goto: async () => {}, selectOption: async () => {}, fill: async () => {},
      click: async () => { for (let i = 0; i < 6; i++) await sleep(50) }, // a long action
      isVisible: async (sel) => (sel === '#saved' ? t >= 100 && t < 400 : t >= 400),
      textContent: async (sel) => (sel === '#saved' ? 'saved result' : 'final result')
    }
    const timing = await replayScenario(page, {
      baseUrl: 'http://127.0.0.1:1', checkpoints: CP.initial && { initial: { selector: '#saved', text: 'saved' }, final: { selector: '#final', text: 'final' } },
      steps: [{ type: 'click', selector: 'a' }, { type: 'click', selector: 'b' }]
    }, { now: () => t, sleep, pollMs: 50 })
    expect(timing.attribution.initialQualified).toBe(true)
    expect(timing.attribution.finalRenderMs).toBeGreaterThanOrEqual(timing.attribution.initialContentMs)
  })
})

describe('real Playwright record → replay roundtrip (synthetic page, ongoing polling)', () => {
  const html = `<!doctype html><title>t</title><label for="c">Opt in</label><input id="c" type="checkbox">
    <button id="go">Go</button><div id="out"></div>
    <script>
      setInterval(() => fetch('/poll').catch(() => {}), 100)
      document.getElementById('go').onclick = () => {
        const on = document.getElementById('c').checked
        setTimeout(() => { out.innerHTML = '<p id="saved">saved result</p>' }, 200)
        setTimeout(() => { out.innerHTML = '<p id="final">final result checked=' + on + '</p>' }, 1500)
      }
    </script>`

  browserIt('records a replayable scenario with timing, no Stop click and one checkbox toggle', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'gw2-rec-'))
    let firstActionUtc
    let polls = 0
    const server = createServer((req, res) => {
      if (req.url === '/poll') { polls++; res.end('{}') } else res.end(html)
    })
    await new Promise((r) => server.listen(0, '127.0.0.1', r))
    const url = `http://127.0.0.1:${server.address().port}/page.html`
    try {
      const recorded = await recordSession(
        { url, name: 'roundtrip', initial: '#saved::saved result', final: '#final::final result checked=true', out: dir },
        {
          headless: true,
          arm: async () => {},
          untilStop: async (page, stopped) => {
            firstActionUtc = Date.now()
            await page.click('label')
            await page.click('#go')
            await page.waitForSelector('#final')
            await page.click('[data-gw2-recorder] button')
            await stopped
          }
        }
      )
      const steps = recorded.scenario.steps
      expect(steps.map((x) => x.type)).toEqual(['navigate', 'check', 'click'])
      expect(steps[1]).toMatchObject({ checked: true })
      expect(steps[0].url).toBe('/page.html')
      expect(JSON.stringify(recorded.scenario)).not.toMatch(/Stop|gw2-recorder/)
      expect(steps.filter((x) => /Opt in|#c/.test(x.selector ?? ''))).toHaveLength(1)
      const rt = recorded.timing.attribution
      expect(Date.parse(recorded.timing.startedAt)).toBeLessThanOrEqual(firstActionUtc)
      expect(rt.initialContentMs).not.toBeNull()
      expect(rt.finalRenderMs).toBeGreaterThan(rt.initialContentMs)
      expect(readdirSync(dir).sort()).toEqual(['roundtrip.record-timing.json', 'roundtrip.scenario.json'])

      const replayed = await replay({ _: [recorded.files[0]], out: dir, 'timeout-ms': '8000' }, { headless: true })
      const a = replayed.timing.attribution
      expect(a.initialQualified).toBe(true)
      expect(a.finalQualified).toBe(true) // 'checked=true' only if the checkbox toggled exactly once
      expect(a.finalRenderMs).toBeGreaterThan(a.initialContentMs)
      expect(polls).toBeGreaterThan(10)
      expect(readdirSync(dir)).toContain('roundtrip.timing.json')
    } finally {
      server.close()
      rmSync(dir, { recursive: true, force: true })
    }
  }, 60000)
})

describe('hash routes and idempotent checked state', () => {
  it('keeps application hash routes but drops credential-bearing fragments', () => {
    expect(safeUrl('http://h/#/crafting')).toBe('/#/crafting')
    expect(safeUrl('http://h/app?x=1#/bank?tab=2')).toBe('/app?x=1#/bank?tab=2')
    expect(safeUrl('http://h/#/bank?token=abc&tab=2')).toBe('/#/bank?tab=2')
    expect(safeUrl('http://h/#access_token=abc')).toBe('/')
    expect(safeUrl(`http://h/#/x/${KEY}`)).toBe('/')
  })

  it('serializes a check step with its resulting state and replays it with setChecked', async () => {
    const step = buildScenario({ name: 'c', events: [{ type: 'check', selector: '#c', checked: true, atMs: 1 }], checkpoints: CP }).steps[0]
    expect(step).toMatchObject({ type: 'check', selector: '#c', checked: true })
    const calls = []
    const page = {
      goto: async () => {}, setChecked: async (...a) => calls.push(a), isVisible: async () => true, textContent: async () => 'saved'
    }
    await replayScenario(page, { name: 'c', checkpoints: { initial: CP.initial, final: CP.initial }, steps: [step] }, { sleep: async () => {} })
    expect(calls).toEqual([['#c', true]])
  })

  const html = `<!doctype html><label for="a">Opt A</label><input id="a" type="checkbox" checked>
    <input type="checkbox" name="f" value="2"><input type="radio" name="r" value="1"><input type="radio" name="r" value="2" checked>
    <a id="nav" href="#/bank">Bank</a><div id="out"></div>
    <script>
      const show = () => { out.innerHTML = '<p id="page">' + location.hash + '</p><p id="state">' + [a.checked, document.querySelectorAll('input[type=checkbox]')[1].checked, document.querySelector('input[name=r][value="1"]').checked].join(',') + '</p>' }
      addEventListener('hashchange', show); addEventListener('change', show); show()
    </script>`

  browserIt('records route and checked state and replays them from a different initial state', async () => {
    const server = createServer((req, res) => res.end(html))
    await new Promise((r) => server.listen(0, '127.0.0.1', r))
    const dir = mkdtempSync(join(tmpdir(), 'gw2-rec2-'))
    const url = `http://127.0.0.1:${server.address().port}/`
    try {
      const recorded = await recordSession(
        { url, name: 'hash', initial: '#page::#/bank', final: '#state::false,true,true', out: dir },
        {
          headless: true,
          arm: async () => {},
          untilStop: async (page, stopped) => {
            await page.click('#nav')
            await page.waitForSelector('#page:has-text("#/bank")')
            await page.click('label') // a: checked -> unchecked
            await page.check('input[name=f]')
            await page.check('input[name=r][value="1"]')
            await page.waitForSelector('#state:has-text("false,true,true")')
            await page.click('[data-gw2-recorder] button')
            await stopped
          }
        }
      )
      const steps = recorded.scenario.steps
      expect(steps.map((x) => x.type)).toEqual(['navigate', 'click', 'navigate', 'check', 'check', 'check'])
      expect(steps[2].url).toBe('/#/bank')
      expect(steps.slice(3).map((x) => x.checked)).toEqual([false, true, true])
      expect(steps.slice(3).map((x) => x.selector)).toEqual(['#a', 'input[name="f"]', 'input[name="r"][value="1"]'])
      // replay starts from the page's own initial state (a checked, r=2); intended states must still hold
      const replayed = await replay({ _: [recorded.files[0]], out: dir, 'timeout-ms': '8000' }, { headless: true })
      expect(replayed.timing.attribution.finalQualified).toBe(true)
      // a second replay is idempotent for check steps too
      expect(replayed.timing.steps.filter((x) => x.type === 'navigate')).toHaveLength(2)
    } finally {
      server.close()
      rmSync(dir, { recursive: true, force: true })
    }
  }, 60000)
})
