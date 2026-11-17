/** Required sequential browser gate. Journey counts are separate from Vitest/V8 source coverage. */
import { spawn } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { join, resolve } from 'node:path'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const output = resolve(process.env.GW2_SMOKE_OUTPUT ?? join(frontend, 'quality-reports/desktop-journeys'))
await mkdir(output, { recursive: true })
const commands = []
for (const script of ['user-journeys.mjs', 'reservation-browser-smoke.mjs']) {
  let log = ''
  let error = null
  const exitCode = await new Promise(resolve => {
    const child = spawn(process.execPath, [join(frontend, 'scripts', script)], { cwd: frontend,
      env: { ...process.env, GW2_SMOKE_OUTPUT: output }, stdio: ['ignore', 'pipe', 'pipe'] })
    const append = chunk => { process.stdout.write(chunk); log = (log + chunk.toString()).slice(-200_000) }
    child.stdout.on('data', append)
    child.stderr.on('data', append)
    child.on('error', cause => { error = cause.message; resolve(1) })
    child.on('close', code => resolve(code ?? 1))
  })
  await writeFile(join(output, `${script}.log`), log)
  commands.push({ script, exitCode, error })
  if (exitCode !== 0) process.exitCode = 1
}
let journeys = []
for (const [file, expected] of [['user-journeys.json', 5], ['reservations.json', 1]]) {
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
