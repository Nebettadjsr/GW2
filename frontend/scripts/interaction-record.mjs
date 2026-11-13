/**
 * Local, opt-in interaction recorder/replayer (STORY-RESOURCE-001). Explicit use only:
 *
 *   npm run record:interaction -- record --url http://127.0.0.1:5173/ --name my-flow \
 *       --initial "<css selector>[::text]" --final "<css selector>[::text]" [--busy "<css selector>"]
 *   npm run record:interaction -- replay <scenario.json> [--url http://host:port] [--replay-gaps] [--timeout-ms 30000]
 *   npm run record:interaction -- skeleton [--out file]
 *
 * `record` opens an external (headed) browser. Log in / enter keys manually FIRST; nothing is recorded until
 * you press Enter in this terminal (arming), then a small overlay appears and navigation, clicks, selections
 * and text input are captured with monotonic timestamps. Press Enter again (or the overlay's Stop) to write
 * the scenario and timing files to `frontend/.interaction-recordings/` (git-ignored). The application is not
 * modified: the overlay is injected from outside after arming. No cookies, storage state, headers or
 * login/key-field values are read or serialized (see interactionRecorder.mjs).
 *
 * Edit a scenario's `pause` entries to `{ "kind": "dwell"|"game-pause", "dwellMs": N, "observed": true }`.
 * Pauses stay `unclassified` (never claimed as think time) until you do.
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createInterface } from 'node:readline/promises'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import {
  LIMITS, baselineSkeleton, boundEvent, buildScenario, buildTimingOutput, createBuffer, createObserver, replayScenario, safeUrl
} from './interactionRecorder.mjs'

export const DEFAULT_OUTPUT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '.interaction-recordings')

function parseArgs(argv) {
  const [command, ...rest] = argv
  const options = { _: [] }
  for (let i = 0; i < rest.length; i++) {
    const arg = rest[i]
    if (!arg.startsWith('--')) options._.push(arg)
    else if (['--replay-gaps'].includes(arg)) options[arg.slice(2)] = true
    else options[arg.slice(2)] = rest[++i]
  }
  return { command, options }
}

function parseCheckpoint(spec) {
  if (!spec) return undefined
  const [selector, text] = spec.split('::')
  return { selector, text: text ?? null }
}

/** Runs inside the page after arming. Reports through the exposed `gw2Record` binding; keeps no storage. */
function overlayScript(busySelector) {
  return `(() => {
    if (window.__gw2RecorderInstalled) return
    window.__gw2RecorderInstalled = true
    let busyMs = 0, busySince = null
    const busy = () => ${busySelector ? `!!document.querySelector(${JSON.stringify(busySelector)})` : 'false'}
    setInterval(() => {
      const now = performance.now()
      if (busy()) { if (busySince === null) busySince = now } else if (busySince !== null) { busyMs += now - busySince; busySince = null }
    }, 100)
    const takeBusy = () => { const now = performance.now(); const total = busyMs + (busySince !== null ? now - busySince : 0); busyMs = 0; if (busySince !== null) busySince = now; return total }
    const unique = (css) => { try { return document.querySelectorAll(css).length === 1 } catch { return false } }
    const selectorOf = (el) => {
      const tag = el.tagName.toLowerCase()
      if (el.id && unique('#' + CSS.escape(el.id))) return '#' + CSS.escape(el.id)
      for (const attr of ['data-testid', 'data-test', 'aria-label', 'name']) {
        const value = el.getAttribute(attr)
        const css = tag + '[' + attr + '=' + JSON.stringify(value) + ']'
        if (value && unique(css)) return css
      }
      if (el.type === 'checkbox' || el.type === 'radio') {
        const css = 'input[name=' + JSON.stringify(el.name) + '][value=' + JSON.stringify(el.value) + ']'
        if (el.name && unique(css)) return css
        const same = [...document.querySelectorAll('input[type=' + el.type + ']')]
        return 'input[type=' + el.type + '] >> nth=' + same.indexOf(el)
      }
      const text = (el.textContent || '').trim().slice(0, 40)
      return text && !/input|select|textarea/i.test(tag) ? tag + ':has-text(' + JSON.stringify(text) + ')' : tag
    }
    const field = (el) => ({ type: el.type, name: el.name, id: el.id, placeholder: el.placeholder, autocomplete: el.autocomplete, label: el.labels && el.labels[0] ? el.labels[0].textContent : undefined })
    const send = (event) => window.gw2Record({ ...event, busyMs: takeBusy() })
    const ours = (el) => !!(el.closest && el.closest('[data-gw2-recorder]'))
    const checkable = (el) => !!el && /^input$/i.test(el.tagName) && /checkbox|radio/i.test(el.type)
    document.addEventListener('click', (e) => {
      if (ours(e.target)) return
      const el = e.target.closest('button, a, [role=button], summary, label, input[type=checkbox], input[type=radio]') || e.target
      if (/^(input|select|textarea)$/i.test(el.tagName) && !/checkbox|radio/i.test(el.type)) return
      // checkbox/radio (direct or via label) are recorded once, as their resulting state, by the change handler
      if (checkable(el) || (/^label$/i.test(el.tagName) && checkable(el.control))) return
      send({ type: 'click', selector: selectorOf(el) })
    }, true)
    document.addEventListener('change', (e) => { if (ours(e.target)) return; const el = e.target; if (/select/i.test(el.tagName)) send({ type: 'select', selector: selectorOf(el), value: el.value, field: field(el) }); else if (checkable(el)) send({ type: 'check', selector: selectorOf(el), checked: el.checked }); else if (/input|textarea/i.test(el.tagName)) send({ type: 'input', selector: selectorOf(el), value: el.value, field: field(el) }) }, true)
    const box = document.createElement('div'); box.setAttribute('data-gw2-recorder', '')
    box.style.cssText = 'position:fixed;right:8px;bottom:8px;z-index:2147483647;background:#222;color:#fff;font:12px monospace;padding:6px 8px;border-radius:4px;opacity:.9'
    const clock = document.createElement('span'); const stop = document.createElement('button'); stop.textContent = 'Stop'; stop.style.marginLeft = '8px'
    stop.onclick = (e) => { e.stopPropagation(); window.gw2Record({ type: '__stop' }) }
    box.append('REC ', clock, stop); document.documentElement.append(box)
    const t0 = performance.now(); setInterval(() => { clock.textContent = ((performance.now() - t0) / 1000).toFixed(1) + 's' }, 200)
  })()`
}

