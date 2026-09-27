/**
 * Real-browser check of the Ectoplasm Salvage page: its addressable destination, the four scenarios
 * rendered exactly as supplied, the explicit reload, keyboard operation, the narrow layout and the
 * loading/failure/unavailable states (STORY-WEB-013, `DOMAIN_SPEC.md` 2.3, 45-47,
 * `FRONTEND_UX_GUIDELINES.md` 2, 3, 5, 6, 7).
 *
 * Runs the built frontend against a *controlled* API boundary (`scripts/stubOrigin.mjs`): every
 * answer comes from this process, so no backend, no database and no GW2 Trading Post lookup is
 * involved. It therefore evidences structure, interaction, layout and what the page requests — never
 * real prices, never a domain value, and never page-load performance
 * (`TARGET_ARCHITECTURE.md` 33). `smoke:ecto:live` is the comparison against an actual backend.
 *
 * The served numbers are deliberately incoherent — no field is the arithmetic consequence of any
 * other — so a page that recomputed profit, net cost, the recovered Dust value or the Luck cost
 * would disagree with this script instead of agreeing with itself.
 *
 * Usage:  npm run build && npm run smoke:ecto
 * Environment:
 *   GW2_ECTO_SMOKE_PORT   port for the stub origin  (default 5182)
 *   GW2_BROWSER_PATH      browser executable        (default: the first installed Chrome/Edge)
 *   GW2_SMOKE_TIMEOUT_MS  per-step wait             (default 30000)
 *
 * Exits 0 when every step passed, 1 otherwise.
 */
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startStubOrigin } from './stubOrigin.mjs'

const PORT = Number(process.env.GW2_ECTO_SMOKE_PORT ?? 5182)
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
const DIST_DIR = fileURLToPath(new URL('../dist/', import.meta.url))

const WIDE = { width: 1440, height: 900 }
const NARROW = { width: 360, height: 800 }

const ECTO_ROUTE = '/api/ecto/salvage'

const steps = []

