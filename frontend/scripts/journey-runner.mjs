/**
 * Actual-app readiness journeys (STORY-RESOURCE-002). Local, explicit use only; never part of CI.
 *
 *   node scripts/journey-runner.mjs crafting        --origin https://172.24.59.83:8443 --ignore-https-errors --concurrency 1
 *   node scripts/journey-runner.mjs bank-materials  --origin https://172.24.59.83:8443 --ignore-https-errors --concurrency 1
 *   node scripts/journey-runner.mjs characters      --origin https://172.24.59.83:8443 --ignore-https-errors --concurrency 1
 *
 * Options: --origin <url> (required) --concurrency <n> (default 1; above 1 also needs --authorized-multi-session)
 * --out <dir> --headless|--headed --timeout-ms <n> --stable-ms <n> --dwell-profile provisional|none --dwell-ms <n>
 * --ignore-https-errors (explicit, for the self-signed lab certificate) --sections equipment,inventory,builds
 * --bootstrap auto|env-key|manual|none.
 *
 * Authentication happens before any timing and is never serialized: `env-key` reads the read-only key from the
 * process environment variable GW2_JOURNEY_API_KEY into the browser form only; `manual` waits for a person to
 * connect in a headed browser; `none` assumes no connection is needed (synthetic origins). No cookie, storage
 * state, header or key is written; every artifact is scanned and the write is refused on a credential-looking hit.
 *
 * The flows are PROVISIONAL / UNOBSERVED (see PROVISIONAL_NOTE) until the owner replaces them with recordings.
 * Final render is a rendered-content checkpoint that stays stable for --stable-ms; `networkidle` is never used.
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createInterface } from 'node:readline/promises'
import { monitorEventLoopDelay } from 'node:perf_hooks'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import {
  JOURNEYS, LIMITS, PROVISIONAL_NOTE, QUALIFICATION, REQUEST_BOUNDARY, REQUEST_ID_HEADER, RESULT_SCHEMA, SCENARIO_VERSION, STORAGE_PREFIX,
  actionId, awaitStable, boundResult, boundedList, classifyStatus, generatorHealth, newRunId, parseJourneyArgs, resourceObservation,
  safePath, scanForSecrets, shortText, summarizeRequests, validateRunResult, virtualUserId
} from './journeyCommon.mjs'
import { JOURNEY_FLOWS, WRITE_GUARD_SCRIPT } from './journeys.mjs'

export const DEFAULT_JOURNEY_OUTPUT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '.journey-runs')
const sleep = (ms) => new Promise((resolveSleep) => setTimeout(resolveSleep, ms))

/** One browser context = one virtual user. Observes requests and renders; never alters traffic. */
function createUser({ index, options, runStart, health }) {
  const id = virtualUserId(index)
  const actions = []
  const events = boundedList(LIMITS.maxEvents)
  const requests = boundedList(LIMITS.maxRequests)
  const mono = () => performance.now()
  const offset = (t = mono()) => Math.round(t - runStart)
  const user = { offset, id, actions, events, requests, page: null, context: null, sequence: 0, pageErrors: 0 }

  user.attach = (page) => {
    user.page = page
    const pending = new Map()
    page.on('pageerror', () => { health.pageErrors += 1 })
    page.on('request', (request) => {
      if (new URL(request.url()).origin !== options.origin) return
      pending.set(request, mono())
    })
    const settle = async (request, failure) => {
      const started = pending.get(request)
      if (started === undefined) return
      pending.delete(request)
      let response = null
      if (!failure) response = await request.response().catch(() => null)
      requests.push({
        virtual_user_id: id, method: request.method(), path: safePath(request.url(), options.origin),
        status: response ? response.status() : null, errorClass: classifyStatus(response?.status(), failure),
        completed: !failure, startOffsetMs: offset(started), latencyMs: failure ? null : Math.round(mono() - started),
        // Playwright does not reveal browser-cache hits; unknown stays unknown rather than being guessed.
        fromCache: null, boundary: REQUEST_BOUNDARY,
        requestId: response ? shortText(response.headers()[REQUEST_ID_HEADER] ?? '', 64) || null : null,
        actionId: null
      })
    }
    page.on('requestfinished', (request) => { settle(request, false).catch(() => {}) })
    page.on('requestfailed', (request) => { settle(request, true).catch(() => {}) })
  }

  /**
   * Runs one action. `spec`: name, optional `dwell`, `initial()` (saved/previous content visible?), `perform()`,
   * `final()` (rendered checkpoint of THIS action), `outcome()` (optional observed result label).
   */
  user.action = async (spec) => {
    user.sequence += 1
    const record = {
      action_id: actionId(id, user.sequence, spec.name), name: spec.name, description: spec.description ?? null, optional: spec.optional === true,
      offeredAtUtc: new Date().toISOString(), startedAtUtc: null, startOffsetMs: null, endOffsetMs: null,
      dwell: spec.dwell ? { ms: options.dwellMs, observed: false, label: 'unobserved' } : null,
      initialContent: { offsetMs: null, unavailableReason: 'not checked' },
      finalRender: { offsetMs: null, unavailableReason: 'not reached' },
      stableForMs: null, durationMs: null, terminal: 'error', outcome: null, error: null
    }
    actions.push(record)
    const offered = mono()
    try {
      if (spec.dwell && options.dwellMs > 0) await sleep(options.dwellMs)
      const started = mono()
      record.startedAtUtc = new Date().toISOString()
      record.startOffsetMs = offset(started)
      if (spec.initial) {
        const seen = await awaitStable({ check: spec.initial, now: mono, sleep, timeoutMs: Math.min(1500, options.timeoutMs), stableMs: 0, startMs: started })
        record.initialContent = seen.ok ? { offsetMs: offset(seen.firstSeenMs) } : { offsetMs: null, unavailableReason: 'no saved/previous content was visible before the action' }
      } else record.initialContent = { offsetMs: null, unavailableReason: 'journey step has no saved-content checkpoint' }
      const performedAt = mono()
      await spec.perform()
      const result = await awaitStable({
        check: async () => (await spec.final(performedAt)) === true, now: mono, sleep, timeoutMs: options.timeoutMs, stableMs: options.stableMs, startMs: started
      })
      record.endOffsetMs = offset()
      if (result.ok) {
        record.finalRender = { offsetMs: offset(result.firstSeenMs), stableConfirmedOffsetMs: offset(result.confirmedMs) }
        record.stableForMs = options.stableMs
        record.terminal = 'completed'
        record.durationMs = Math.round(result.firstSeenMs - offered)
      } else {
        record.finalRender = { offsetMs: null, unavailableReason: 'final rendered checkpoint was not reached before the timeout' }
        record.terminal = 'timeout'
        record.durationMs = Math.round(mono() - offered)
      }
      if (spec.outcome) record.outcome = await spec.outcome().catch(() => null)
    } catch (error) {
      record.endOffsetMs = offset()
      record.durationMs = Math.round(mono() - offered)
      record.finalRender = { offsetMs: null, unavailableReason: 'action raised an error' }
      record.error = shortText(error?.message ?? error)
      record.terminal = 'error'
    }
    events.push({ atOffsetMs: record.endOffsetMs, type: 'action-end', action_id: record.action_id, terminal: record.terminal })
    return record
  }
  return user
}

