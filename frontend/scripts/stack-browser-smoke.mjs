/**
 * Real-browser check of the running Compose stack (STORY-INFRA-008): navigation and connection UI
 * served by the frontend container, with API calls going to the same origin through its proxy (proxied reads are checked by compose-smoke.sh; the app issues none before a key).
 * Offline: no key is entered, no synchronization is started, nothing is written.
 *
 * Usage:  GW2_FRONTEND_URL=http://127.0.0.1:<port> node scripts/stack-browser-smoke.mjs
 * Environment: GW2_FRONTEND_URL (required), GW2_BROWSER_PATH, GW2_SMOKE_TIMEOUT_MS (default 30000)
 * Exits 0 when every step passed, 1 otherwise.
 */
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

const FRONTEND_URL = process.env.GW2_FRONTEND_URL
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
if (!FRONTEND_URL) {
  console.error('GW2_FRONTEND_URL is required (the Compose frontend port)')
  process.exit(1)
}
const DESTINATIONS = ['crafting', 'discovery', 'ecto', 'synchronization', 'bank', 'materials']

const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true })
let failed = false
try {
  const page = await browser.newPage()
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(error.name))
  page.setDefaultTimeout(TIMEOUT_MS)
  const origin = new URL(FRONTEND_URL).origin
  const apiResponses = []
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/')) apiResponses.push({ origin: url.origin, path: url.pathname, status: response.status() })
  })
  await page.goto(FRONTEND_URL)
  await page.waitForSelector('[data-test="screen-nav"]')
  console.log('  ok   shell rendered with navigation')
  for (const id of DESTINATIONS) {
    await page.locator(`[data-test="nav-${id}"]`).click()
    await page.waitForFunction((hash) => location.hash === hash, `#/${id}`)
  }
  console.log(`  ok   navigated to ${DESTINATIONS.length} destinations`)
  await page.waitForSelector('[data-test="connection-panel"]')
  const status = (await page.locator('[data-test="connection-status"]').innerText()).trim()
  console.log(`  ok   connection panel visible — status: ${status}`)
  if ((await page.locator('[data-test="api-key-input"]').count()) === 0) throw new Error('no key entry field offered')
  console.log('  ok   key entry offered in the browser (no key entered)')
  await page.waitForTimeout(1000)
  const foreign = apiResponses.filter((r) => r.origin !== origin)
  if (foreign.length > 0) throw new Error(`API requests left the frontend origin: ${JSON.stringify(foreign)}`)
  const serverErrors = apiResponses.filter((r) => r.status >= 500)
  if (serverErrors.length > 0) throw new Error(`API responded 5xx: ${JSON.stringify(serverErrors)}`)
  if (pageErrors.length > 0) throw new Error(`Browser page errors: ${pageErrors.join(', ')}`)
  console.log(`  ok   ${apiResponses.length} API responses, all same-origin, none 5xx (the app makes none before a key is entered)`)
} catch (error) {
  failed = true
  console.error(`FAIL: ${error instanceof Error ? error.message : error}`)
} finally {
  // Windows browser-driver cleanup can stall after the browser has already exited.
  await Promise.race([browser.close(), new Promise((resolve) => setTimeout(resolve, 10_000))])
}
process.exit(failed ? 1 : 0)
