/**
 * STORY-WEB-013: live comparison and runtime measurement for the Ectoplasm Salvage page,
 * complementing the controlled-response `smoke:ecto`.
 *
 * Two things this can establish that the controlled check cannot:
 *
 *   1. **Representative runtime** of `GET /api/ecto/salvage` against the real backend and the real
 *      Trading Post, which `TARGET_ARCHITECTURE.md` 23 requires before this route's synchronous
 *      versus task-based execution is decided. A bounded number of sequential samples, timed at the
 *      caller; nothing is assumed about the duration.
 *   2. **That the page renders the live answer it actually received** — every expected figure is read
 *      out of the very response the browser got, formatted the way the page formats it. No value is
 *      derived, and no domain number is asserted, so a changed market moves both sides together.
 *
 * Read-only by construction: this route runs one calculation over live quotes. It writes nothing,
 * touches no account, starts no synchronization and needs no database content.
 *
 * Prerequisites, both started by hand:
 *   1. the backend    — `./mvnw spring-boot:run` in the repository root
 *   2. the dev server — `npm run dev` in `frontend/` (it proxies `/api` to the backend)
 *
 * Usage:  npm run smoke:ecto:live
 * Environment:
 *   GW2_FRONTEND_URL        page origin           (default http://localhost:5173)
 *   GW2_BACKEND_ORIGIN      backend for the timing samples (default http://localhost:8080)
 *   GW2_ECTO_LIVE_SAMPLES   timing samples        (default 5)
 *   GW2_BROWSER_PATH, GW2_SMOKE_TIMEOUT_MS   as in the other checks
 *
 * Exits 0 when every step passed, 1 otherwise. A backend that is unreachable, answers 502 or has no
 * usable quotes fails the run and says which — it is never reported as a pass.
 */
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

const origin = process.env.GW2_FRONTEND_URL ?? 'http://localhost:5173'
const backendOrigin = process.env.GW2_BACKEND_ORIGIN ?? 'http://localhost:8080'
const samples = Number(process.env.GW2_ECTO_LIVE_SAMPLES ?? 5)
const timeout = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 60_000)
const ectoPath = '/api/ecto/salvage'

/** Formatting only: the copper value is always one the response supplied, never derived here. */
function money(copper) {
  if (copper === null || copper === undefined) return '—'
  const amount = Math.abs(copper)
  const gold = Math.floor(amount / 10_000)
  const silver = Math.floor((amount % 10_000) / 100)
  const tail = `${amount % 100}c`
  const sign = copper < 0 ? '-' : ''
  return `${sign}${gold ? `${gold}g ${silver}s ` : silver ? `${silver}s ` : ''}${tail}`
}

function signedMoney(copper) {
  if (copper === null || copper === undefined) return '—'
  return copper > 0 ? `+${money(copper)}` : money(copper)
}

function outcome(copper) {
  return copper > 0 ? 'gain' : copper < 0 ? 'loss' : 'break-even'
}

/**
 * Times the route itself, straight at the backend, so the number is the operation's own duration
 * rather than the dev server's proxy plus a page render. Sequential on purpose: concurrent samples
 * would measure contention instead.
 */
async function measureRoute() {
  assert.ok(Number.isInteger(samples) && samples > 0 && samples <= 20,
    `GW2_ECTO_LIVE_SAMPLES must be 1..20, got ${process.env.GW2_ECTO_LIVE_SAMPLES}`)

  const durations = []
  let lastBody = null

  for (let sample = 1; sample <= samples; sample += 1) {
    const startedAt = performance.now()
    let response
    try {
      response = await fetch(`${backendOrigin}${ectoPath}`, { headers: { accept: 'application/json' } })
    } catch (cause) {
      throw new Error(`No backend answered ${backendOrigin}${ectoPath} — start it first (${cause.message})`)
    }
    const body = await response.json()
    durations.push(Math.round(performance.now() - startedAt))

    assert.equal(response.status, 200,
      `${ectoPath} answered ${response.status} (${body.error ?? '?'}: ${body.message ?? '?'}) — ` +
        'live prices were unavailable, so no timing is recorded for this run')
    assert.equal(body.resultAvailable, true,
      'The live calculation produced no result; there is nothing to measure or compare')
    lastBody = body
  }

  const ordered = [...durations].sort((left, right) => left - right)
  const measurement = {
    route: ectoPath,
    samples: durations.length,
    durationsMs: durations,
    minMs: ordered[0],
    medianMs: ordered[Math.floor((ordered.length - 1) / 2)],
    maxMs: ordered.at(-1)
  }
  console.log(JSON.stringify(measurement))
  return { measurement, body: lastBody }
}

