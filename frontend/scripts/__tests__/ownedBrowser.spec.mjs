// @vitest-environment node
import { spawn } from 'node:child_process'
import { pathToFileURL } from 'node:url'
import { expect, it } from 'vitest'
import { resolveBrowserPath } from '../resolveBrowserPath.mjs'

let browserPath
try { browserPath = resolveBrowserPath() } catch { browserPath = undefined }

const moduleUrl = pathToFileURL(new URL('../ownedBrowser.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')).href

/**
 * Runs closeOwnedBrowser in its OWN Node process and requires that process to exit by itself (no process.exit,
 * no unref'd workaround): a lingering BrowserServer/stdio handle would keep it alive until the timeout kills it.
 */
function runChild(hangGracefulClose) {
  const code = `
    import { launchOwnedBrowser, closeOwnedBrowser } from ${JSON.stringify(moduleUrl)}
    const owned = await launchOwnedBrowser({ executablePath: process.env.TEST_BROWSER, headless: true, args: ['--no-sandbox'] })
    ${hangGracefulClose ? 'owned.browser.close = () => new Promise(() => {})' : ''}
    const pid = owned.child.pid
    await closeOwnedBrowser(owned)
    await closeOwnedBrowser(owned) // repeated cleanup stays safe
    let alive = true
    try { process.kill(pid, 0) } catch { alive = false }
    console.log(JSON.stringify({ pid, alive }))
  `
  return new Promise(resolve => {
    const child = spawn(process.execPath, ['--input-type=module', '-e', code], {
      env: { ...process.env, TEST_BROWSER: browserPath }, stdio: ['ignore', 'pipe', 'pipe']
    })
    let out = ''
    child.stdout.on('data', d => { out += d })
    const timer = setTimeout(() => { child.kill('SIGKILL') }, 40_000)
    child.on('exit', (exitCode, signal) => { clearTimeout(timer); resolve({ exitCode, signal, out }) })
  })
}

it.skipIf(!browserPath)('lets the Node worker exit by itself after a graceful close', async () => {
  const r = await runChild(false)
  expect(r.signal).toBeNull()
  expect(r.exitCode).toBe(0)
  expect(JSON.parse(r.out.trim().split('\n').pop()).alive).toBe(false)
}, 60_000)

it.skipIf(!browserPath)('lets the Node worker exit by itself after a hung graceful close forced the fallback', async () => {
  const r = await runChild(true)
  expect(r.signal).toBeNull()
  expect(r.exitCode).toBe(0)
  expect(JSON.parse(r.out.trim().split('\n').pop()).alive).toBe(false)
}, 60_000)
