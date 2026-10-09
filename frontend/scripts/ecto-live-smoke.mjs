/**
 * Live check of the browser-owned Ectoplasm Salvage calculation, complementing the
 * controlled-response `smoke:ecto`.
 *
 * The page no longer asks a backend to calculate anything (DOMAIN_SPEC 2.3): it loads item metadata,
 * the Ecto and Crystalline Dust Trading Post quotes and the account's Luck read model, and derives
 * the whole result in the browser. This check is retargeted at that (STORY-WEB-029) and never
 * requests the removed `/api/ecto/salvage` route — it asserts, over the entire run, that the browser
 * did not.
 *
 * Two things it can establish that the controlled check cannot:
 *
 *   1. **Representative runtime** of the three live input reads, timed at the caller against the real
 *      backend, the real item store and the real Trading Post. Nothing is assumed about the duration.
 *   2. **That the page's own arithmetic is right for the live answer it received** — the expected
 *      figures are derived here, from the quotes in the very responses the browser got and from the
 *      yields the page states for the selected tool, using DOMAIN_SPEC 46/47 directly. No expected
 *      value is read out of the page's result, so a changed market moves both sides together while a
 *      wrong calculation does not.
 *
 * The one figure taken from the page rather than derived is the selected tool's coin cost: it is a
 * published per-use price, not a domain rule, and `smoke:ecto` pins it against fixed responses. Here
 * it enters as an input, so what is verified is DOMAIN_SPEC 46's relation between Ecto value consumed,
 * tool cost, net Dust value and effective cost — and section 47's cost per 1,000 Luck — against live
 * quotes. The run fails rather than approximating if that per-use cost is not a whole copper amount.
 *
 * Read-only by construction: three GETs, one local calculation. It writes nothing, touches no
 * account, starts no synchronization and needs no database content beyond the account read model.
 *
 * Prerequisites, both started by hand:
 *   1. the backend    — `./mvnw spring-boot:run` in the repository root
 *   2. the dev server — `npm run dev` in `frontend/` (it proxies `/api` to the backend)
 *
 * Usage:  npm run smoke:ecto:live
 * Environment:
 *   GW2_FRONTEND_URL        page origin           (default http://127.0.0.1:5173)
 *   GW2_BACKEND_ORIGIN      backend for the timing samples (default http://127.0.0.1:8080)
 *   GW2_ECTO_LIVE_SAMPLES   timing samples        (default 5)
 *   GW2_BROWSER_PATH, GW2_SMOKE_TIMEOUT_MS   as in the other checks
 *
 * Exits 0 when every step passed, 1 otherwise. A backend that is unreachable, answers an error or has
 * no usable quotes fails the run and says which — it is never reported as a pass.
 */
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

const origin = process.env.GW2_FRONTEND_URL ?? 'http://127.0.0.1:5173'
const backendOrigin = process.env.GW2_BACKEND_ORIGIN ?? 'http://127.0.0.1:8080'
const samples = Number(process.env.GW2_ECTO_LIVE_SAMPLES ?? 5)
const timeout = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 60_000)

const ECTO_ID = 19721
const DUST_ID = 24277
/** Every item the page shows a picture or a name for: the two traded ones and each salvage tool. */
const METADATA_IDS = [ECTO_ID, DUST_ID, 44602, 23041, 89409, 67027, 19986]

const METADATA_PATH = '/api/items/metadata'
const PRICES_PATH = '/api/items/prices'
const LUCK_PATH = '/api/account/luck'
const REMOVED_CALCULATION_PATH = '/api/ecto/salvage'
const INPUT_PATHS = [METADATA_PATH, PRICES_PATH, LUCK_PATH]

/** The Trading Post keeps 15% of a sale (DOMAIN_SPEC 25); recovered Dust is sold, so this applies. */
const TP_SELL_MULTIPLIER = 0.85