/** The four rows as the page shows them, in the order the page lists them. */
async function renderedScenarios(page) {
  return page.$$eval('[data-test="ecto-scenario-table"] tbody tr', (rows) =>
    rows.map((row) => {
      const cell = (hook) =>
        row.querySelector(`[data-test="${hook}"]`)?.textContent.replace(/\s+/g, ' ').trim() ?? null
      return {
        acquisition: cell('ecto-scenario-acquisition'),
        sale: cell('ecto-scenario-sale'),
        ectoCost: cell('ecto-scenario-ecto-cost'),
        dustGross: cell('ecto-scenario-dust-gross'),
        dustRecoveredGross: cell('ecto-scenario-dust-recovered-gross'),
        dustRecoveredNet: cell('ecto-scenario-dust-recovered-net'),
        netCost: cell('ecto-scenario-net-cost'),
        profit: row
          .querySelector('[data-test="ecto-scenario-profit"] .money')
          .textContent.replace(/\s+/g, ' ')
          .trim(),
        outcome: cell('ecto-scenario-outcome'),
        luckCost: cell('ecto-scenario-luck-cost')
      }
    })
  )
}

/** What the page must show for that body — each field formatted, none of them computed. */
function expectedScenarios(body) {
  const row = (acquisition, sale, values) => ({
    acquisition,
    sale,
    ectoCost: money(values.ectoAcquisitionCostCopper),
    dustGross: money(values.dustGrossUnitPriceCopper),
    dustRecoveredGross: money(values.expectedGrossRecoveredDustValueCopper),
    dustRecoveredNet: money(values.netValueOfRecoveredDustCopper),
    netCost: money(values.netCostPerEctoCopper),
    profit: signedMoney(values.profitPerEctoCopper),
    outcome: outcome(values.profitPerEctoCopper),
    luckCost: money(values.costPer1000LuckCopper)
  })

  return [
    row('Instant buy', 'Instant sell', body.instantBuyInstantSell),
    row('Instant buy', 'Listing sell', body.instantBuyListingSell),
    row('Buy order', 'Instant sell', body.listingBuyInstantSell),
    row('Buy order', 'Listing sell', body.listingBuyListingSell)
  ]
}

async function textOf(page, selector) {
  return (await page.$eval(selector, (element) => element.textContent)).replace(/\s+/g, ' ').trim()
}

const browser = await chromium.launch({ executablePath: resolveBrowserPath() })
const page = await browser.newPage()
page.setDefaultTimeout(timeout)

function nextCalculation() {
  const pending = page.waitForResponse((response) => new URL(response.url()).pathname === ectoPath)
  // Marked handled at creation, so an abandoned wait can never replace the real failure with a
  // "target closed" rejection from the line that created it.
  pending.catch(() => undefined)
  return pending
}

async function liveAnswer(pending) {
  const response = await pending
  assert.equal(response.status(), 200,
    `The page's own calculation answered ${response.status()} — the browser had no live result to render`)
  const body = await response.json()
  assert.equal(body.resultAvailable, true, 'The page received a calculation with no result')
  return body
}

