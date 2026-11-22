/**
 * Real-browser check of the running Compose stack (STORY-INFRA-008): navigation and connection UI
 * served by the frontend container, with API calls going to the same origin through its proxy (proxied reads are checked by compose-smoke.sh; the app issues none before a key).
 * Offline: no key is entered, no synchronization is started, nothing is written.
 *
 * Usage:  GW2_FRONTEND_URL=http://127.0.0.1:<port> node scripts/stack-browser-smoke.mjs
 * Environment: GW2_FRONTEND_URL (required), GW2_BROWSER_PATH, GW2_SMOKE_TIMEOUT_MS (default 30000),
 *   GW2_SMOKE_HEADLESS (default true; set false to show the browser)
 * Exits 0 when every step passed, 1 otherwise.
 */
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

const FRONTEND_URL = process.env.GW2_FRONTEND_URL
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
if (!FRONTEND_URL) {
  console.error('GW2_FRONTEND_URL is required (the Compose frontend port)')
  process.exit(1)
}
const DESTINATIONS = ['home', 'crafting', 'discovery', 'ecto', 'synchronization', 'bank', 'materials', 'characters']

const browser = await chromium.launch({
  executablePath: resolveBrowserPath(),
  headless: process.env.GW2_SMOKE_HEADLESS !== 'false'
})
let failed = false
try {
  const page = await browser.newPage()
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(error.name))
  page.setDefaultTimeout(TIMEOUT_MS)
  page.setDefaultNavigationTimeout(TIMEOUT_MS)
  const origin = new URL(FRONTEND_URL).origin
  const apiRequests = []
  const apiResponses = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname.startsWith('/api/')) apiRequests.push({ origin: url.origin, path: url.pathname })
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/')) apiResponses.push({ origin: url.origin, path: url.pathname, status: response.status() })
  })
  const homeResponse = await page.request.get(`${origin}/home`, { maxRedirects: 0 })
  assert.equal(homeResponse.status(), 200, 'Canonical Home did not serve its public document')
  assert.equal(homeResponse.headers()['x-robots-tag'], 'index, follow')
  for (const legacyPath of ['/welcome', '/welcome.html']) {
    const redirect = await page.request.get(`${origin}${legacyPath}`, { maxRedirects: 0 })
    assert.equal(redirect.status(), 308, `${legacyPath} did not redirect permanently`)
    assert.equal(redirect.headers().location, '/home', `${legacyPath} leaked a noncanonical redirect origin or path`)
    assert.equal(redirect.headers()['x-robots-tag'], 'noindex, nofollow')
  }
  console.log('  ok   Home is indexable at /home; both legacy URLs redirect to relative /home with noindex')
  await page.goto(FRONTEND_URL)
  await page.waitForSelector('[data-test="screen-nav"]')
  console.log('  ok   shell rendered with navigation')
  for (const id of DESTINATIONS) {
    await page.locator(`[data-test="nav-${id}"]`).click()
    await page.waitForFunction((destination) => destination === 'home'
      ? location.pathname === '/home' && location.hash === ''
      : location.pathname === '/' && location.hash === `#/${destination}`, id)
    if (await page.locator(`[data-test="nav-${id}"]`).getAttribute('aria-current') !== 'page') {
      throw new Error(`${id} is not marked as the current destination`)
    }
    if (id === 'synchronization') {
      await page.waitForSelector('[data-test="connection-panel"]')
    } else {
      await page.waitForSelector(id === 'home'
        ? '[data-test="home-screen"]' : '[data-test="connection-needed-link"]')
      if (await page.locator('[data-test="connection-panel"]').count()) throw new Error(`key form repeated on ${id}`)
      if (await page.locator('[data-test="api-key-input"]').count()) throw new Error(`key entry repeated on ${id}`)
    }
  }
  console.log(`  ok   navigated to ${DESTINATIONS.length} destinations`)
  await page.locator('[data-test="nav-synchronization"]').click()
  await page.waitForSelector('[data-test="connection-panel"]')
  const panel = page.locator('[data-test="connection-panel"]')
  const input = page.locator('[data-test="api-key-input"]')
  const connect = page.locator('[data-test="connect"]')
  if (await panel.count() !== 1 || await input.count() !== 1 || await connect.count() !== 1) {
    throw new Error('System Status must offer exactly one key entry and Connect action')
  }
  if (!(await input.isVisible()) || !(await input.isEnabled())) throw new Error('key entry is not ready')
  if (await input.inputValue() !== '') throw new Error('key entry is not empty in a fresh browser session')
  if (await input.getAttribute('type') !== 'password') throw new Error('key entry is not masked')
  if (!(await connect.isVisible()) || !(await connect.isEnabled()) || (await connect.innerText()).trim() !== 'Connect') {
    throw new Error('the disconnected Connect action is not ready')
  }
  if (await page.locator('[data-test="disconnect"], [data-test="retry-connection"]').count()) {
    throw new Error('a fresh browser session unexpectedly offers disconnect or retry')
  }
  console.log('  ok   disconnected key entry is empty, masked and ready; Connect is enabled (no key entered)')
  await page.waitForTimeout(1000)
  const foreign = apiResponses.filter((r) => r.origin !== origin)
  if (foreign.length > 0) throw new Error(`API requests left the frontend origin: ${JSON.stringify(foreign)}`)
  const serverErrors = apiResponses.filter((r) => r.status >= 500)
  if (serverErrors.length > 0) throw new Error(`API responded 5xx: ${JSON.stringify(serverErrors)}`)
  if (apiRequests.length > 0) throw new Error(`Keyless navigation made unexpected API requests: ${JSON.stringify(apiRequests)}`)
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