function record(name, detail) {
  steps.push({ name, detail })
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

function check(condition, message) {
  if (!condition) throw new Error(message)
}

function assertEqual(actual, expected, what) {
  const left = JSON.stringify(actual)
  const right = JSON.stringify(expected)
  if (left !== right) throw new Error(`${what}\n  rendered: ${left}\n  supplied: ${right}`)
}

/** Formatting only: the expected copper value is always the supplied one, never derived from it. */
function money(copper) {
  const amount = Math.abs(copper)
  const gold = Math.floor(amount / 10_000)
  const silver = Math.floor((amount % 10_000) / 100)
  const tail = `${amount % 100}c`
  return `${copper < 0 ? '-' : ''}${gold ? `${gold}g ${silver}s ` : silver ? `${silver}s ` : ''}${tail}`
}

function signed(copper) {
  return copper > 0 ? `+${money(copper)}` : money(copper)
}

function scenarios(offset) {
  return {
    instantBuyInstantSell: {
      ectoAcquisitionCostCopper: 1111 + offset,
      dustGrossUnitPriceCopper: 2222 + offset,
      dustNetUnitPriceCopper: 3333 + offset,
      netValueOfRecoveredDustCopper: 4444 + offset,
      netCostPerEctoCopper: 5555 + offset,
      profitPerEctoCopper: -6666 - offset,
      costPer1000LuckCopper: 7777 + offset
    },
    instantBuyListingSell: {
      ectoAcquisitionCostCopper: 1212 + offset,
      dustGrossUnitPriceCopper: 2323 + offset,
      dustNetUnitPriceCopper: 3434 + offset,
      netValueOfRecoveredDustCopper: 4545 + offset,
      netCostPerEctoCopper: -5656 - offset,
      profitPerEctoCopper: 6767 + offset,
      costPer1000LuckCopper: -7878 - offset
    },
    listingBuyInstantSell: {
      ectoAcquisitionCostCopper: 1313 + offset,
      dustGrossUnitPriceCopper: 2424 + offset,
      dustNetUnitPriceCopper: 0,
      netValueOfRecoveredDustCopper: 4646 + offset,
      netCostPerEctoCopper: 5757 + offset,
      profitPerEctoCopper: 0,
      costPer1000LuckCopper: 7979 + offset
    },
    listingBuyListingSell: {
      ectoAcquisitionCostCopper: 1414 + offset,
      dustGrossUnitPriceCopper: 2525 + offset,
      dustNetUnitPriceCopper: 3636 + offset,
      netValueOfRecoveredDustCopper: 4747 + offset,
      netCostPerEctoCopper: 5858 + offset,
      profitPerEctoCopper: 6969 + offset,
      costPer1000LuckCopper: 8080 + offset
    }
  }
}

function salvageResponse(offset) {
  return {
    resultAvailable: true,
    ectoItemId: 19_721,
    dustItemId: 24_277,
    // A fee percentage that is deliberately not the project's 15: a page stating it from its own
    // knowledge rather than from this answer would print the wrong number.
    assumptions: {
      expectedLuckPerEcto: 20,
      expectedDustPerEcto: 0.75,
      ectosPer1000Luck: 50,
      tradingPostSellFeePercent: 12
    },
    ...scenarios(offset)
  }
}

const UNAVAILABLE = {
  resultAvailable: false,
  ectoItemId: 19_721,
  dustItemId: 24_277,
  assumptions: {
    expectedLuckPerEcto: 20,
    expectedDustPerEcto: 0.75,
    ectosPer1000Luck: 50,
    tradingPostSellFeePercent: 12
  },
  instantBuyInstantSell: null,
  instantBuyListingSell: null,
  listingBuyInstantSell: null,
  listingBuyListingSell: null
}

/** What the next calculation is answered with; each step sets what it needs. */
const stub = { mode: 'ok', calls: 0, delayMs: 0 }

function answerApi({ url, sendJson }) {
  if (url.pathname !== ECTO_ROUTE) return sendJson(404, { error: 'NOT_FOUND', message: url.pathname })

  stub.calls += 1
  const call = stub.calls
  const answer = () => {
    if (stub.mode === 'failure') {
      return sendJson(502, {
        error: 'PRICE_SOURCE_UNAVAILABLE',
        message: 'Live Trading Post prices for the Ectoplasm calculation are currently unavailable'
      })
    }
    if (stub.mode === 'unavailable') return sendJson(200, UNAVAILABLE)
    // A different price snapshot per call, so a rendered reload is visibly the new answer.
    return sendJson(200, salvageResponse((call - 1) * 10_000))
  }

  if (stub.delayMs > 0) setTimeout(answer, stub.delayMs)
  else answer()
}

async function textOf(page, selector) {
  return (await page.$eval(selector, (element) => element.textContent)).replace(/\s+/g, ' ').trim()
}

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
        dustRecovered: cell('ecto-scenario-dust-recovered'),
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

function expectedScenarios(body) {
  const row = (acquisition, sale, values) => ({
    acquisition,
    sale,
    ectoCost: money(values.ectoAcquisitionCostCopper),
    dustGross: money(values.dustGrossUnitPriceCopper),
    dustRecovered: money(values.netValueOfRecoveredDustCopper),
    netCost: money(values.netCostPerEctoCopper),
    profit: signed(values.profitPerEctoCopper),
    outcome:
      values.profitPerEctoCopper > 0 ? 'gain' : values.profitPerEctoCopper < 0 ? 'loss' : 'break-even',
    luckCost: money(values.costPer1000LuckCopper)
  })

  return [
    row('Instant buy', 'Instant sell', body.instantBuyInstantSell),
    row('Instant buy', 'Listing sell', body.instantBuyListingSell),
    row('Buy order', 'Instant sell', body.listingBuyInstantSell),
    row('Buy order', 'Listing sell', body.listingBuyListingSell)
  ]
}

async function pageOverflow(page) {
  return page.evaluate(() => {
    const root = document.documentElement
    const past = Array.from(document.querySelectorAll('body *'))
      .filter((element) => element.getBoundingClientRect().right > root.clientWidth + 1)
      .slice(0, 5)
      .map((element) => `${element.tagName.toLowerCase()}[${element.getAttribute('data-test') ?? ''}]`)
    return { scrollWidth: root.scrollWidth, clientWidth: root.clientWidth, past }
  })
}

async function openEcto(page, origin, viewport) {
  await page.setViewportSize(viewport)
  await page.goto(`${origin}/#/ecto`, { waitUntil: 'domcontentloaded', timeout: TIMEOUT_MS })
  // A fresh load, so focus, scroll position and component state are the ones a real opening has.
  await page.reload({ waitUntil: 'domcontentloaded', timeout: TIMEOUT_MS })
  await page.waitForSelector(
    '[data-test="ecto-scenario-table"], [data-test="ecto-error"], [data-test="ecto-unavailable"]',
    { timeout: TIMEOUT_MS }
  )
}

async function run() {
  check(existsSync(`${DIST_DIR}index.html`), 'frontend/dist is missing — run `npm run build` first.')

  const browserPath = resolveBrowserPath()
  const origin = await startStubOrigin({ port: PORT, distDir: DIST_DIR, answerApi })
  console.log(`Browser : ${browserPath}`)
  console.log(`Page    : ${origin.origin} (stub backend in this process)\n`)

  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage({ viewport: WIDE })
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))
  const browserRequests = []
  page.on('request', (request) => browserRequests.push(request.url()))

  try {
    // 1. The page really came from this process, not from something else on the port.
    await page.goto(origin.origin, { waitUntil: 'domcontentloaded', timeout: TIMEOUT_MS })
    await page.waitForSelector('[data-test="screen-nav"]', { timeout: TIMEOUT_MS })
    check(
      origin.servedCount() > 0,
      `The page at ${origin.origin} was not served by this script — stop whatever else is listening ` +
        'on that port. Nothing was checked.'
    )
    check(
      origin.requestsTo(ECTO_ROUTE).length === 0,
      'The Ectoplasm calculation ran before its destination was opened.'
    )
    record(
      'page served by this script, nothing calculated before opening it',
      `${origin.servedCount()} requests answered so far`
    )

    // 2. An addressable destination of its own.
    await page.click('[data-test="nav-ecto"]')
    await page.waitForSelector('[data-test="ecto-scenario-table"]', { timeout: TIMEOUT_MS })
    check(new URL(page.url()).hash === '#/ecto', `Not addressable at its own URL: ${page.url()}`)
    check(
      (await page.title()) === 'Ectoplasm Salvage · GW2 Crafting Tool',
      `The document title does not name the page: ${await page.title()}`
    )
    check(
      (await textOf(page, '[data-page-heading]')) === 'Ectoplasm Salvage',
      `The page heading is not the destination's own name: ${await textOf(page, '[data-page-heading]')}`
    )
    const navCurrent = await page.$eval('[aria-current="page"]', (element) =>
      element.getAttribute('data-test')
    )
    check(navCurrent === 'nav-ecto', `The navigation does not mark the page current: ${navCurrent}`)
    // Opening this destination costs exactly one calculation on its own route. The default screen
    // the shell started on has already called its own crafting routes, which this stub answers with
    // a 404 and which say nothing about this page; what must not happen is a second Ectoplasm
    // calculation, a synchronization or a price refresh.
    assertEqual(
      origin.requestsTo(ECTO_ROUTE).map((request) => `${request.method} ${request.path}`),
      [`GET ${ECTO_ROUTE}`],
      'Opening the page did not request its calculation exactly once.'
    )
    assertEqual(
      origin
        .requestsTo('/api/')
        .filter((request) => /^\/api\/(sync|prices)/.test(request.path))
        .map((request) => request.path),
      [],
      'Opening the page triggered a synchronization or a price refresh.'
    )
    record('addressable destination that calculates once when opened', `#/ecto, GET ${ECTO_ROUTE}`)

    // 3. All four scenarios, exactly as served.
    const firstAnswer = salvageResponse(0)
    assertEqual(
      await renderedScenarios(page),
      expectedScenarios(firstAnswer),
      'The rendered scenarios do not match the supplied ones.'
    )
    record(
      'four scenarios rendered as supplied',
      'instant/order buying × instant/listing selling, no value derived from another'
    )

    // 4. Gross quotes and fee-inclusive results labelled apart, with the served fee percentage.
    const basis = await textOf(page, '[data-test="ecto-assumption-fee"]')
    check(basis.includes('12%'), `The stated fee is not the one the backend supplied: ${basis}`)
    check(
      basis.includes('gross') && basis.includes('deducted'),
      `The fee note does not separate gross quotes from fee-inclusive results: ${basis}`
    )
    const yields = await textOf(page, '[data-test="ecto-assumption-yield"]')
    check(
      yields.includes('not a guaranteed drop'),
      `The yields are not labelled as expected values: ${yields}`
    )
    assertEqual(
      [
        await textOf(page, '[data-test="ecto-quote-instant-buy"]'),
        await textOf(page, '[data-test="ecto-quote-buy-order"]'),
        await textOf(page, '[data-test="dust-quote-instant-sell"]'),
        await textOf(page, '[data-test="dust-quote-listing-sell"]'),
        await textOf(page, '[data-test="dust-net-instant-sell"]'),
        await textOf(page, '[data-test="dust-net-listing-sell"]')
      ],
      [
        money(firstAnswer.instantBuyInstantSell.ectoAcquisitionCostCopper),
        money(firstAnswer.listingBuyInstantSell.ectoAcquisitionCostCopper),
        money(firstAnswer.instantBuyInstantSell.dustGrossUnitPriceCopper),
        money(firstAnswer.instantBuyListingSell.dustGrossUnitPriceCopper),
        money(firstAnswer.instantBuyInstantSell.dustNetUnitPriceCopper),
        money(firstAnswer.instantBuyListingSell.dustNetUnitPriceCopper)
      ],
      'The quote panel does not show the supplied quotes.'
    )
    record('assumptions and quotes stated by the backend', 'fee 12%, gross and after-fee kept apart')

    // 5. Keyboard: the reload control is reachable by Tab, shows visible focus, and Enter runs it.
    const tabbed = []
    for (let press = 0; press < 12; press += 1) {
      await page.keyboard.press('Tab')
      const focused = await page.evaluate(() => {
        const active = document.activeElement
        if (active === null || active === document.body) return null
        const style = getComputedStyle(active)
        return {
          test: active.getAttribute('data-test'),
          tag: active.tagName.toLowerCase(),
          outlineStyle: style.outlineStyle,
          outlineWidth: Number.parseFloat(style.outlineWidth) || 0
        }
      })
      tabbed.push(focused)
      if (focused?.test === 'ecto-reload') break
    }
    const reloadFocus = tabbed.at(-1)
    check(
      reloadFocus?.test === 'ecto-reload',
      `The reload control was not reached by Tab: ${JSON.stringify(tabbed)}`
    )
    check(
      reloadFocus.outlineStyle !== 'none' && reloadFocus.outlineWidth > 0,
      `The focused control has no visible focus indicator: ${JSON.stringify(reloadFocus)}`
    )

    const callsBeforeReload = origin.requestsTo(ECTO_ROUTE).length
    await page.keyboard.press('Enter')
    await page.waitForFunction(
      (expected) =>
        document.querySelector('[data-test="ecto-scenario-ecto-cost"]')?.textContent.trim() ===
        expected,
      money(salvageResponse(10_000).instantBuyInstantSell.ectoAcquisitionCostCopper),
      { timeout: TIMEOUT_MS }
    )
    assertEqual(
      await renderedScenarios(page),
      expectedScenarios(salvageResponse(10_000)),
      'The reloaded calculation is not the newly supplied one.'
    )
    check(
      origin.requestsTo(ECTO_ROUTE).length === callsBeforeReload + 1,
      `Reloading issued ${origin.requestsTo(ECTO_ROUTE).length - callsBeforeReload} calculations.`
    )
    record(
      'keyboard-operated reload requests exactly one fresh calculation',
      `${tabbed.length} Tab press(es) to the control, visible focus, Enter activated it`
    )

    // 6. A slow calculation: the loading state is shown and the control cannot be pressed again.
    stub.delayMs = 700
    const callsBeforeSlow = origin.requestsTo(ECTO_ROUTE).length
    await page.click('[data-test="ecto-reload"]')
    await page.waitForSelector('[data-test="ecto-loading"]', { timeout: TIMEOUT_MS })
    const disabledWhileLoading = await page.$eval(
      '[data-test="ecto-reload"]',
      (button) => button.disabled
    )
    check(disabledWhileLoading, 'The reload control stayed enabled while a calculation was running.')
    const staleValues = await page.$$eval('[data-test="ecto-scenario-ecto-cost"]', (cells) =>
      cells.map((cell) => cell.textContent.trim())
    )
    check(
      staleValues.length === 0,
      `An earlier snapshot stayed on screen while a new calculation ran: ${staleValues.join(', ')}`
    )
    await page.waitForSelector('[data-test="ecto-scenario-table"]', { timeout: TIMEOUT_MS })
    stub.delayMs = 0
    check(
      origin.requestsTo(ECTO_ROUTE).length === callsBeforeSlow + 1,
      'A duplicate calculation was issued while one was in flight.'
    )
    record(
      'loading state shown, reload control disabled while it runs',
      'one calculation issued, no earlier snapshot presented as the running one'
    )

    // 7. Narrow: the page reflows and the wide table scrolls inside its own region.
    await page.setViewportSize(NARROW)
    await page.waitForTimeout(200)
    const narrowOverflow = await pageOverflow(page)
    check(
      narrowOverflow.scrollWidth <= narrowOverflow.clientWidth + 1,
      `The page scrolls sideways at ${NARROW.width}px: ${JSON.stringify(narrowOverflow)}`
    )
    const region = await page.$eval('.table-region', (element) => ({
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
      tabIndex: element.tabIndex
    }))
    check(
      region.scrollWidth > region.clientWidth,
      'The scenario table did not need its own scrolling region at 360px — check the assertion, not the page.'
    )
    check(region.tabIndex === 0, 'The scrollable region is not reachable by keyboard.')
    const narrowRows = await renderedScenarios(page)
    check(narrowRows.length === 4, `Only ${narrowRows.length} scenarios survived the narrow layout.`)
    record(
      'narrow layout keeps every scenario readable',
      `${NARROW.width}px, region ${region.clientWidth}px holds ${region.scrollWidth}px of columns`
    )
    await page.setViewportSize(WIDE)

    // 8. A failed calculation: an explicit failure, no stale figures, and a working retry.
    stub.mode = 'failure'
    await page.click('[data-test="ecto-reload"]')
    await page.waitForSelector('[data-test="ecto-error"]', { timeout: TIMEOUT_MS })
    const failure = await textOf(page, '[data-test="ecto-error"]')
    check(
      failure.includes('PRICE_SOURCE_UNAVAILABLE'),
      `The failure does not carry the backend's own code: ${failure}`
    )
    check(
      !/exception|stack|http:\/\/|guildwars2/i.test(failure),
      `The failure exposes backend internals: ${failure}`
    )
    check(
      (await page.$$('[data-test="ecto-scenario-ecto-cost"]')).length === 0,
      'An earlier calculation stayed on screen after a failed reload.'
    )
    stub.mode = 'ok'
    await page.click('[data-test="ecto-retry"]')
    await page.waitForSelector('[data-test="ecto-scenario-table"]', { timeout: TIMEOUT_MS })
    record('failed reload shows a safe failure and retries on request', 'no stale figures in between')

    // 9. A completed calculation with no usable quotes is an answer, not a failure or a zero.
    stub.mode = 'unavailable'
    await page.click('[data-test="ecto-reload"]')
    await page.waitForSelector('[data-test="ecto-unavailable"]', { timeout: TIMEOUT_MS })
    check(
      (await page.$('[data-test="ecto-error"]')) === null,
      'The unavailable result was presented as a failure.'
    )
    check(
      (await page.$('[data-test="ecto-scenario-table"]')) === null,
      'Scenarios were rendered for a calculation that produced none.'
    )
    const unavailableText = await textOf(page, '[data-test="ecto-screen"]')
    check(
      !/\b0c\b/.test(unavailableText),
      `A missing value was shown as zero: ${unavailableText.slice(0, 200)}`
    )
    stub.mode = 'ok'
    record('no usable quotes is an answer, never zeros', 'no scenario table, no failure notice')

    // 10. What the browser asked for, and where.
    // Everything this page asked for after it was opened is its own calculation; the two crafting
    // requests below are the default screen's, made before the navigation.
    const openedAt = origin.requestsTo('/api/').findIndex((request) => request.path === ECTO_ROUTE)
    const afterOpening = [
      ...new Set(origin.requestsTo('/api/').slice(openedAt).map((request) => request.path))
    ]
    assertEqual(afterOpening, [ECTO_ROUTE], 'The page called a route other than its own calculation.')
    const upstream = browserRequests.filter((url) => /guildwars2\.com/i.test(url))
    check(upstream.length === 0, `The browser requested ArenaNet: ${upstream.slice(0, 3).join(', ')}`)
    const foreign = browserRequests.filter((url) => !url.startsWith(origin.origin))
    check(foreign.length === 0, `The browser left this origin: ${foreign.slice(0, 3).join(', ')}`)
    check(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`)
    record(
      'every request went to this origin and to that one route',
      `${browserRequests.length} browser requests, ${origin.requestsTo(ECTO_ROUTE).length} calculations`
    )

    console.log(`\nEctoplasm browser smoke PASSED (${steps.length} steps).`)
  } finally {
    await browser.close()
    origin.close()
  }
}

run().catch((error) => {
  console.error(`\nEctoplasm browser smoke FAILED after ${steps.length} step(s): ${error.message}`)
  process.exitCode = 1
})
