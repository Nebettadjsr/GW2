/**
 * Real-browser check of the application shell: navigation, responsive layout, zoom, keyboard focus
 * and text contrast (STORY-WEB-004, FRONTEND_UX_GUIDELINES 2, 3, 7, 8).
 *
 * Runs the built frontend against a *controlled* API boundary (`scripts/stubOrigin.mjs`): this script
 * answers every route the areas read, so no real backend, no database and no GW2 API is involved
 * and no synchronization can be started. It therefore evidences structure, layout and interaction —
 * never real data, and never page-load performance (TARGET_ARCHITECTURE 33).
 *
 * Usage:  npm run build && npm run smoke:layout
 * Environment:
 *   GW2_LAYOUT_SMOKE_PORT  port for the stub origin  (default 5175)
 *   GW2_BROWSER_PATH       browser executable        (default: the first installed Chrome/Edge)
 *   GW2_SMOKE_TIMEOUT_MS   per-step wait             (default 30000)
 *
 * Exits 0 when every step passed, 1 otherwise.
 */
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startStubOrigin } from './stubOrigin.mjs'

const PORT = Number(process.env.GW2_LAYOUT_SMOKE_PORT ?? 5175)
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
const DIST_DIR = fileURLToPath(new URL('../dist/', import.meta.url))

/** Verification examples, not breakpoint values: a phone, a tablet and a desktop width. */
const VIEWPORTS = [
  { name: 'desktop 1440×900', width: 1440, height: 900 },
  { name: 'tablet 768×1024', width: 768, height: 1024 },
  { name: 'phone 360×800', width: 360, height: 800 }
]

/**
 * One entry per navigation destination `shell/destinations.ts` offers: the heading and document title
 * it must name, and the element that says its content is on screen. The set is not a literal this
 * script may fall behind on — step 1 requires it to match the destinations the shell actually rendered,
 * so a destination added without an entry here fails instead of quietly going unchecked.
 */
const AREAS = [
  { id: 'crafting', heading: 'Crafting Profit', ready: '[data-test="profit-table"]' },
  { id: 'discovery', heading: 'Crafting Discovery', ready: '[data-test="discovery-table"]' },
  // Both halves of the Ectoplasm result panel: the cost summary needs the Trading Post quotes and the
  // Luck section needs the account answer, so a page that rendered only one of them is not ready.
  { id: 'ecto', heading: 'Ecto Salvage', ready: '.panel:has(.result-summary):has(.account-luck)' },
  { id: 'synchronization', heading: 'Synchronization', ready: '[data-test="sync-controls"]' },
  { id: 'bank', heading: 'Bank', ready: '[data-test="bank-slots"]' },
  { id: 'materials', heading: 'Materials', ready: '[data-test="material-category"]' }
]

/** Resolved by id, never by index, so reordering or extending `AREAS` cannot silently retarget a step. */
function areaOf(id) {
  const area = AREAS.find((candidate) => candidate.id === id)
  if (area === undefined) throw new Error(`This check has no area entry for ${id}.`)
  return area
}

/** The area the table, zoom, focus, contrast and reduced-motion steps are taken on. */
const PROFIT_AREA = areaOf('crafting')

const INTRO_SAMPLE = 'page intro'
/**
 * Where the introductory sentence is measured. Crafting Profit no longer has one (DOMAIN_SPEC 2.1.1
 * removed it), so this pair is taken on an area that still renders an introduction instead of being
 * skipped on a page where the element cannot exist — see `assertMeasured`.
 */
const INTRO_AREA = areaOf('synchronization')

const steps = []

