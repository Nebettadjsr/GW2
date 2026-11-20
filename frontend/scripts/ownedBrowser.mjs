/**
 * Browser lifecycle owned by one smoke script. The browser is started through Playwright's public launchServer so
 * the exact child process this script created stays addressable: a hung graceful close falls back to force-stopping
 * ONLY that process (tree on Windows) and waiting for its exit. Nothing else - no other Chrome/Edge, no app - is touched.
 */
import { execFile } from 'node:child_process'
import { chromium } from 'playwright-core'

const GRACEFUL_MS = 4_000
const EXIT_WAIT_MS = 4_000 // GRACEFUL + EXIT_WAIT stays inside the parent's 10s cleanup budget

export async function launchOwnedBrowser(options) {
  const server = await chromium.launchServer(options)
  const child = server.process()
  try {
    const browser = await chromium.connect(server.wsEndpoint())
    return { browser, server, child }
  } catch (error) {
    await forceStop({ server, child }).catch(() => {})
    throw error
  }
}

function exited(child) { return child.exitCode !== null || child.signalCode !== null }

function waitForExit(child, ms) {
  if (exited(child)) return Promise.resolve(true)
  return new Promise(resolve => {
    const timer = setTimeout(() => { child.off('exit', done); resolve(exited(child)) }, ms)
    const done = () => { clearTimeout(timer); resolve(true) }
    child.once('exit', done)
  })
}

async function forceStop({ server, child }) {
  if (exited(child)) return true
  if (process.platform === 'win32' && child.pid) {
    const taskkill = `${process.env.SystemRoot ?? process.env.WINDIR ?? 'C:\\Windows'}\\System32\\taskkill.exe`
    await new Promise(resolve => execFile(taskkill, ['/pid', String(child.pid), '/t', '/f'], () => resolve()))
  } else {
    try { await server.kill() } catch { try { child.kill('SIGKILL') } catch {} }
  }
  return waitForExit(child, EXIT_WAIT_MS)
}

/**
 * Releases the Node-side resources this script created for the browser (driver connection, BrowserServer websocket,
 * the child's stdio pipes). Only called AFTER the owned process is confirmed gone, so nothing live is cut off. Each
 * step is bounded and idempotent: repeated or timed-out cleanup stays safe and never hangs.
 */
async function releaseOwnedResources({ browser, server, child }) {
  const bounded = async (promise, ms) => {
    let timer
    try { await Promise.race([Promise.resolve(promise).catch(() => {}), new Promise(r => { timer = setTimeout(r, ms) })]) }
    finally { clearTimeout(timer) }
  }
  await bounded(browser.close(), 1_000)
  await bounded(server.close(), 1_000)
  for (const stream of [child.stdin, child.stdout, child.stderr]) {
    try { if (stream && !stream.destroyed) stream.destroy() } catch { /* already released */ }
  }
  try { child.unref() } catch { /* already released */ }
}

/** Resolves when the owned browser process is confirmed gone; throws otherwise so the caller fails nonzero. */
export async function closeOwnedBrowser(handle) {
  if (!handle) return
  const { browser, server, child } = handle
  let timer
  const graceful = Promise.race([
    (async () => { await browser.close().catch(() => {}); await server.close().catch(() => {}); return waitForExit(child, GRACEFUL_MS) })(),
    new Promise(resolve => { timer = setTimeout(() => resolve(false), GRACEFUL_MS) })
  ])
  const clean = await graceful
  clearTimeout(timer)
  if (clean) { await releaseOwnedResources(handle); return }
  console.error(`Owned browser (pid ${child.pid}) did not close within ${GRACEFUL_MS}ms; force-stopping only that process`)
  if (!(await forceStop(handle))) throw new Error(`Owned browser process ${child.pid} could not be confirmed stopped`)
  await releaseOwnedResources(handle)
}
