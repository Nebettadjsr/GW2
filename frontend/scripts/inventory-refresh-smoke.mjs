/** Browser check of inventory refresh/recovery with controlled HTTP fixtures; no ArenaNet calls. */
import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { chromium, selectors } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

const base = process.env.GW2_FRONTEND_URL ?? 'http://127.0.0.1:5174'
const output = process.env.GW2_SMOKE_OUTPUT ?? 'quality-reports/inventory-refresh'
selectors.setTestIdAttribute('data-test')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true })
try {
  for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
    const context = await browser.newContext({ viewport })
    const page = await context.newPage()
    page.setDefaultTimeout(15_000)
    const errors = []
    page.on('pageerror', (error) => errors.push(error.message))
    let readFails = false
    let count = 42
    let refreshes = 0
    let bankReads = 0
    const freshness = { status: 'STALE', completedAt: '2026-10-26T08:00:00Z', refresh: 'CREDENTIAL_REQUIRED' }
    await page.route('**/api/**', async (route) => {
      const path = new URL(route.request().url()).pathname
      if (!path.startsWith('/api/')) return route.continue()
      let body
      let status = 200
      if (path === '/api/session') body = { accessContext: 'fixture-context', expiresAt: '2099-01-01T00:00:00Z', grants: ['account', 'inventories', 'characters'] }
      else if (path === '/api/session/logout') return route.fulfill({ status: 204 })
      else if (path === '/api/crafting/selector-options') body = { defaultScopeKind: 'ALL', disciplines: [], characterOptionCount: 0, characterOptions: [] }
      else if (path === '/api/system/status') body = { running: false, accountLastRefreshedAt: freshness.completedAt }
      else if (path === '/api/account/bank') {
        bankReads++
        if (readFails) { body = { error: 'ACCOUNT_READ_FAILED', message: 'Controlled read failure.' }; status = 503 }
        else body = { slotCount: 30, slots: Array.from({ length: 30 }, (_, slot) => ({ slot,
          itemId: slot === 0 ? 19697 : null, count: slot === 0 ? count : null, iconUrl: null, rarity: 'Basic' })), freshness }
      } else if (path === '/api/account/materials') body = { categoryCount: 1, categories: [{ category: 1, order: 1, name: 'Crafting materials',
        materials: [{ position: 0, itemId: 19697, count, iconUrl: null, rarity: 'Basic' }] }], freshness }
      else if (path === '/api/sync/account') {
        refreshes++
        assert.ok(route.request().headers()['x-gw2-api-key'])
        body = { taskId: `refresh-${refreshes}`, operation: 'ACCOUNT_SYNC', statusUrl: `/api/sync/tasks/refresh-${refreshes}`, coversRequest: true }
        status = 202
      } else if (path.startsWith('/api/sync/tasks/')) {
        count = 77
        freshness.status = 'CURRENT'
        freshness.completedAt = '2026-10-26T09:00:00Z'
        body = { taskId: path.split('/').at(-1), operation: 'ACCOUNT_SYNC', state: 'SUCCEEDED', failure: null }
      } else { body = { error: 'NOT_FOUND', message: 'Unexpected fixture request' }; status = 404 }
      return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    })
    await page.goto(`${base}/#/synchronization`)
    await page.getByTestId('api-key-input').fill('browser-fixture-read-only-key')
    await page.getByTestId('connect').click()
    await page.getByTestId('connection-status').filter({ hasText: 'Connected to your Guild Wars 2 account.' }).waitFor()
    await page.getByTestId('nav-bank').click()
    await page.getByTestId('bank-slot').first().waitFor()
    assert.match(await page.getByTestId('inventory-freshness').innerText(), /may be out of date/)
    assert.equal(await page.getByTestId('api-key-input').count(), 0)
    await page.getByTestId('inventory-refresh').click()
    await page.waitForFunction(() => document.querySelector('[data-test="bank-slot"]')?.textContent?.includes('77'))
    assert.equal(refreshes, 1)
    assert.equal(bankReads, 2)
    readFails = true
    await page.getByTestId('bank-reload').click()
    await page.getByTestId('bank-error').waitFor()
    assert.match(await page.getByTestId('bank-error').innerText(), /previous saved snapshot/)
    assert.match(await page.getByTestId('bank-slot').first().innerText(), /77/)
    await page.screenshot({ path: `${output}/bank-error-${viewport.width}.png`, fullPage: true })
    readFails = false
    await page.getByTestId('bank-retry').click()
    await page.getByTestId('bank-error').waitFor({ state: 'detached' })
    await page.getByTestId('nav-materials').click()
    await page.getByTestId('material-category').waitFor()
    await page.getByTestId('inventory-refresh').click()
    await page.getByTestId('inventory-refresh-status').filter({ hasText: 'Completed' }).waitFor()
    assert.equal(refreshes, 2)
    assert.equal(await page.getByTestId('api-key-input').count(), 0)
    const overflows = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
    assert.equal(overflows, false, 'No document-wide overflow')
    await page.screenshot({ path: `${output}/materials-${viewport.width}.png`, fullPage: true })
    await page.getByTestId('nav-synchronization').click()
    await page.getByTestId('disconnect').click()
    await page.getByTestId('nav-bank').click()
    await page.getByTestId('connection-needed').waitFor()
    assert.equal(await page.getByTestId('bank-slot').count(), 0)
    assert.deepEqual(errors, [])
    console.log(`Inventory browser check passed: ${viewport.width}px; refresh, recovery, freshness and disconnect`)
    await context.close()
  }
} finally { await browser.close() }