function record(name, detail) {
  steps.push({ name, detail })
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

function check(condition, message) {
  if (!condition) throw new Error(message)
}

/** A row set wide enough to need the table's own horizontal scrolling, with real null cases. */
function profitRows() {
  const row = (recipeId, outputName, overrides) => ({
    recipeId,
    outputItemId: 1000 + recipeId,
    outputName,
    outputCount: 1,
    disciplines: 'Armorsmith,Weaponsmith',
    minRating: 400,
    craftableCount: 12,
    profitCopper: 12_345,
    totalProfitCopper: 148_140,
    buyCostCopper: 98_765,
    matsSellValueCopper: 4_321,
    revenueCopper: 111_110,
    resultAvailable: true,
    blockedReason: 'NONE',
    outputPrice: { buyUnitCopper: 120_000, sellUnitCopper: 130_000 },
    missingToBuy: [],
    missingToBuyOne: [],
    ...overrides
  })

  return [
    row(1, 'Deldrimor Steel Ingot'),
    row(2, 'Elonian Leather Square', { blockedReason: 'BUYING_DISABLED', craftableCount: 0 }),
    row(3, 'Spiritwood Plank of Considerable Length and Name', {
      blockedReason: 'PRICE_UNAVAILABLE',
      profitCopper: null,
      totalProfitCopper: null,
      missingToBuy: [{ itemId: 19_699, itemName: 'Charged Core', quantity: 3, price: null }],
      missingToBuyOne: [{ itemId: 19_699, itemName: 'Charged Core', quantity: 1, price: null }]
    }),
    row(4, 'Bolt of Damask', {
      resultAvailable: false,
      craftableCount: null,
      profitCopper: null,
      totalProfitCopper: null,
      buyCostCopper: null,
      outputPrice: null,
      missingToBuy: null,
      missingToBuyOne: null
    }),
    // A loss, so the negative money treatment is on screen to be measured as well as the positive.
    row(5, 'Charged Quartz Crystal', { profitCopper: -3_400, totalProfitCopper: -40_800 })
  ]
}

function bankSlots() {
  return Array.from({ length: 12 }, (_, index) => {
    const isEmpty = index === 3 || index === 7
    return {
      slot: index,
      itemId: isEmpty ? null : 24_295 + index,
      count: isEmpty ? null : 250,
      iconUrl: null,
      rarity: isEmpty ? null : 'Rare'
    }
  })
}

/** The item ids a `/items/…` request asked for, so these fixtures answer what the page asked. */
function requestedIds(url) {
  return (url.searchParams.get('ids') ?? '')
    .split(',')
    .map((id) => Number.parseInt(id.trim(), 10))
    .filter((id) => Number.isFinite(id))
}

const ITEM_NAMES = {
  19_721: 'Glob of Ectoplasm',
  24_277: 'Pile of Crystalline Dust'
}

/**
 * Names for the items a page shows. The ids are echoed from the request rather than listed here, so
 * this stays an answer to what the page asked for as its item set changes. No icon is offered: the
 * bank fixture already renders the no-icon case, and an icon here would only add a subresource.
 */
function itemMetadata(ids) {
  return { items: ids.map((itemId) => ({ itemId, name: ITEM_NAMES[itemId] ?? null, iconUrl: null })) }
}

/**
 * Trading Post quotes. Every value is distinct — per item and per side — so a page reading the buy
 * where it meant the sell, or Dust where it meant Ectoplasm, prints a visibly wrong number instead of
 * the same one twice.
 */
function itemPrices(ids) {
  const quotes = {
    19_721: { buyUnitCopper: 23_416, sellUnitCopper: 24_837 },
    24_277: { buyUnitCopper: 6_142, sellUnitCopper: 6_573 }
  }
  return {
    prices: ids.map((itemId) => ({
      itemId,
      ...(quotes[itemId] ?? { buyUnitCopper: null, sellUnitCopper: null })
    }))
  }
}

/**
 * An account part-way to its next Magic Find percent, so the Ectoplasm Salvage page renders its Luck
 * summary, a partly filled progress bar and every Magic Find target row rather than the unavailable
 * notice. `consumedLuck` sits between the current and next cumulative values, which is what keeps that
 * bar inside its track.
 */
function accountLuck() {
  return {
    consumedLuck: 4_318_772,
    currentLuckMagicFindPercent: 152,
    cumulativeLuckForCurrentPercent: 4_280_000,
    nextMagicFindPercent: 153,
    cumulativeLuckForNextPercent: 4_400_000,
    luckRemainingToNextPercent: 81_228,
    luckRemainingToCap: 9_481_228,
    cumulativeLuckForCap: 13_800_000,
    fetchedAt: '2026-09-29T08:15:00Z',
    // All three target kinds, so each label the page can print is on screen.
    targets: [
      { kind: 'PLUS_5', magicFindPercent: 157, cumulativeLuck: 4_960_000, luckRemaining: 641_228 },
      { kind: 'PLUS_10', magicFindPercent: 162, cumulativeLuck: 5_700_000, luckRemaining: 1_381_228 },
      { kind: 'CAP', magicFindPercent: 300, cumulativeLuck: 13_800_000, luckRemaining: 9_481_228 }
    ]
  }
}

function answerApi({ url, sendJson }) {
  if (url.pathname === '/api/crafting/selector-options') {
    return sendJson(200, {
      defaultScopeKind: 'ALL',
      disciplines: ['Armorsmith', 'Chef', 'Weaponsmith'],
      characterOptionCount: 1,
      characterOptions: [
        { characterName: 'A Long Character Name', discipline: 'Chef', rating: 500, active: true }
      ]
    })
  }
  if (url.pathname === '/api/crafting/profit') {
    const rows = profitRows()
    return sendJson(200, {
      scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 },
      settings: {
        useOwnMats: true,
        allowBuying: true,
        maxBuyCopper: 250_000,
        listingSell: false,
        listingBuy: false,
        dailyBuyInsteadOfCraft: true,
        allowNonTradeableMaterials: true
      },
      rowCount: rows.length,
      rows
    })
  }
  // Discovery's rows are the same shape as Profit's, so the same fixture serves both tables; what this
  // check needs from this route is a rendered comparison list, not a second set of candidates.
  if (url.pathname === '/api/crafting/discovery') {
    const rows = profitRows()
    return sendJson(200, {
      scope: { discipline: 'Chef', characterName: 'A Long Character Name', rating: 500 },
      inventoryCharacterName: 'A Long Character Name',
      settings: {
        useOwnMats: true,
        allowBuying: true,
        maxBuyCopper: 200_000,
        listingSell: false,
        listingBuy: false,
        dailyBuyInsteadOfCraft: false
      },
      rowCount: rows.length,
      rows
    })
  }
  // What the Ectoplasm Salvage page loads: names for the items it shows, quotes for Ectoplasm and
  // Crystalline Dust, and the account's Luck.
  if (url.pathname === '/api/items/metadata') {
    return sendJson(200, itemMetadata(requestedIds(url)))
  }
  if (url.pathname === '/api/items/prices') {
    return sendJson(200, itemPrices(requestedIds(url)))
  }
  if (url.pathname === '/api/account/luck') {
    return sendJson(200, accountLuck())
  }
  if (url.pathname === '/api/account/bank') {
    const slots = bankSlots()
    return sendJson(200, { slotCount: slots.length, slots })
  }
  if (url.pathname === '/api/account/materials') {
    return sendJson(200, {
      categoryCount: 2,
      categories: [
        {
          name: 'Basic Crafting Materials',
          materials: [
            { category: 5, itemId: 19_697, count: 250, iconUrl: null, rarity: 'Basic' },
            { category: 5, itemId: 19_699, count: 41, iconUrl: null, rarity: null }
          ]
        },
        {
          name: 'Category 38',
          materials: [{ category: 38, itemId: 46_731, count: 7, iconUrl: null, rarity: null }]
        }
      ]
    })
  }
  sendJson(404, { error: 'NOT_FOUND', message: url.pathname })
}