/** The read-only route the shared client polls while the backend reports the account data stale. */
const TASK_STATUS_ROUTE = /^\/api\/sync\/tasks\/[0-9a-fA-F-]+$/
/** Item images come from this application's own delivery route and nowhere else. */
const ICON_ROUTE = /^\/api\/items\/\d+\/icon\/[0-9a-f]{64}\.(png|jpg)$/

const steps = []

function record(name, detail) {
  steps.push(name)
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

/** Formatting only, mirroring `formatCopper`: the amount is always derived, never parsed back. */
function money(copper) {
  const sign = copper < 0 ? '-' : ''
  const amount = Math.abs(copper)
  const gold = Math.floor(amount / 10_000)
  const silver = Math.floor((amount % 10_000) / 100)
  const remainder = amount % 100
  if (gold > 0) return `${sign}${gold}g ${silver}s ${remainder}c`
  if (silver > 0) return `${sign}${silver}s ${remainder}c`
  return `${sign}${remainder}c`
}

/** The inverse, so a figure the page rendered can be compared as a number. */
function copperOf(text, what) {
  const match = /^([+-]?)(?:(\d+)g )?(?:(\d+)s )?(\d+)c$/.exec(text ?? '')
  if (match === null) throw new Error(`${what}: "${text}" is not a copper amount this page formats`)
  const [, sign, gold = '0', silver = '0', remainder] = match
  const amount = Number(gold) * 10_000 + Number(silver) * 100 + Number(remainder)
  return sign === '-' ? -amount : amount
}

/**
 * Waits out a refresh the backend already had running, so the samples below are taken against a
 * backend that can answer. This is the same recovery the browser performs for a stale read
 * (`frontend/src/api/http.ts`); doing it first keeps it out of the measurement.
 */
async function awaitAccountReadModel() {
  const deadline = Date.now() + 5 * 60_000
  while (Date.now() < deadline) {
    let response
    try {
      response = await fetch(`${backendOrigin}${LUCK_PATH}`, { headers: { accept: 'application/json' } })
    } catch (cause) {
      throw new Error(`No backend answered ${backendOrigin}${LUCK_PATH} — start it first (${cause.message})`)
    }
    const body = await response.json()
    if (response.status === 200) return
    if (body.error !== 'ACCOUNT_DATA_STALE') {
      throw new Error(
        `${LUCK_PATH} answered ${response.status} (${body.error ?? '?'}: ${body.message ?? '?'}) — ` +
          'the account read model is unavailable, so there is nothing live to compare'
      )
    }
    console.log(`  ..   ${LUCK_PATH} reports ${body.error} (${(body.staleSources ?? []).join(', ')}); waiting`)
    await new Promise((resolve) => setTimeout(resolve, 1_000))
  }
  throw new Error(`${LUCK_PATH} still reported stale account data after 5 minutes`)
}

/**
 * Times each live input read straight at the backend, so the numbers are the operations' own
 * durations rather than the dev server's proxy plus a page render. Sequential on purpose: concurrent
 * samples would measure contention instead.
 */
async function measureInputs() {
  assert.ok(Number.isInteger(samples) && samples > 0 && samples <= 20,
    `GW2_ECTO_LIVE_SAMPLES must be 1..20, got ${process.env.GW2_ECTO_LIVE_SAMPLES}`)

  const requests = {
    [METADATA_PATH]: `${METADATA_PATH}?ids=${encodeURIComponent(METADATA_IDS.join(','))}`,
    [PRICES_PATH]: `${PRICES_PATH}?ids=${ECTO_ID},${DUST_ID}`,
    [LUCK_PATH]: LUCK_PATH
  }
  const measurements = []

  for (const [path, query] of Object.entries(requests)) {
    const durations = []
    for (let sample = 1; sample <= samples; sample += 1) {
      const startedAt = performance.now()
      const response = await fetch(`${backendOrigin}${query}`, { headers: { accept: 'application/json' } })
      const body = await response.json()
      durations.push(Math.round(performance.now() - startedAt))
      assert.equal(response.status, 200,
        `${path} answered ${response.status} (${body.error ?? '?'}: ${body.message ?? '?'}) — ` +
          'a live input was unavailable, so no timing is recorded for this run')
    }
    const ordered = [...durations].sort((left, right) => left - right)
    measurements.push({
      route: path,
      samples: durations.length,
      durationsMs: durations,
      minMs: ordered[0],
      medianMs: ordered[Math.floor((ordered.length - 1) / 2)],
      maxMs: ordered.at(-1)
    })
  }

  console.log(JSON.stringify(measurements))
  return measurements
}

/** The expected result for one set of live inputs, straight from DOMAIN_SPEC 46 and 47. */
function expectedResult({ count, dustPerEcto, luckPerEcto, toolCostPerUseCopper, ectoQuote, dustQuote }) {
  const expectedLuck = count * luckPerEcto
  const expectedDust = count * dustPerEcto
  const ectoValueConsumed = count * ectoQuote
  const salvageToolCost = Math.round(count * toolCostPerUseCopper)
  // Expected gross recovered value scales the unchanged yield by the gross quote; the fee is deducted
  // exactly once, from that value, and never from the acquisition cost.
  const dustValueAfterFees = Math.floor(expectedDust * dustQuote * TP_SELL_MULTIPLIER)
  const effectiveCost = ectoValueConsumed + salvageToolCost - dustValueAfterFees
  return {
    expectedLuck,
    expectedDust,
    ectoValueConsumed,
    salvageToolCost,
    dustValueAfterFees,
    effectiveCost,
    costPer1000Luck: Math.round((effectiveCost / expectedLuck) * 1_000)
  }
}

/** Every figure in the calculated region, keyed by its own label and by which group it sits in. */
async function renderedFigures(page) {
  return page.$$eval(
    '[data-test="ecto-calculation"] .calculation-row, [data-test="ecto-calculation"] .calculation-total',
    (rows) =>
      rows.map((row) => ({
        label: row.querySelector('span')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
        value: row.querySelector('strong')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
        // "Luck received" is stated twice: once among the yields, once in the per-1,000-Luck summary.
        summary: row.parentElement?.classList.contains('calculation-luck') ?? false
      }))
  )
}

function figure(rows, label, { summary = false } = {}) {
  const matches = rows.filter((row) => row.label === label && row.summary === summary)
  assert.equal(matches.length, 1,
    `Expected exactly one ${summary ? 'summary ' : ''}"${label}" figure, found ${matches.length} ` +
      `among ${JSON.stringify(rows.map((row) => row.label))}`)
  return matches[0].value
}

/** The yields the page states for the selected tool — its own displayed assumptions, not constants. */
async function selectedYields(page) {
  const cells = await page.$eval('.salvage-row.selected', (row) =>
    [...row.querySelectorAll('strong.numeric')].map((cell) => cell.textContent.trim())
  )
  assert.equal(cells.length, 3, `The selected salvage row states ${cells.length} figures, expected 3`)
  const [rareMaterialsChance, dust, luck] = cells
  const dustPerEcto = Number(dust)
  const luckPerEcto = Number(luck)
  assert.ok(dustPerEcto > 0 && luckPerEcto > 0,
    `The selected tool states unusable yields: ${JSON.stringify(cells)}`)
  return { rareMaterialsChance, dustPerEcto, luckPerEcto }
}

/** The page's own locale formatting for a count, so this never depends on Node's default locale. */
function numberFormatter(page) {
  return (value) => page.evaluate((amount) => Math.round(amount).toLocaleString(), value)
}

function quotesFor(pricesBody, { buyMode, sellMode }) {
  const quote = (itemId) => {
    const found = pricesBody.prices.find((price) => price.itemId === itemId)
    assert.ok(found !== undefined, `The live price response carries no entry for item ${itemId}`)
    return found
  }
  const ecto = quote(ECTO_ID)
  const dust = quote(DUST_ID)
  const ectoQuote = buyMode === 'instant' ? ecto.sellUnitCopper : ecto.buyUnitCopper
  const dustQuote = sellMode === 'instant' ? dust.buyUnitCopper : dust.sellUnitCopper
  assert.ok(ectoQuote !== null && dustQuote !== null,
    `The Trading Post has no usable ${buyMode} Ecto quote or ${sellMode} Dust quote right now ` +
      `(${JSON.stringify({ ecto, dust })}), so there is no live result to compare`)
  return { ectoQuote, dustQuote }
}

/**
 * The rendered result against the live quotes the browser itself received.
 *
 * The selected tool's per-use coin cost is the one input taken from the page; everything else is
 * derived here, and the total is checked as DOMAIN_SPEC 46's relation between the four parts.
 */
async function compareRendered(page, { what, pricesBody, buyMode, sellMode }) {
  await page.waitForSelector('[data-test="ecto-result"]', { timeout })
  const formatNumber = numberFormatter(page)
  const rows = await renderedFigures(page)
  const { dustPerEcto, luckPerEcto } = await selectedYields(page)
  const { ectoQuote, dustQuote } = quotesFor(pricesBody, { buyMode, sellMode })

  const count = Number(await page.locator('#ecto-count').inputValue())
  assert.ok(Number.isInteger(count) && count > 0, `${what}: the page holds an unusable Ecto count: ${count}`)

  const renderedToolCost = -copperOf(figure(rows, 'Salvage tool cost'), `${what}: salvage tool cost`)
  const toolCostPerUseCopper = renderedToolCost / count
  assert.ok(Number.isInteger(toolCostPerUseCopper),
    `${what}: the selected tool's stated cost of ${renderedToolCost}c over ${count} uses is not a ` +
      'whole copper amount per use, so this check would compare approximations')

  const expected = expectedResult({
    count, dustPerEcto, luckPerEcto, toolCostPerUseCopper, ectoQuote, dustQuote
  })

  assert.equal(figure(rows, 'Luck received'), `+${await formatNumber(expected.expectedLuck)}`,
    `${what}: the expected Luck is not ${count} × the stated ${luckPerEcto} per Ecto`)
  assert.equal(figure(rows, 'Dust received'), `+${await formatNumber(expected.expectedDust)}`,
    `${what}: the expected Dust is not ${count} × the stated ${dustPerEcto} per Ecto`)
  assert.equal(figure(rows, 'Luck received', { summary: true }), await formatNumber(expected.expectedLuck),
    `${what}: the Luck the per-1,000 summary divides by is not the Luck received`)

  assert.equal(figure(rows, 'Ecto value consumed'), `-${money(expected.ectoValueConsumed)}`,
    `${what}: the consumed Ecto value is not ${count} × the live ${buyMode} quote of ${ectoQuote}c`)
  assert.equal(figure(rows, 'Salvage tool cost'), `-${money(expected.salvageToolCost)}`,
    `${what}: the tool cost does not scale with the Ecto count`)
  assert.equal(figure(rows, 'Dust value after TP fees'), `+${money(expected.dustValueAfterFees)}`,
    `${what}: the net Dust value is not the expected yield at the live ${sellMode} quote of ` +
      `${dustQuote}c with the selling fee deducted once`)
  assert.equal(figure(rows, 'Effective cost'), money(expected.effectiveCost),
    `${what}: the effective cost is not consumed Ecto value plus tool cost minus net Dust value`)
  assert.equal(figure(rows, 'Cost per 1,000 Luck', { summary: true }), money(expected.costPer1000Luck),
    `${what}: the cost per 1,000 Luck is not the effective cost scaled to 1,000 Luck`)

  console.log(JSON.stringify({
    what,
    count,
    ectoQuoteCopper: ectoQuote,
    dustQuoteCopper: dustQuote,
    dustPerEcto,
    luckPerEcto,
    toolCostPerUseCopper,
    effectiveCostCopper: expected.effectiveCost,
    renderedEffectiveCost: money(expected.effectiveCost),
    costPer1000LuckCopper: expected.costPer1000Luck
  }))
  return { count, ectoQuote, dustQuote, dustPerEcto, luckPerEcto, toolCostPerUseCopper }
}

/** The Magic Find targets against the live Luck read model and the same selected inputs. */
async function compareTargets(page, luckBody, inputs) {
  const formatNumber = numberFormatter(page)
  const expectedTargets = []
  if (luckBody.nextMagicFindPercent !== null && luckBody.luckRemainingToNextPercent > 0) {
    expectedTargets.push({
      label: 'Next +1%',
      magicFindPercent: luckBody.nextMagicFindPercent,
      luckRemaining: luckBody.luckRemainingToNextPercent
    })
  }
  for (const target of luckBody.targets) {
    expectedTargets.push({
      label: target.kind === 'PLUS_5' ? '+5%' : target.kind === 'PLUS_10' ? '+10%' : '300% cap',
      magicFindPercent: target.magicFindPercent,
      luckRemaining: target.luckRemaining
    })
  }
  assert.ok(expectedTargets.length > 0,
    'The live Luck response offers no target at all, so no target row could be compared')

  const expected = []
  for (const target of expectedTargets) {
    const ectosRequired =
      target.luckRemaining <= 0 ? 0 : Math.ceil(target.luckRemaining / inputs.luckPerEcto)
    const ectoCost = ectosRequired * inputs.ectoQuote
    const dustNet = Math.floor(
      ectosRequired * inputs.dustPerEcto * inputs.dustQuote * TP_SELL_MULTIPLIER
    )
    const salvageCost = Math.round(ectosRequired * inputs.toolCostPerUseCopper)
    expected.push({
      label: target.label,
      percent: `→ ${target.magicFindPercent}% MF`,
      luckNeeded: await formatNumber(target.luckRemaining),
      ectos: await formatNumber(ectosRequired),
      ectoValue: money(ectoCost),
      effectiveCost: money(ectoCost + salvageCost - dustNet)
    })
  }

  const rendered = await page.$$eval('.target-row', (rows) =>
    rows.map((row) => {
      const cells = [...row.children]
      const text = (element) => element?.textContent.replace(/\s+/g, ' ').trim() ?? null
      return {
        label: text(cells[0]?.querySelector('strong')),
        percent: text(cells[0]?.querySelector('.meta')),
        luckNeeded: text(cells[1]),
        ectos: text(cells[2]),
        ectoValue: text(cells[3]),
        effectiveCost: text(cells[4]?.querySelector('strong'))
      }
    })
  )

  assert.deepEqual(rendered, expected,
    'The Magic Find target rows are not the live Luck response at the selected tool and quotes')

  const summary = await page.$$eval('.account-summary > div strong', (values) =>
    values.map((value) => value.textContent.replace(/\s+/g, ' ').trim())
  )
  assert.deepEqual(summary, [
    await formatNumber(luckBody.consumedLuck),
    `${luckBody.currentLuckMagicFindPercent}%`,
    luckBody.nextMagicFindPercent === null ? 'Maximum' : `${luckBody.nextMagicFindPercent}%`
  ], "The account's own Luck summary is not the live read model")

  return expected.length
}

const browser = await chromium.launch({ executablePath: resolveBrowserPath() })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.setDefaultTimeout(timeout)

/** Every path the browser asked for, so the removed route's absence is asserted over the whole run. */
const requestedPaths = []
const upstream = []
const pageErrors = []
page.on('request', (request) => {
  requestedPaths.push(new URL(request.url()).pathname)
  if (/guildwars2\.com/i.test(request.url())) upstream.push(request.url())
})
page.on('pageerror', (error) => pageErrors.push(String(error)))

function pathCount(path) {
  return requestedPaths.filter((requested) => requested === path).length
}

/**
 * The next answer on one input route, read as the page itself received it. Marked handled at
 * creation, so an abandoned wait can never replace the real failure with a "target closed" rejection
 * from the line that created it.
 */
function nextAnswer(path) {
  const pending = page.waitForResponse((response) => new URL(response.url()).pathname === path)
  pending.catch(() => undefined)
  return pending
}

async function liveBody(pending, path) {
  let response = await pending
  for (let attempt = 0; attempt < 3 && response.status() !== 200; attempt += 1) {
    const stale = await response.json().catch(() => ({}))
    assert.equal(stale.error, 'ACCOUNT_DATA_STALE',
      `${path} answered ${response.status()} (${stale.error ?? '?'}: ${stale.message ?? '?'}) — ` +
        'the browser had no live input to calculate from')
    // The shared client waits out the refresh and repeats the read; this follows it to that answer.
    response = await nextAnswer(path)
  }
  assert.equal(response.status(), 200,
    `${path} never answered 200, so the browser had no live input to calculate from`)
  return response.json()
}

try {
  // 1. What the live inputs cost, measured rather than assumed (TARGET_ARCHITECTURE.md 23).
  await awaitAccountReadModel()
  const measurements = await measureInputs()
  record(
    'the three live input reads measured at the backend',
    measurements
      .map((one) => `${one.route} median ${one.medianMs}ms (min ${one.minMs}, max ${one.maxMs})`)
      .join('; ')
  )

  // 2. The page opened against its own live inputs, with no calculation request of any kind.
  const opening = INPUT_PATHS.map((path) => [path, nextAnswer(path)])
  await page.goto(`${origin}/#/ecto`, { waitUntil: 'domcontentloaded' })
  const answers = new Map()
  for (const [path, pending] of opening) answers.set(path, await liveBody(pending, path))
  await page.waitForSelector('[data-test="ecto-result"]')
  await page.waitForSelector('[data-test="ecto-account-luck"]')

  const metadataBody = answers.get(METADATA_PATH)
  let pricesBody = answers.get(PRICES_PATH)
  const luckBody = answers.get(LUCK_PATH)

  assert.equal(pathCount(REMOVED_CALCULATION_PATH), 0,
    `The browser requested the removed backend calculation route ${REMOVED_CALCULATION_PATH}`)
  const unexpected = requestedPaths.filter(
    (path) =>
      path.startsWith('/api/') &&
      !INPUT_PATHS.includes(path) &&
      !ICON_ROUTE.test(path) &&
      !TASK_STATUS_ROUTE.test(path)
  )
  assert.deepEqual([...new Set(unexpected)], [],
    'The Ecto page asked the backend for something other than its three inputs and its item images')
  for (const path of INPUT_PATHS) {
    assert.ok(pathCount(path) >= 1, `Opening the page never read ${path}`)
  }
  assert.deepEqual(
    metadataBody.items.map((item) => item.itemId).sort((left, right) => left - right),
    [...METADATA_IDS].sort((left, right) => left - right),
    'The live metadata answer does not cover the two traded items and every salvage tool'
  )
  for (const itemId of [ECTO_ID, DUST_ID]) {
    const name = metadataBody.items.find((item) => item.itemId === itemId)?.name
    assert.ok(typeof name === 'string' && name.length > 0, `Item ${itemId} arrived without a name`)
    assert.ok((await page.locator('.tp-block').allInnerTexts()).some((text) => text.includes(name)),
      `The page does not show the live name of item ${itemId} ("${name}")`)
  }
  record(
    'opened on its three live inputs, with no request to the removed calculation route',
    `${METADATA_PATH} ×${pathCount(METADATA_PATH)}, ${PRICES_PATH} ×${pathCount(PRICES_PATH)}, ` +
      `${LUCK_PATH} ×${pathCount(LUCK_PATH)}; ecto-result and ecto-account-luck both rendered`
  )

  // 3. The rendered result, derived here from those very quotes (DOMAIN_SPEC 46, 47).
  let inputs = await compareRendered(page, {
    what: 'opening the page',
    pricesBody,
    buyMode: 'instant',
    sellMode: 'instant'
  })
  record('the opened result is this response\'s own calculation', 'instant buy / instant sell')

  // 4. The Magic Find targets, against the live Luck read model at the same inputs.
  const targetRows = await compareTargets(page, luckBody, inputs)
  record('the Magic Find targets are the live Luck read model', `${targetRows} target rows compared`)

  // 5. Amount, tool and both price modes recalculate in the browser (DOMAIN_SPEC 2.3): a changed
  //    selection produces a changed result without asking the backend for anything at all.
  const callsBeforeControls = requestedPaths.filter((path) => path.startsWith('/api/')).length
  const effectiveBefore = figure(await renderedFigures(page), 'Effective cost')
  await page.locator('#ecto-count').fill('250')
  await page.getByRole('button', { name: /Basic \/ Copper-Fed/ }).click()
  await page.locator('.tp-block').first().getByRole('button', { name: /Buy order/ }).click()
  await page.locator('.tp-block').last().getByRole('button', { name: /Listing sell/ }).click()

  inputs = await compareRendered(page, {
    what: 'after changing the amount, the tool and both price modes',
    pricesBody,
    buyMode: 'order',
    sellMode: 'listing'
  })
  const effectiveAfter = figure(await renderedFigures(page), 'Effective cost')
  assert.notEqual(effectiveAfter, effectiveBefore,
    `The result did not change at all for a different amount, tool and both price modes: ${effectiveAfter}`)
  assert.equal(requestedPaths.filter((path) => path.startsWith('/api/')).length, callsBeforeControls,
    'A local control asked the backend to recalculate')
  await compareTargets(page, luckBody, inputs)
  record(
    'every control recalculates in the browser',
    `effective cost ${effectiveBefore} → ${effectiveAfter}, ${callsBeforeControls} backend calls before ` +
      'and after'
  )

  // 6. A manual refresh rereads the two Trading Post prices only, and the page shows that answer.
  const metadataReads = pathCount(METADATA_PATH)
  const luckReads = pathCount(LUCK_PATH)
  const refreshing = nextAnswer(PRICES_PATH)
  await page.getByRole('button', { name: 'Refresh TP prices' }).click()
  pricesBody = await liveBody(refreshing, PRICES_PATH)
  await page.waitForFunction(
    (expected) =>
      [...document.querySelectorAll('[data-test="ecto-calculation"] .calculation-total strong')]
        .some((total) => total.textContent.replace(/\s+/g, ' ').trim() === expected),
    money(
      expectedResult({
        count: 250,
        dustPerEcto: inputs.dustPerEcto,
        luckPerEcto: inputs.luckPerEcto,
        toolCostPerUseCopper: inputs.toolCostPerUseCopper,
        ...quotesFor(pricesBody, { buyMode: 'order', sellMode: 'listing' })
      }).effectiveCost
    ),
    { timeout }
  )
  assert.equal(pathCount(METADATA_PATH), metadataReads, 'The price refresh reread item metadata')
  assert.equal(pathCount(LUCK_PATH), luckReads, 'The price refresh reread account Luck')
  inputs = await compareRendered(page, {
    what: 'after a manual Trading Post refresh',
    pricesBody,
    buyMode: 'order',
    sellMode: 'listing'
  })
  record(
    'a manual refresh rereads only the two prices and the result follows them',
    `${PRICES_PATH} ×${pathCount(PRICES_PATH)}, ${METADATA_PATH} ×${metadataReads}, ` +
      `${LUCK_PATH} ×${luckReads}`
  )

  // 7. The live prices reached the browser through this application only.
  assert.deepEqual(upstream, [], `The browser requested ArenaNet directly: ${upstream.slice(0, 3)}`)
  assert.deepEqual(pageErrors, [], `Uncaught page errors: ${pageErrors.join(' | ')}`)
  assert.equal(pathCount(REMOVED_CALCULATION_PATH), 0,
    `The browser requested the removed backend calculation route ${REMOVED_CALCULATION_PATH}`)
  record(
    'nothing left this origin and nothing asked a backend to calculate',
    `${requestedPaths.length} requests, 0 to ${REMOVED_CALCULATION_PATH}, 0 to ArenaNet`
  )

  console.log(`\nEctoplasm live smoke PASSED (${steps.length} steps).`)
} catch (error) {
  console.error(`\nEctoplasm live smoke FAILED after ${steps.length} step(s): ${error.stack ?? error}`)
  process.exitCode = 1
} finally {
  await browser.close()
}
