/**
 * Real-browser check of the current Ecto Salvage page against a controlled API origin.
 * Usage: npm run build && npm run smoke:ecto
 */
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startStubOrigin } from './stubOrigin.mjs'

const PORT = Number(process.env.GW2_ECTO_SMOKE_PORT ?? 5182)
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
const DIST_DIR = fileURLToPath(new URL('../dist/', import.meta.url))
const ECTO_ID = 19721
const DUST_ID = 24277
const METADATA_IDS = [ECTO_ID, DUST_ID, 44602, 23041, 89409, 67027, 19986]
const PRICE_IDS = [ECTO_ID, DUST_ID]

const steps = []
let priceReads = 0

function check(condition, message) {
  if (!condition) throw new Error(message)
}

function record(name) {
  steps.push(name)
  console.log('  ok   ' + name)
}

function requestedIds(url) {
  return (url.searchParams.get('ids') ?? '').split(',').map(Number)
}

function answerApi({ url, sendJson }) {
  if (url.pathname === '/api/items/metadata') {
    return sendJson(200, {
      items: requestedIds(url).map((itemId) => ({
        itemId,
        name: itemId === ECTO_ID ? 'Glob of Ectoplasm' : itemId === DUST_ID ? 'Pile of Crystalline Dust' : 'Salvage tool',
        iconUrl: null
      }))
    })
  }
  if (url.pathname === '/api/items/prices') {
    priceReads += 1
    const refreshed = priceReads > 1
    return sendJson(200, {
      prices: [
        { itemId: ECTO_ID, buyUnitCopper: refreshed ? 110 : 100, sellUnitCopper: refreshed ? 130 : 120 },
        { itemId: DUST_ID, buyUnitCopper: refreshed ? 210 : 200, sellUnitCopper: refreshed ? 250 : 240 }
      ]
    })
  }
  if (url.pathname === '/api/account/luck') {
    return sendJson(200, {
      consumedLuck: 14134,
      currentLuckMagicFindPercent: 50,
      cumulativeLuckForCurrentPercent: 13790,
      nextMagicFindPercent: 51,
      cumulativeLuckForNextPercent: 14550,
      luckRemainingToNextPercent: 416,
      luckRemainingToCap: 4281316,
      cumulativeLuckForCap: 4295450,
      fetchedAt: '2026-09-29T00:00:00Z',
      targets: [
        { kind: 'PLUS_5', magicFindPercent: 55, cumulativeLuck: 17930, luckRemaining: 3796 },
        { kind: 'PLUS_10', magicFindPercent: 60, cumulativeLuck: 23050, luckRemaining: 8916 },
        { kind: 'CAP', magicFindPercent: 300, cumulativeLuck: 4295450, luckRemaining: 4281316 }
      ]
    })
  }
  return sendJson(404, { error: 'NOT_FOUND', message: url.pathname })
}

async function figure(page, label) {
  return page.evaluate((wanted) => {
    const rows = document.querySelectorAll(
      '.salvage-calculation .calculation-row, .salvage-calculation .calculation-total'
    )
    const row = [...rows].find((candidate) => candidate.querySelector('span')?.textContent.trim() === wanted)
    return row?.textContent.replace(/\s+/g, ' ').trim() ?? null
  }, label)
}