/**
 * Records one session. `io` lets tests drive it headlessly: `io.arm(page)` replaces the Enter prompt,
 * `io.untilStop(page, stopped)` replaces waiting for Enter/Stop, `io.headless` hides the browser.
 */
export async function recordSession(options, io = {}) {
  if (!options.url || !options.name || !options.initial || !options.final) {
    throw new Error('record needs --url, --name, --initial and --final (see header comment)')
  }
  const outDir = resolve(options.out ?? DEFAULT_OUTPUT_DIR)
  mkdirSync(outDir, { recursive: true })
  const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: io.headless === true })
  const checkpoints = { initial: parseCheckpoint(options.initial), final: parseCheckpoint(options.final) }
  const events = createBuffer(LIMITS.maxSteps)
  let armed = false
  let finish
  const stopped = new Promise((resolveStop) => { finish = resolveStop })
  let rl
  try {
    const page = await (await browser.newContext()).newPage()
    await page.exposeBinding('gw2Record', (_source, event) => {
      if (!armed) return
      if (event.type === '__stop') return finish()
      events.push(boundEvent({ ...event, atMs: performance.now() }))
    })
    page.on('framenavigated', (frame) => {
      if (armed && frame === page.mainFrame()) events.push(boundEvent({ type: 'navigate', url: frame.url(), atMs: performance.now() }))
    })
    page.on('domcontentloaded', () => { if (armed) page.evaluate(overlayScript(options.busy)).catch(() => {}) })
    await page.goto(options.url)
    if (io.arm) await io.arm(page)
    else {
      rl = createInterface({ input: process.stdin, output: process.stdout })
      await rl.question('Log in / enter keys manually in the browser now. Press Enter here to ARM recording... ')
    }
    const startUrl = page.url()
    const armedAtUtc = new Date().toISOString()
    const armedAtMs = performance.now()
    armed = true
    await page.evaluate(overlayScript(options.busy))
    // Observe the rendered checkpoints from arming until stop, so initial and final transitions get timestamps.
    const observer = createObserver(page, checkpoints, () => performance.now())
    let observing = true
    const poller = (async () => {
      while (observing) { await observer.sample(); await new Promise((r) => setTimeout(r, 50)) }
    })()
    console.log('Recording. Press Enter here (or Stop in the overlay) to finish.')
    await (io.untilStop ? io.untilStop(page, stopped) : Promise.race([stopped, rl.question('')]))
    await observer.sample()
    observing = false
    await poller
    armed = false
    const scenario = buildScenario({
      name: options.name, events: events.items, startUrl, armedAtMs, droppedEvents: events.dropped,
      baseUrl: new URL(options.url).origin, checkpoints
    })
    const base = join(outDir, scenario.name.replace(/[^\w.-]+/g, '_'))
    writeFileSync(`${base}.scenario.json`, `${JSON.stringify(scenario, null, 2)}\n`)
    const stepTimings = [{ type: 'navigate', url: scenario.steps[0]?.url ?? null, offsetMs: 0 }, ...events.items.map((event) => ({
      type: event.type, selector: event.selector ?? null, url: event.url === undefined ? null : safeUrl(event.url),
      offsetMs: Math.round(event.atMs - armedAtMs)
    }))]
    const timing = buildTimingOutput({ scenario, stepTimings, attribution: observer.attribution(armedAtMs), startedAt: armedAtUtc })
    writeFileSync(`${base}.record-timing.json`, `${JSON.stringify({ mode: 'record', ...timing }, null, 2)}\n`)
    console.log(`Wrote ${base}.scenario.json (${scenario.steps.length} steps) and ${base}.record-timing.json. Edit pauses, then run replay.`)
    return { scenario, timing, files: [`${base}.scenario.json`, `${base}.record-timing.json`] }
  } finally {
    rl?.close()
    await browser.close()
  }
}