/** The rendered page against one live response: scenarios, quote panel and stated assumptions. */
async function compareRendered(page, body, what) {
  await page.waitForSelector('[data-test="ecto-scenario-table"]')
  assert.deepEqual(await renderedScenarios(page), expectedScenarios(body),
    `${what}: the rendered scenarios are not the ones this response supplied`)

  assert.deepEqual(
    [
      await textOf(page, '[data-test="ecto-quote-instant-buy"]'),
      await textOf(page, '[data-test="ecto-quote-buy-order"]'),
      await textOf(page, '[data-test="dust-quote-instant-sell"]'),
      await textOf(page, '[data-test="dust-quote-listing-sell"]')
    ],
    [
      money(body.instantBuyInstantSell.ectoAcquisitionCostCopper),
      money(body.listingBuyInstantSell.ectoAcquisitionCostCopper),
      money(body.instantBuyInstantSell.dustGrossUnitPriceCopper),
      money(body.instantBuyListingSell.dustGrossUnitPriceCopper)
    ],
    `${what}: the quote panel does not show this response's quotes`)

  // STORY-DOM-024: the price panel carries gross quotes only, never a price net of the selling fee.
  const prices = await textOf(page, '[data-test="ecto-prices"]')
  assert.ok(!prices.includes('after fee') && !prices.includes('after 15%'),
    `${what}: a displayed market price is labelled net of the fee: ${prices}`)

  const fee = await textOf(page, '[data-test="ecto-assumption-fee"]')
  assert.ok(fee.includes(`${body.assumptions.tradingPostSellFeePercent}%`),
    `${what}: the stated fee is not the backend's own: ${fee}`)
  assert.equal(await textOf(page, '[data-test="ecto-item-id"]'), `#${body.ectoItemId}`)
  assert.equal(await textOf(page, '[data-test="dust-item-id"]'), `#${body.dustItemId}`)

  console.log(JSON.stringify({
    what,
    route: ectoPath,
    status: 200,
    ectoItemId: body.ectoItemId,
    dustItemId: body.dustItemId,
    tradingPostSellFeePercent: body.assumptions.tradingPostSellFeePercent,
    instantBuyInstantSell: {
      ectoAcquisitionCostCopper: body.instantBuyInstantSell.ectoAcquisitionCostCopper,
      profitPerEctoCopper: body.instantBuyInstantSell.profitPerEctoCopper,
      costPer1000LuckCopper: body.instantBuyInstantSell.costPer1000LuckCopper,
      renderedProfit: signedMoney(body.instantBuyInstantSell.profitPerEctoCopper)
    }
  }))
}

try {
  // 1. What the operation costs, measured rather than assumed (TARGET_ARCHITECTURE.md 23).
  const { measurement } = await measureRoute()

  // 2. The page against its own live answer.
  const upstream = []
  page.on('request', (request) => {
    if (/guildwars2\.com/i.test(request.url())) upstream.push(request.url())
  })
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))

  const opening = nextCalculation()
  await page.goto(`${origin}/#/ecto`, { waitUntil: 'domcontentloaded' })
  const opened = await liveAnswer(opening)
  await compareRendered(page, opened, 'opening the page')

  // 3. An explicit reload is a fresh calculation, and the page shows that one.
  const reloading = nextCalculation()
  await page.locator('[data-test="ecto-reload"]').click()
  const reloaded = await liveAnswer(reloading)
  await compareRendered(page, reloaded, 'after an explicit reload')

  // 4. The live prices reached the browser through the backend only.
  assert.deepEqual(upstream, [], `The browser requested ArenaNet directly: ${upstream.slice(0, 3)}`)
  assert.deepEqual(pageErrors, [], `Uncaught page errors: ${pageErrors.join(' | ')}`)

  console.log(
    `\nEctoplasm live smoke PASSED: ${measurement.samples} timed samples ` +
      `(min ${measurement.minMs}ms, median ${measurement.medianMs}ms, max ${measurement.maxMs}ms), ` +
      'opening and reload rendered exactly the live responses received.'
  )
} catch (error) {
  console.error(`\nEctoplasm live smoke FAILED: ${error.stack ?? error}`)
  process.exitCode = 1
} finally {
  await browser.close()
}