/** Page-level horizontal overflow: the failure section 3 forbids outright. */
async function pageOverflow(page) {
  return page.evaluate(() => {
    const root = document.documentElement
    return { scrollWidth: root.scrollWidth, clientWidth: root.clientWidth }
  })
}

/**
 * Opens an area by a fresh load of its own URL. The reload matters: a `goto` that only changes the
 * fragment is a same-document navigation, which would test in-page routing rather than the direct
 * URL — and would leave the previous page's focus and state in place.
 */
async function openArea(page, origin, area) {
  await page.goto(`${origin}/#/${area.id}`, { waitUntil: 'networkidle', timeout: TIMEOUT_MS })
  await page.reload({ waitUntil: 'networkidle', timeout: TIMEOUT_MS })
  await page.waitForSelector(area.ready, { timeout: TIMEOUT_MS })
}

async function textOf(page, selector) {
  return (await page.$eval(selector, (element) => element.textContent ?? ''))
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * Measures real text/background pairs from computed styles and returns WCAG contrast ratios. Token
 * values alone establish nothing; these are the combinations the browser actually rendered.
 *
 * `only` restricts the set to the named pairs, so a pair that belongs to another area can be measured
 * where it is actually rendered without re-measuring this page's pairs. A pair whose element is absent
 * is still skipped here — `assertMeasured` decides which names may not be missing.
 */
async function measureContrast(page, { only = null } = {}) {
  return page.evaluate((onlyNames) => {
    const channel = (value) => {
      const srgb = value / 255
      return srgb <= 0.03928 ? srgb / 12.92 : ((srgb + 0.055) / 1.055) ** 2.4
    }
    const luminance = ([r, g, b]) =>
      0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    const parse = (color) => (color.match(/\d+(\.\d+)?/g) ?? ['0', '0', '0']).map(Number)
    const ratio = (foreground, background) => {
      const light = Math.max(luminance(foreground), luminance(background))
      const dark = Math.min(luminance(foreground), luminance(background))
      return (light + 0.05) / (dark + 0.05)
    }
    const opaqueBackgroundOf = (element) => {
      let node = element
      while (node !== null) {
        const background = getComputedStyle(node).backgroundColor
        if (background !== 'rgba(0, 0, 0, 0)' && background !== 'transparent') return background
        node = node.parentElement
      }
      return getComputedStyle(document.body).backgroundColor
    }

    const samples = []
    const sample = (name, element) => {
      if (onlyNames !== null && !onlyNames.includes(name)) return
      if (element === null) return
      const style = getComputedStyle(element)
      const background = opaqueBackgroundOf(element)
      samples.push({
        name,
        color: style.color,
        background,
        fontSize: Number.parseFloat(style.fontSize),
        bold: Number(style.fontWeight) >= 700,
        ratio: ratio(parse(style.color), parse(background))
      })
    }

    sample('page heading', document.querySelector('[data-page-heading]'))
    // Only rendered on the pages that keep an introduction, so this one is measured on `INTRO_AREA`.
    sample('page intro', document.querySelector('[data-test="page-intro"]'))
    sample('secondary text', document.querySelector('.meta'))
    sample('primary action', document.querySelector('.button--primary'))
    sample('quiet action', document.querySelector('.table-region button'))
    sample('current destination', document.querySelector('[aria-current="page"]'))
    sample('other destination', document.querySelector('.site-nav__link:not([aria-current])'))
    sample('table cell', document.querySelector('.profit-table tbody td'))
    sample('column basis note', document.querySelector('.column-note'))
    sample('recipe identifier', document.querySelector('.recipe-ids'))
    sample('settings summary', document.querySelector('.settings-disclosure > summary'))
    sample('blocked row state', document.querySelector('.status--caution'))
    sample('unavailable row state', document.querySelector('.status--unknown'))
    sample('ok row state', document.querySelector('.status--success'))
    sample('profit value', document.querySelector('.money--gain'))
    sample('loss value', document.querySelector('.money--loss'))

    // Status tones the current page does not happen to show, measured from the shared treatments
    // themselves rather than asserted from token values.
    const probe = document.createElement('span')
    probe.textContent = 'probe'
    document.body.append(probe)
    for (const tone of ['success', 'failure', 'busy', 'unknown', 'idle']) {
      probe.className = `status status--${tone}`
      sample(`status tone ${tone}`, probe)
    }
    probe.remove()

    return samples
  }, only)
}

/**
 * Fails the run when a pair that must be measured was not. A `sample` whose element is absent is
 * skipped silently, which is how the removal of the Crafting Profit introduction left the `page intro`
 * pair unmeasured while the sample-count floor kept passing (STORY-WEB-015 F001). A missing name, a
 * name no `sample` call produced, and an unusable ratio are all reported here instead of counting as
 * coverage.
 */
function assertMeasured(samples, required, where) {
  const unmeasured = required.filter((name) => {
    const taken = samples.find((sample) => sample.name === name)
    return taken === undefined || !Number.isFinite(taken.ratio)
  })
  check(
    unmeasured.length === 0,
    `${where}: no measurable element for ${unmeasured.join(', ')}. That contrast pair was not ` +
      'measured, so it cannot count as coverage — point the sample at a rendered element.'
  )
}

/**
 * The destinations the shell actually rendered, in document order, as their `data-test` names.
 *
 * Both the areas this check must cover and the navigation stops the focus order must contain are
 * derived from this rather than from a literal count. A count written here goes stale the day a
 * destination is added — which is exactly what happened to the four Crafting Discovery and Ectoplasm
 * Salvage arrived with (STORY-WEB-017 F001/F002).
 */
async function renderedDestinations(page) {
  return page.$$eval('[data-test="screen-nav"] .site-nav__link', (links) =>
    links.map((link) => link.getAttribute('data-test'))
  )
}

async function focusWalk(page, stepCount) {
  const focused = []
  for (let step = 0; step < stepCount; step += 1) {
    await page.keyboard.press('Tab')
    focused.push(
      await page.evaluate(() => {
        const active = document.activeElement
        if (active === null) return null
        const style = getComputedStyle(active)
        return {
          tag: active.tagName,
          test: active.getAttribute('data-test'),
          text: (active.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 40),
          outlineStyle: style.outlineStyle,
          outlineWidth: Number.parseFloat(style.outlineWidth)
        }
      })
    )
  }
  return focused
}

async function run() {
  check(existsSync(`${DIST_DIR}index.html`), 'frontend/dist is missing — run `npm run build` first.')

  const browserPath = resolveBrowserPath()
  const stub = await startStubOrigin({ port: PORT, distDir: DIST_DIR, answerApi })
  console.log(`Browser : ${browserPath}`)
  console.log(`Page    : ${stub.origin} (stub backend in this process)\n`)

  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage({ viewport: VIEWPORTS[0] })
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))
  const browserCalls = []
  page.on('response', (response) => browserCalls.push(new URL(response.url()).pathname))

  try {
    await page.goto(`${stub.origin}/`, { waitUntil: 'networkidle', timeout: TIMEOUT_MS })
    check(
      stub.servedCount() > 0,
      `The page at ${stub.origin} was not served by this script — stop whatever else is listening ` +
        'on that port. Nothing was checked.'
    )
    record('page served by this script', `${stub.servedCount()} requests answered so far`)

    // 1. What follows has to cover what the shell offers. A destination in the navigation with no
    // `AREAS` entry gets no heading, title, `aria-current` or reflow check at all, and an entry for a
    // destination that no longer exists cannot open — both are reported here, by name, rather than
    // leaving the per-viewport line claiming a coverage the list no longer has.
    const navStopNames = await renderedDestinations(page)
    const navIds = navStopNames.map((name) => String(name).replace(/^nav-/, ''))
    check(navIds.length > 0, 'The shell rendered no navigation destination, so nothing could be covered.')
    const uncovered = navIds.filter((id) => !AREAS.some((area) => area.id === id))
    check(
      uncovered.length === 0,
      `The navigation offers ${uncovered.join(', ')}, which this check does not cover — add an AREAS ` +
        'entry naming its heading and its ready selector, or that destination stays unchecked.'
    )
    const absent = AREAS.map((area) => area.id).filter((id) => !navIds.includes(id))
    check(
      absent.length === 0,
      `This check covers ${absent.join(', ')}, which the navigation no longer offers: ${navIds.join(', ')}.`
    )
    record(`all ${navIds.length} rendered destinations are covered by this check`, navIds.join(', '))

    // 2. Every area reachable by its own URL, named, marked and reflowing at three widths.
    for (const viewport of VIEWPORTS) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      for (const area of AREAS) {
        await openArea(page, stub.origin, area)

        check(
          (await textOf(page, '[data-page-heading]')) === area.heading,
          `${area.id} at ${viewport.name}: the page heading did not read "${area.heading}".`
        )
        check(
          (await page.title()) === `${area.heading} · GW2 Crafting Tool`,
          `${area.id}: the document title did not name the area (${await page.title()}).`
        )
        const current = await page.$eval('[aria-current="page"]', (link) => ({
          test: link.getAttribute('data-test'),
          href: link.getAttribute('href')
        }))
        check(
          current.test === `nav-${area.id}` && current.href === `#/${area.id}`,
          `${area.id}: the marked destination was ${current.test} (${current.href}).`
        )

        const overflow = await pageOverflow(page)
        check(
          overflow.scrollWidth <= overflow.clientWidth + 1,
          `${area.id} at ${viewport.name}: the page itself scrolls horizontally ` +
            `(${overflow.scrollWidth} > ${overflow.clientWidth}).`
        )
      }
      record(`all ${AREAS.length} areas reflow at ${viewport.name}`, 'no page-level horizontal scrolling')
    }

    // 3. The wide comparison table keeps its scrolling local, and is reachable by keyboard.
    await page.setViewportSize({ width: 360, height: 800 })
    await openArea(page, stub.origin, PROFIT_AREA)
    const region = await page.$eval('.table-region', (element) => ({
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
      tabindex: element.getAttribute('tabindex'),
      role: element.getAttribute('role'),
      label: element.getAttribute('aria-label')
    }))
    check(
      region.scrollWidth > region.clientWidth,
      'The table did not overflow its region at 360px, so this check proved nothing.'
    )
    check(
      region.tabindex === '0' && region.role === 'region' && region.label !== null,
      `The scrollable region is not a named, focusable region: ${JSON.stringify(region)}`
    )
    record(
      'table scrolling stays inside its own region at 360px',
      `region ${region.clientWidth}px wide holds ${region.scrollWidth}px of columns`
    )

    // 4. Browser zoom: doubling the text size must reflow, not overflow the page.
    await page.setViewportSize({ width: 1440, height: 900 })
    await openArea(page, stub.origin, PROFIT_AREA)
    await page.evaluate(() => {
      document.documentElement.style.fontSize = '200%'
    })
    const zoomed = await pageOverflow(page)
    check(
      zoomed.scrollWidth <= zoomed.clientWidth + 1,
      `At 200% text size the page scrolls horizontally (${zoomed.scrollWidth} > ${zoomed.clientWidth}).`
    )
    check(
      (await page.$eval('[data-page-heading]', (h) => h.getBoundingClientRect().height)) > 0,
      'The page heading was not rendered at 200% text size.'
    )
    await page.evaluate(() => {
      document.documentElement.style.fontSize = ''
    })
    record('reflows at 200% text size', `${zoomed.scrollWidth}px content in ${zoomed.clientWidth}px`)

    // 5. Keyboard: the skip link and every destination are focusable, visibly, in document order.
    // The walk is as long as the shell's own navigation, and the stops after the skip link must be
    // exactly that navigation, in its order — a count written here would only assert an older shell.
    await openArea(page, stub.origin, PROFIT_AREA)
    const walk = await focusWalk(page, navStopNames.length + 1)
    check(walk[0]?.test === null && walk[0]?.tag === 'A', `First stop was not the skip link: ${JSON.stringify(walk[0])}`)
    const navStops = walk.slice(1).map((stop) => stop?.test ?? null)
    check(
      navStops.length === navStopNames.length &&
        navStops.every((stop, index) => stop === navStopNames[index]),
      `Expected the ${navStopNames.length} rendered destinations in the focus order ` +
        `(${navStopNames.join(', ')}), saw ${navStops.join(', ')}.`
    )
    const invisibleFocus = walk.filter(
      (stop) => stop === null || stop.outlineStyle === 'none' || stop.outlineWidth < 1
    )
    check(
      invisibleFocus.length === 0,
      `Keyboard focus was not visible on: ${JSON.stringify(invisibleFocus)}`
    )
    record('keyboard focus order and visible focus', walk.map((stop) => stop.test ?? 'skip link').join(' → '))

    // 6. Enter on a focused destination opens it, and the new page's heading takes focus.
    await page.focus('[data-test="nav-bank"]')
    await page.keyboard.press('Enter')
    await page.waitForSelector('[data-test="bank-slots"]', { timeout: TIMEOUT_MS })
    const focusedAfterNavigation = await page.evaluate(
      () => document.activeElement?.getAttribute('data-test') ?? null
    )
    check(
      focusedAfterNavigation === 'page-heading',
      `After keyboard navigation the focus sat on ${focusedAfterNavigation}.`
    )
    check(
      (await textOf(page, '[data-page-heading]')) === 'Bank',
      'Enter on the Bank destination did not open the Bank area.'
    )
    record('destinations are operable by keyboard', 'Enter opened Bank and focused its heading')

    // 7. Contrast of the combinations actually rendered, not of the token values.
    await openArea(page, stub.origin, PROFIT_AREA)
    // DOMAIN_SPEC 2.1.1's three display filters open enabled, and two of them hide exactly the rows
    // whose treatments are measured here (a loss and a zero count). Switching them off puts those
    // treatments back on screen; it changes nothing about the styles being measured.
    for (const filter of ['filter-zero-craftable', 'filter-not-allowed', 'filter-non-positive-profit']) {
      await page.setChecked(`[data-test="${filter}"]`, false)
    }
    const profitSamples = await measureContrast(page)
    // The introductory sentence is measured where one is still rendered, and is required there: after
    // DOMAIN_SPEC 2.1.1 removed it from Crafting Profit this pair silently measured nothing.
    await openArea(page, stub.origin, INTRO_AREA)
    const samples = [...profitSamples, ...(await measureContrast(page, { only: [INTRO_SAMPLE] }))]
    assertMeasured(samples, [INTRO_SAMPLE], `contrast on ${PROFIT_AREA.id} and ${INTRO_AREA.id}`)
    check(samples.length >= 15, `Too few contrast samples were taken: ${samples.length}`)
    const failures = samples.filter((sample) => {
      const isLargeText = sample.fontSize >= 24 || (sample.fontSize >= 18.66 && sample.bold)
      return sample.ratio < (isLargeText ? 3 : 4.5)
    })
    check(
      failures.length === 0,
      `Insufficient contrast: ${failures
        .map((sample) => `${sample.name} ${sample.ratio.toFixed(2)}:1 (${sample.color} on ${sample.background})`)
        .join('; ')}`
    )
    const worst = samples.reduce((lowest, sample) => (sample.ratio < lowest.ratio ? sample : lowest))
    const intro = samples.find((sample) => sample.name === INTRO_SAMPLE)
    record(
      `${samples.length} rendered text/background pairs meet WCAG AA`,
      `lowest ${worst.name} at ${worst.ratio.toFixed(2)}:1; ${INTRO_SAMPLE} measured on ` +
        `${INTRO_AREA.id} at ${intro.ratio.toFixed(2)}:1`
    )

    // 8. The required-pair guard is what turns a vanished target into a failure. With the rendered
    // introduction taken out of the page, the same measurement must report the pair as unmeasured
    // rather than return a smaller set that still satisfies the sample floor above.
    const introRemoved = await page.evaluate(() => {
      document.querySelector('[data-test="page-intro"]')?.remove()
      return document.querySelector('[data-test="page-intro"]') === null
    })
    check(introRemoved, 'The control could not remove the introduction, so it proved nothing.')
    const control = await measureContrast(page, { only: [INTRO_SAMPLE] })
    check(
      control.length === 0,
      `The control still measured something without the introduction: ${JSON.stringify(control)}`
    )
    let reported = null
    try {
      assertMeasured(control, [INTRO_SAMPLE], 'control')
    } catch (error) {
      reported = error
    }
    check(
      reported !== null && reported.message.includes(INTRO_SAMPLE),
      `A missing contrast target did not fail the check: ${reported?.message ?? 'nothing was thrown'}`
    )
    record('a missing contrast target fails the check', reported.message)

    // 9. Reduced motion: the decorative transitions are actually switched off.
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await openArea(page, stub.origin, PROFIT_AREA)
    const durations = await page.evaluate(() =>
      [...document.querySelectorAll('.site-nav__link, .button--primary')].map(
        (element) => getComputedStyle(element).transitionDuration
      )
    )
    check(
      durations.length > 0 && durations.every((duration) => duration === '0s'),
      `Transitions still run under prefers-reduced-motion: ${durations.join(', ')}`
    )
    await page.emulateMedia({ reducedMotion: null })
    record('reduced-motion preference respected', `${durations.length} controls at 0s`)

    // 10. Nothing in any of the above submitted a synchronization or called another host.
    check(
      stub.requestsTo('/api/sync').length === 0 && stub.requestsTo('/api/prices').length === 0,
      `Navigating submitted a synchronization request: ${JSON.stringify(stub.requestsTo('/api'))}`
    )
    const foreignCalls = browserCalls.filter(
      (path) => path !== '/' && !path.startsWith('/api/') && !path.startsWith('/assets/')
    )
    check(foreignCalls.length === 0, `Calls outside the backend API and page assets: ${foreignCalls.join(', ')}`)
    check(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`)
    record(
      'no synchronization, no foreign call, no page error',
      `${stub.requests.length} API requests, all reads`
    )

    console.log(`\nLayout and accessibility browser check PASSED (${steps.length} steps).`)
    console.log('Every backend answer was produced by this script; no real data was read or written.')
  } finally {
    await browser.close()
    stub.close()
  }
}

run().catch((error) => {
  console.error(`\nLayout and accessibility browser check FAILED after ${steps.length} step(s): ${error.message}`)
  process.exitCode = 1
})