export async function replay(options, io = {}) {
  const [file] = options._
  if (!file) throw new Error('replay needs a scenario file')
  const scenario = JSON.parse(readFileSync(file, 'utf8'))
  const outDir = resolve(options.out ?? DEFAULT_OUTPUT_DIR)
  mkdirSync(outDir, { recursive: true })
  const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: io.headless === true || options.headless === 'true' })
  try {
    const page = await (await browser.newContext()).newPage()
    const timing = await replayScenario(page, scenario, {
      replayGaps: options['replay-gaps'] === true, timeoutMs: Number(options['timeout-ms'] ?? 30000), url: options.url
    })
    const out = join(outDir, `${scenario.name.replace(/[^\w.-]+/g, '_')}.timing.json`)
    writeFileSync(out, `${JSON.stringify(timing, null, 2)}\n`)
    console.log(`Wrote ${out}`)
    console.log(JSON.stringify(timing.attribution))
    return { timing, file: out }
  } finally {
    await browser.close()
  }
}

async function main() {
  const { command, options } = parseArgs(process.argv.slice(2))
  if (command === 'record') await recordSession(options)
  else if (command === 'replay') await replay(options)
  else if (command === 'skeleton') {
    const text = `${JSON.stringify(baselineSkeleton(), null, 2)}\n`
    if (options.out) writeFileSync(resolve(options.out), text)
    else process.stdout.write(text)
  } else {
    console.error('usage: record | replay <scenario.json> | skeleton (see header of interaction-record.mjs)')
    process.exitCode = 2
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => { console.error(error.message); process.exitCode = 1 })
}
