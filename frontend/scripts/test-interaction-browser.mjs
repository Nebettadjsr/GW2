// Explicit browser integration checks; normal Vitest needs no installed Chrome/Edge.
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const specs = process.argv.slice(2)
const result = spawnSync(process.execPath, [
  fileURLToPath(new URL('../node_modules/vitest/vitest.mjs', import.meta.url)),
  'run', ...(specs.length ? specs : ['scripts/__tests__/interactionRecorder.spec.mjs'])
], { stdio: 'inherit', env: { ...process.env, GW2_RECORDER_BROWSER: '1' } })
if (result.error) console.error(result.error.message)
process.exitCode = result.status ?? 1
