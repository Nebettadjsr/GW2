/** Required sequential browser gate. Journey counts are separate from Vitest/V8 source coverage. */
import { execFile, spawn } from 'node:child_process'
import { mkdir, readFile, unlink, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { join, resolve } from 'node:path'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const output = resolve(process.env.GW2_SMOKE_OUTPUT ?? join(frontend, 'quality-reports/desktop-journeys'))
await mkdir(output, { recursive: true })
const commands = []
// At most 180s per child plus 10s for owned-resource teardown; two sequential children, no retries.
const CHILD_BUDGET_MS = 180_000
const CLEANUP_BUDGET_MS = 10_000
for (const [script, reportFile] of [['user-journeys.mjs', 'user-journeys.json'], ['reservation-browser-smoke.mjs', 'reservations.json']]) {
  // Remove only this child's known report. A prior green file cannot satisfy a new empty run.
  await unlink(join(output, reportFile)).catch(error => { if (error.code !== 'ENOENT') throw error })
  let log = ''
  let error = null
  let timedOut = false
  const exitCode = await new Promise(resolve => {
    const child = spawn(process.execPath, [join(frontend, 'scripts', script)], { cwd: frontend,
      env: { ...process.env, GW2_SMOKE_OUTPUT: output }, stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
      detached: process.platform !== 'win32' })
    const append = chunk => { process.stdout.write(chunk); log = (log + chunk.toString()).slice(-200_000) }
    let forcedStop
    const budget = setTimeout(() => {
      timedOut = true
      error = `${script} exceeded ${CHILD_BUDGET_MS}ms; closing its owned browser/server`
      append(Buffer.from(`\n${error}\n`))
      if (child.connected) child.send({ type: 'gw2-journey-timeout' }, () => {})
      forcedStop = setTimeout(() => {
        // Last resort targets only this spawned child's process tree, never unrelated local sessions.
        if (process.platform === 'win32') {
          const taskkill = join(process.env.SystemRoot ?? process.env.WINDIR ?? 'C:\\Windows', 'System32', 'taskkill.exe')
          execFile(taskkill, ['/pid', String(child.pid), '/t', '/f'], () => {})
        }
        else { try { process.kill(-child.pid, 'SIGKILL') } catch {} }
      }, CLEANUP_BUDGET_MS)
    }, CHILD_BUDGET_MS)
    child.stdout.on('data', append)
    child.stderr.on('data', append)
    child.on('error', cause => { clearTimeout(budget); error = cause.message; resolve(1) })
    child.on('close', code => { clearTimeout(budget); clearTimeout(forcedStop); resolve(timedOut ? 1 : code ?? 1) })
  })
  await writeFile(join(output, `${script}.log`), log)
  commands.push({ script, exitCode, timedOut, error })
  if (exitCode !== 0) process.exitCode = 1
}
let journeys = []
for (const [file, expected] of [['user-journeys.json', 10], ['reservations.json', 1]]) {
  try {
    const report = JSON.parse(await readFile(join(output, file), 'utf8'))
    if (report.results.length !== expected || report.results.some(result => result.status !== 'passed')) process.exitCode = 1
    journeys.push(...report.results)
  } catch (error) {
    journeys.push({ name: file, status: 'failed', error: `Missing/unreadable evidence: ${error.message}` })
    process.exitCode = 1
  }
}
await writeFile(join(output, 'results.json'), JSON.stringify({ measurement: 'controlled-browser-journeys',
  sourceCoverage: 'Not instrumented; Vitest coverage is reported independently.', commands, results: journeys }, null, 2))
console.log(`Desktop browser gate: ${journeys.filter(journey => journey.status === 'passed').length}/${journeys.length} journeys passed. Evidence: ${output}`)
