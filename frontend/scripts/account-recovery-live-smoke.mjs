/**
 * Integrated recovery check against the running app and real ArenaNet data.
 * Start the backend and Vite, then supply GW2_API_KEY in the environment and run:
 *   node scripts/account-recovery-live-smoke.mjs
 * Optional: GW2_FRONTEND_URL (default http://127.0.0.1:5173), GW2_SMOKE_TIMEOUT_MS.
 * Refreshes this account's disposable cache. Never prints credentials or response bodies.
 */
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

const key = process.env.GW2_API_KEY
if (!key) throw new Error('Supply GW2_API_KEY for the live integrated check')
const timeout = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 240_000)
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true })
const page = await browser.newPage()
page.setDefaultTimeout(timeout)
const calls = []
let stage = 'open key entry'
page.on('response', (response) => {
  const url = new URL(response.url())
  if (url.pathname.startsWith('/api/') && !url.pathname.includes('/icon/')) {
    calls.push({ path: url.pathname, status: response.status() })
  }
})
try {
  await page.goto(process.env.GW2_FRONTEND_URL ?? 'http://127.0.0.1:5173')
  const input = page.locator('[data-test="api-key-input"]')
  assert.equal(await input.getAttribute('type'), 'password')
  await input.fill(key)
  stage = 'prove key and bootstrap account'
  const proved = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/session')
  await page.locator('[data-test="connect"]').click()
  assert.equal((await proved).status(), 200, 'live account proof must succeed')
  await page.waitForFunction(() => document.querySelector('[data-test="connection-status"]')?.textContent
    ?.includes('Connected to your Guild Wars 2 account.'))
  await page.waitForFunction(() => !!document.querySelector('[data-test="refresh-data-and-results"], [data-test="bootstrap-failed"]'))
  assert.equal(await page.locator('[data-test="bootstrap-failed"]').count(), 0, 'initial scoped sync must succeed')
  stage = 'explicit fresh synchronization and Profit calculation'
  await page.locator('a[href="#/synchronization"]').click()
  await page.locator('[data-test="sync-trigger-ACCOUNT_SYNC"]').click()
  await page.waitForFunction(() => document.querySelector('[data-test="sync-state-ACCOUNT_SYNC"]')?.textContent?.trim() === 'Completed')
  assert.ok(calls.some((call) => call.path === '/api/sync/account' && call.status === 202))
  await page.locator('a[href="#/crafting"]').click()
  const calculated = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/crafting/profit' && response.status() === 200)
  await page.locator('[data-test="refresh-data-and-results"]').click()
  await calculated
  await page.waitForFunction(() =>
    !document.querySelector('[data-test="loading"]') &&
    !document.querySelector('[data-test="request-error"]') &&
    !!document.querySelector('[data-test="summary"], [data-test="empty"]'))
  assert.ok(calls.some((call) => call.path === '/api/crafting/selector-options' && call.status === 200))
  assert.ok(calls.some((call) => call.path === '/api/crafting/profit' && call.status === 200))
  assert.ok(!await page.locator('body').innerText().then((text) => /account_sync_state|DATA_STORE_UNAVAILABLE/.test(text)))
  assert.equal(await input.inputValue(), '')
  assert.equal(await page.locator('[data-test="results-region"]').count(), 1)
  if (process.env.GW2_SMOKE_SCREENSHOT) await page.screenshot({ path: process.env.GW2_SMOKE_SCREENSHOT })
  stage = 'reload retains the key and reconnects'
  await page.reload()
  await page.waitForFunction(() => document.querySelector('[data-test="connection-status"]')?.textContent
    ?.includes('Connected to your Guild Wars 2 account.'))
  assert.equal(await page.evaluate(() => sessionStorage.getItem('gw2.apiKey')), key)
  stage = 'Bank and Materials read scoped data'
  for (const [hash, path] of [['bank', '/api/account/bank'], ['materials', '/api/account/materials']]) {
    const response = page.waitForResponse((response) => new URL(response.url()).pathname === path && response.status() === 200)
    await page.locator(`a[href="#/${hash}"]`).click()
    await response
  }
  stage = 'disconnect clears browser account data and retained key'
  await page.locator('[data-test="disconnect"]').click()
  await page.waitForFunction(() => document.querySelector('[data-test="connection-status"]')?.textContent?.includes('Not connected.'))
  assert.equal(await page.evaluate(() => sessionStorage.getItem('gw2.apiKey')), null)
  assert.equal(await page.locator('[data-test="results-region"]').count(), 0)
  console.log(JSON.stringify({ result: 'passed', upstream: 'live ArenaNet', stages: [
    'masked entry', 'account proof', 'fresh synchronization', 'selector options', 'Profit calculation',
    'reload retention', 'Bank', 'Materials', 'disconnect'
  ], requests: calls }, null, 2))
} catch (error) {
  console.error(JSON.stringify({ result: 'failed', stage, errorType: error?.name ?? 'Error', requests: calls }, null, 2))
  process.exitCode = 1
} finally {
  await browser.close()
}
// This is a one-shot CLI; do not leave browser-driver handles keeping its shell alive.
process.exit(process.exitCode ?? 0)