async function run() {
  check(existsSync(DIST_DIR + 'index.html'), 'frontend/dist is missing; run npm run build first.')
  const browserPath = resolveBrowserPath()
  const origin = await startStubOrigin({ port: PORT, distDir: DIST_DIR, answerApi })
  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const pageErrors = []
  const browserRequests = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))
  page.on('request', (request) => browserRequests.push(request.url()))

  try {
    await page.goto(origin.origin, { waitUntil: 'domcontentloaded', timeout: TIMEOUT_MS })
    await page.waitForSelector('[data-test="nav-ecto"]', { timeout: TIMEOUT_MS })
    await page.click('[data-test="nav-ecto"]')
    await page.waitForSelector('.salvage-calculation', { timeout: TIMEOUT_MS })
    await page.waitForSelector('.account-luck', { timeout: TIMEOUT_MS })

    check(origin.servedCount() > 0, 'The stub origin did not serve the page.')
    check(new URL(page.url()).hash === '#/ecto', 'Ecto is not addressable at its own URL.')
    check((await page.title()) === 'Ecto Salvage · GW2 Crafting Tool', 'The document title is stale.')
    check((await page.locator('[data-page-heading]').innerText()) === 'Ecto Salvage', 'The page heading is stale.')
    check(origin.requestsTo('/api/ecto/salvage').length === 0, 'The removed calculation route was called.')
    check(origin.requestsTo('/api/items/metadata').length === 1, 'Expected one metadata read.')
    check(origin.requestsTo('/api/items/prices').length === 1, 'Expected one price read.')
    check(origin.requestsTo('/api/account/luck').length === 1, 'Expected one account Luck read.')
    const metadataRequest = browserRequests.find((request) => request.includes('/api/items/metadata?'))
    const priceRequest = browserRequests.find((request) => request.includes('/api/items/prices?'))
    check(metadataRequest !== undefined, 'Metadata request missing.')
    check(priceRequest !== undefined, 'Price request missing.')
    check(JSON.stringify(requestedIds(new URL(metadataRequest))) === JSON.stringify(METADATA_IDS), 'Metadata IDs differ.')
    check(JSON.stringify(requestedIds(new URL(priceRequest))) === JSON.stringify(PRICE_IDS), 'Prices requested more than Ecto and Dust.')
    record('destination and three current input reads')

    check((await figure(page, 'Ecto value consumed'))?.includes('-1g 20s 0c'), 'Default Ecto value is wrong.')
    check((await figure(page, 'Dust value after TP fees'))?.includes('+3g 14s 50c'), 'Default Dust proceeds are wrong.')
    check((await figure(page, 'Salvage tool cost'))?.includes('-60s 0c'), 'Tool cost is missing.')
    check((await figure(page, 'Effective cost'))?.includes('-1g 34s 50c'), 'Effective cost is wrong.')
    check((await page.locator('.ecto-value-note').innerText()).includes("They aren't free to salvage"), 'Owned Ecto valuation is unexplained.')
    check((await page.locator('.source-line').innerText()).includes('statistical averages'), 'Yields are not qualified.')
    check((await page.locator('.target-header').innerText()).includes('Ecto value'), 'Target value column is missing.')
    check((await page.locator('.target-header').innerText()).includes('Effective cost'), 'Target effective cost is missing.')
    check((await page.locator('.luck-progress__bar').evaluate((element) => element.style.width)) !== '0%', 'Magic Find progress is empty.')
    record('structured cost, target and Magic Find presentation')

    const callsBeforeControls = origin.requestsTo('/api/').length
    await page.locator('#ecto-count').fill('10')
    await page.getByRole('button', { name: /Basic \/ Copper-Fed/ }).click()
    await page.locator('.tp-block').first().getByRole('button', { name: /Buy order/ }).click()
    await page.locator('.tp-block').last().getByRole('button', { name: /Listing sell/ }).click()
    check((await figure(page, 'Ecto value consumed'))?.includes('-10s 0c'), 'Selected Buy Order value was not used.')
    check(origin.requestsTo('/api/').length === callsBeforeControls, 'A local control made another API request.')
    record('amount, method and TP modes recalculate locally')

    await page.locator('[data-test="tp-price-disclaimer-trigger"]').click()
    await page.waitForSelector('[data-test="tp-price-disclaimer-dialog"][open]', { timeout: TIMEOUT_MS })
    const warning = await page.locator('[data-test="tp-price-disclaimer-dialog"]').innerText()
    check(warning.includes('CHECK IN-GAME PRICE & QUANTITY'), 'The shared warning action is missing.')
    check(warning.includes('order-book depth'), 'The market-depth limitation is missing.')
    await page.locator('[data-test="tp-price-disclaimer-close"]').click()
    record('shared TP quantity warning opens and closes')

    await page.getByRole('button', { name: 'Refresh TP prices' }).click()
    await page.waitForFunction(
      () => [...document.querySelectorAll('.tp-block')][0]?.textContent.includes('1s 30c'),
      undefined,
      { timeout: TIMEOUT_MS }
    )
    check(origin.requestsTo('/api/items/prices').length === 2, 'Price refresh did not read prices once.')
    check(origin.requestsTo('/api/items/metadata').length === 1, 'Price refresh reread metadata.')
    check(origin.requestsTo('/api/account/luck').length === 1, 'Price refresh reread account Luck.')
    record('manual refresh reads only the two TP prices')

    await page.setViewportSize({ width: 360, height: 800 })
    const overflow = await page.evaluate(() => ({
      width: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth
    }))
    check(overflow.scrollWidth <= overflow.width + 1, 'Ecto page overflows the narrow viewport: ' + JSON.stringify(overflow))
    const targetRegion = await page.locator('.magic-find-section .table-region').evaluate((element) => ({
      width: element.clientWidth,
      scrollWidth: element.scrollWidth,
      tabIndex: element.tabIndex
    }))
    check(targetRegion.scrollWidth > targetRegion.width, 'The target table does not scroll inside its region.')
    check(targetRegion.tabIndex === 0, 'The scrolling target table is not keyboard reachable.')
    check(pageErrors.length === 0, 'Uncaught browser errors: ' + pageErrors.join(' | '))
    check(browserRequests.every((request) => request.startsWith(origin.origin)), 'The browser left the stub origin.')
    record('narrow layout and same-origin requests')

    console.log('\nEcto browser smoke PASSED (' + steps.length + ' steps).')
  } finally {
    await browser.close()
    origin.close()
  }
}

run().catch((error) => {
  console.error('\nEcto browser smoke FAILED after ' + steps.length + ' step(s): ' + error.message)
  process.exitCode = 1
})