/** Authenticates before timing; the key is read from the environment, typed into the form and dropped. */
async function bootstrapAccount(page, options, io) {
  if (io.arm) return io.arm(page)
  const mode = options.bootstrap === 'auto' ? (process.env.GW2_JOURNEY_API_KEY ? 'env-key' : 'manual') : options.bootstrap
  await page.goto(`${options.origin}/#/synchronization`)
  if (mode === 'none') return undefined
  if (mode === 'manual') {
    if (options.headless) throw new Error('manual bootstrap needs a headed browser (or set GW2_JOURNEY_API_KEY)')
    const rl = createInterface({ input: process.stdin, output: process.stdout })
    try { await rl.question('Connect the account manually in the browser (System Status). Press Enter to START timing... ') } finally { rl.close() }
    return undefined
  }
  const key = process.env.GW2_JOURNEY_API_KEY
  if (!key) throw new Error('GW2_JOURNEY_API_KEY is not set')
  await page.locator('[data-test=api-key-input]').fill(key)
  await page.locator('[data-test=connect]').click()
  await page.locator('[data-test=disconnect]').waitFor({ state: 'visible', timeout: options.timeoutMs })
  await page.locator('[data-test=bootstrap-syncing]').waitFor({ state: 'hidden', timeout: options.timeoutMs }).catch(() => {})
  return undefined
}

