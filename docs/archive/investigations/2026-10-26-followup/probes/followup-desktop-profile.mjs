/** Investigation-only desktop profile: unchanged production build, synthetic economic values. */
import assert from 'node:assert/strict'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { gzipSync, brotliCompressSync, constants } from 'node:zlib'
import { createHash } from 'node:crypto'
import { chromium, selectors } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

selectors.setTestIdAttribute('data-test')
const base = 'http://127.0.0.1:5175'
const output = process.env.GW2_PROFILE_OUTPUT ?? 'quality-reports/followup'
await mkdir(output, { recursive: true })
const corpusBytes = await readFile('../quality-reports/catalog.json')
const corpus = JSON.parse(corpusBytes)
const settings = { useOwnMats: true, allowBuying: false, maxBuyCopper: 10000, listingSell: false, listingBuy: false, allowDailyCrafts: false }
const report = { commit: 'd713eb46c03e3c5cfa3cdfc90ad5d5216b559752', viewport: { width: 1920, height: 1080 },
  boundary: 'Unchanged production frontend, intercepted HTTP fixtures. Real catalog names/topology; invented positive economics to keep all rows visible. Not backend or Oracle latency.',
  catalogSha256: createHash('sha256').update(corpusBytes).digest('hex'), browser: null, cases: [] }
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true })
report.browser = browser.version()
try {
  for (const count of process.env.GW2_PROFILE_COUNTS ? process.env.GW2_PROFILE_COUNTS.split(',').map(Number) : [1000, 5000, corpus.recipes.length]) {
    const rows = corpus.recipes.slice(0, count).map((r, index) => ({
      recipeId: r.recipeId, outputItemId: r.outputItemId, outputName: corpus.itemNames[String(r.outputItemId)] ?? `Item ${r.outputItemId}`,
      outputCount: r.outputCount, disciplines: r.disciplinesText, minRating: r.minRating, resultAvailable: true,
      craftableCount: 5, buyCostCopper: 250, matsSellValueCopper: 120, totalMatsSellValueCopper: 600,
      revenueCopper: 380, profitCopper: 100, totalSellValueCopper: 2222, totalProfitCopper: 900 + index,
      blockedReason: 'NONE', outputPrice: { buyUnitCopper: 360, sellUnitCopper: 420 }, iconUrl: null,
      ingredientNames: r.ingredients.map(i => corpus.itemNames[String(i.itemId)] ?? `Item ${i.itemId}`),
      missingToBuy: r.ingredients.slice(0, 2).map(i => ({ itemId: i.itemId, itemName: corpus.itemNames[String(i.itemId)], quantity: i.count,
        price: { buyUnitCopper: 12, sellUnitCopper: 15 }, iconUrl: null, purchaseUnitPriceCopper: 15, totalPurchaseCostCopper: 60 })),
      missingToBuyOne: []
    }))
    const payload = JSON.stringify({ status: 'CALCULATED', scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 }, settings, rowCount: count, rows, freshness: null })
    const buffer = Buffer.from(payload)
    const compressionSamples = {}
    for (const [name, compress] of Object.entries({ gzipLevel1: () => gzipSync(buffer, { level: 1 }),
      gzipLevel6: () => gzipSync(buffer, { level: 6 }), brotliQuality5: () => brotliCompressSync(buffer, { params: { [constants.BROTLI_PARAM_QUALITY]: 5 } }) })) {
      compressionSamples[name] = Array.from({ length: 3 }, () => {
        const start = performance.now(); const result = compress(); return { milliseconds: performance.now() - start, bytes: result.length }
      })
    }
    const context = await browser.newContext({ viewport: report.viewport })
    const page = await context.newPage()
    page.setDefaultTimeout(120000)
    await page.addInitScript(() => {
      window.__followup = { longTasks: [], json: [] }
      new PerformanceObserver(list => window.__followup.longTasks.push(...list.getEntries().map(e => ({ start: e.startTime, duration: e.duration }))))
        .observe({ type: 'longtask', buffered: true })
      const original = Response.prototype.json
      Response.prototype.json = async function () {
        const start = performance.now()
        const value = await original.call(this)
        if (this.url.includes('/crafting/profit')) window.__followup.json.push({ start, end: performance.now() })
        return value
      }
    })
    let calculations = 0
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname
      if (!path.startsWith('/api/')) return route.continue()
      let body, status = 200
      if (path === '/api/session') body = { accessContext: 'fixture', expiresAt: '2099-01-01T00:00:00Z', grants: ['account', 'characters', 'inventories'] }
      else if (path === '/api/crafting/selector-options') body = { defaultScopeKind: 'ALL', disciplines: ['Chef'], characterOptionCount: 0, characterOptions: [] }
      else if (path === '/api/system/status') body = { running: false }
      else if (path === '/api/crafting/profit') { calculations++; return route.fulfill({ contentType: 'application/json', body: payload }) }
      else if (path === '/api/crafting/ingredient-search') body = { recipeIds: [] }
      else if (path === '/api/session/logout') return route.fulfill({ status: 204 })
      else { body = { error: 'FIXTURE_ROUTE', message: path }; status = 404 }
      return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    })
    await page.goto(`${base}/#/synchronization`)
    await page.getByTestId('api-key-input').fill('read-only-fixture')
    await page.getByTestId('connect').click()
    await page.getByTestId('connection-status').filter({ hasText: 'Connected to your Guild Wars 2 account.' }).waitFor()
    await page.getByTestId('nav-crafting').click()
    await page.waitForFunction(() => document.querySelectorAll('[data-test="profit-row"]').length === 250)
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))))
    const initial = await page.evaluate(() => ({ json: window.__followup.json, end: performance.now(), dom: document.getElementsByTagName('*').length,
      heap: performance.memory?.usedJSHeapSize, longTasks: [...window.__followup.longTasks] }))
    const measure = async (action, expectedRows) => {
      const start = await page.evaluate(() => performance.now())
      await action()
      await page.waitForFunction(n => document.querySelectorAll('[data-test="profit-row"]').length === n, expectedRows)
      await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))))
      return page.evaluate(start => ({ elapsedMs: performance.now() - start, dom: document.getElementsByTagName('*').length,
        heap: performance.memory?.usedJSHeapSize }), start)
    }
    const showAll = await measure(() => page.getByTestId('show-all').check(), count)
    const sortStart = await page.evaluate(() => performance.now())
    await page.getByTestId('sort-outputName').click()
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))))
    const sortMs = await page.evaluate(t => performance.now() - t, sortStart)
    const filter = await measure(() => page.getByTestId('search').fill('fixture-no-such-recipe-987'), 0)
    const restore = await measure(() => page.getByTestId('search').fill(''), count)
    const parseMs = await page.evaluate(text => Array.from({ length: 3 }, () => {
      const t = performance.now(); const value = JSON.parse(text); const ms = performance.now() - t
      if (!value.rows.length) throw new Error('No rows'); return ms
    }), payload)
    assert.equal(calculations, 1, 'Show all, filtering and sorting must not recalculate')
    assert.deepEqual(errors, [])
    const longTasks = await page.evaluate(() => window.__followup.longTasks)
    const entry = { count, payloadBytes: Buffer.byteLength(payload), gzipLevel6Bytes: gzipSync(payload).length,
      brotliQuality5Bytes: brotliCompressSync(payload, { params: { [constants.BROTLI_PARAM_QUALITY]: 5 } }).length,
      pureJsonParseMs: parseMs, compressionSamples, initial, showAll, sortMs, filter, restore, longTasks, calculations, pageErrors: errors }
    report.cases.push(entry)
    console.log(JSON.stringify({ count, showAllMs: showAll.elapsedMs, sortMs, filterMs: filter.elapsedMs, restoreMs: restore.elapsedMs }))
    await writeFile(`${output}/desktop-profile.json`, JSON.stringify(report, null, 2))
    await context.close()
  }
} finally { await browser.close() }