/** Runs one journey for `options.concurrency` independent browser contexts. `io` lets tests inject bootstrap/clock. */
export async function runJourney(journey, options, io = {}) {
  if (!JOURNEYS.includes(journey)) throw new Error(`unknown journey ${journey}`)
  const runId = options.runId ?? newRunId()
  const health = { pageErrors: 0, browserDisconnected: false }
  const lag = monitorEventLoopDelay({ resolution: 20 })
  lag.enable()
  const cpuStart = process.cpuUsage()
  const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: options.headless })
  browser.on('disconnected', () => { health.browserDisconnected = true })
  const startedAtUtc = new Date().toISOString()
  let runStart = performance.now()
  const users = []
  let result
  try {
    const contexts = []
    for (let index = 0; index < options.concurrency; index += 1) {
      const context = await browser.newContext({ ignoreHTTPSErrors: options.ignoreHttpsErrors })
      await context.addInitScript(WRITE_GUARD_SCRIPT(STORAGE_PREFIX))
      const page = await context.newPage()
      contexts.push(context)
      await bootstrapAccount(page, options, io)
      users.push({ page, index })
    }
    // Timing starts only after every context is authenticated; credentials never enter the artifact.
    runStart = performance.now()
    const startedUtc = new Date().toISOString()
    const live = users.map(({ page, index }) => {
      const user = createUser({ index, options, runStart, health })
      user.attach(page)
      return user
    })
    const outcomes = await Promise.all(live.map((user) => JOURNEY_FLOWS[journey](user, options).then(
      (flow) => ({ ok: true, flow }), (error) => ({ ok: false, error: shortText(error?.message ?? error) }))))
    const endedAtUtc = new Date().toISOString()
    const allActions = live.flatMap((user) => user.actions)
    const requests = live.flatMap((user) => user.requests.items)
    const droppedRequests = live.reduce((total, user) => total + user.requests.dropped, 0)
    const failedActions = allActions.filter((a) => a.terminal !== 'completed')
    const safetyFailures = outcomes.filter((o) => o.ok && o.flow?.safety?.violations?.length > 0)
    const flowErrors = outcomes.filter((o) => !o.ok)
    const status = flowErrors.length > 0 || safetyFailures.length > 0 ? 'failed'
      : failedActions.some((a) => !a.optional) ? 'incomplete' : 'completed'
    result = {
      schema: RESULT_SCHEMA, runId, journey, scenario: { name: journey, version: SCENARIO_VERSION, provisional: true, observed: false, note: PROVISIONAL_NOTE },
      commit: options.commit, targetOrigin: options.origin, configuredConcurrency: options.concurrency,
      executedSessions: live.length, startedAtUtc, timingStartedAtUtc: startedUtc, endedAtUtc,
      totalOffsetMs: Math.round(performance.now() - runStart),
      options: { dwellProfile: options.dwellProfile, dwellMs: options.dwellMs, dwellObserved: false, timeoutMs: options.timeoutMs, stableMs: options.stableMs, ignoreHttpsErrors: options.ignoreHttpsErrors, headless: options.headless },
      virtualUsers: live.map((user, i) => ({
        virtual_user_id: user.id, actions: user.actions, flow: outcomes[i].ok ? outcomes[i].flow : { error: outcomes[i].error }
      })),
      events: live.flatMap((user) => user.events.items), droppedEvents: live.reduce((t, u) => t + u.events.dropped, 0),
      requests, requestSummary: summarizeRequests(requests, droppedRequests, allActions),
      resources: resourceObservation({ startUtc: startedUtc, endUtc: endedAtUtc }),
      generatorHealth: generatorHealth({
        cpuMs: Math.round((process.cpuUsage(cpuStart).user + process.cpuUsage(cpuStart).system) / 1000), rssBytes: process.memoryUsage().rss,
        maxLagMs: Math.round(lag.max / 1e6), pageErrors: health.pageErrors, browserDisconnected: health.browserDisconnected
      }),
      result: { status, completedActions: allActions.length - failedActions.length, failedActions: failedActions.map((a) => a.action_id), safetyViolations: safetyFailures.flatMap((o) => o.flow.safety.violations) },
      mode: QUALIFICATION.mode, qualification: QUALIFICATION.qualification,
      conclusion: 'No supported-user, capacity or qualification conclusion is made or implied by this run.'
    }
    for (const context of contexts) await context.close().catch(() => {})
  } finally {
    lag.disable()
    await browser.close().catch(() => {})
  }
  return finish(result, options)
}

/** Bound, validate and credential-scan the artifact, then write it. Refuses to write suspicious content. */
export function finish(result, options) {
  const bounded = boundResult(result)
  const problems = validateRunResult(bounded)
  if (problems.length > 0) throw new Error(`refusing to write the run artifact: ${problems.join('; ')}`)
  const outDir = resolve(options.out ?? DEFAULT_JOURNEY_OUTPUT_DIR)
  mkdirSync(outDir, { recursive: true })
  const file = join(outDir, `${bounded.journey}-${bounded.runId}.json`)
  const text = `${JSON.stringify(bounded, null, 2)}\n`
  if (scanForSecrets(text).length > 0) throw new Error('refusing to write the run artifact: credential-looking content')
  writeFileSync(file, text)
  return { file, result: bounded }
}

async function main() {
  const argv = process.argv.slice(2)
  if (argv.length === 0 || argv[0] === '--help') {
    console.log('usage: node scripts/journey-runner.mjs <crafting|bank-materials|characters> --origin <url> [--concurrency 1] [--ignore-https-errors] ... (see header comment)')
    return
  }
  const { journey, options } = parseJourneyArgs(argv)
  const { file, result } = await runJourney(journey, options)
  console.log(`Wrote ${file}`)
  console.log(JSON.stringify({ journey, status: result.result.status, actions: result.virtualUsers.flatMap((u) => u.actions).map((a) => `${a.name}:${a.terminal}`) }))
  if (result.result.status !== 'completed') process.exitCode = 1
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => { console.error(shortText(error.message, 300)); process.exitCode = 1 })
}
