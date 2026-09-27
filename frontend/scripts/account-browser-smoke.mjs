/**
 * Browser check for the Bank and Materials screens (STORY-WEB-003 required tests).
 *
 * Drives a real Chromium-family browser against an already-running frontend and backend, and
 * compares what the browser rendered against the very responses the two routes return: slot order,
 * the positions of the empty bank slots, item ids and counts, and the material category labels and
 * stack order. Nothing is mocked, nothing is written, and no synchronization is triggered — both
 * routes are GETs (CURRENT_ARCHITECTURE.md 5.12), and the script's own comparison read is a second
 * GET of the same route.
 *
 * It asserts no domain value and establishes no performance (TEST_STRATEGY / TARGET_ARCHITECTURE 33).
 *
 * Prerequisites, both started by hand:
 *   1. the backend    — `./mvnw spring-boot:run` in the repository root
 *   2. the dev server — `npm run dev` in `frontend/` (it proxies `/api` to the backend)
 *
 * Usage:  npm run smoke:account
 * Environment:
 *   GW2_FRONTEND_URL  page to open        (default http://localhost:5173)
 *   GW2_BROWSER_PATH  browser executable  (default: the first installed Chrome/Edge found)
 *   GW2_SMOKE_TIMEOUT_MS  per-step wait   (default 60000)
 *
 * Exits 0 when every step passed, 1 otherwise. It reads no credential and holds none.
 */
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

const FRONTEND_URL = process.env.GW2_FRONTEND_URL ?? 'http://localhost:5173'
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 60_000)

/** The one route an item image may come from (TARGET_ARCHITECTURE.md 12.1). */
const ICON_ROUTE = /^\/api\/items\/\d+\/icon\/[0-9a-f]{64}\.(png|jpg)$/

const steps = []

function record(name, detail) {
  steps.push({ name, detail })
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

function assertEqual(actual, expected, what) {
  const left = JSON.stringify(actual)
  const right = JSON.stringify(expected)
  if (left !== right) {
    const cut = (text) => (text.length > 400 ? `${text.slice(0, 400)}…` : text)
    throw new Error(`${what}\n  rendered: ${cut(left)}\n  response: ${cut(right)}`)
  }
}

async function run() {
  const browserPath = resolveBrowserPath()
  console.log(`Browser : ${browserPath}`)
  console.log(`Page    : ${FRONTEND_URL}\n`)

  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage()

  /** Every backend call the browser really made, so the run proves the network path was used. */
  const apiCalls = []
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/')) {
      apiCalls.push({ path: url.pathname, status: response.status() })
    }
  })

  /** Every request the browser issued, whatever its origin — what proves where images came from. */
  const browserRequests = []
  page.on('request', (request) =>
    browserRequests.push({ url: request.url(), type: request.resourceType() })
  )

  const consoleErrors = []
  page.on('pageerror', (error) => consoleErrors.push(String(error)))

  try {
    await page.goto(FRONTEND_URL, { waitUntil: 'domcontentloaded', timeout: TIMEOUT_MS })
    await page.waitForSelector('[data-test="screen-nav"]', { timeout: TIMEOUT_MS })
    if (apiCalls.some((call) => call.path.startsWith('/api/account/'))) {
      throw new Error('An account read was requested before either screen was opened.')
    }
    record('page loaded', 'no account read before a screen is opened')

    // ---------------------------------------------------------------- Bank
    await page.click('[data-test="nav-bank"]')
    await page.waitForSelector(
      '[data-test="bank-slots"], [data-test="bank-no-slots"], [data-test="bank-error"]',
      { timeout: TIMEOUT_MS }
    )

    const bankCall = apiCalls.find((call) => call.path === '/api/account/bank')
    if (bankCall === undefined) throw new Error('Opening Bank never called /api/account/bank.')
    if (bankCall.status !== 200) throw new Error(`/api/account/bank answered HTTP ${bankCall.status}.`)
    if (await page.$('[data-test="bank-error"]')) {
      throw new Error(await page.$eval('[data-test="bank-error"]', (el) => el.textContent.trim()))
    }
    record('bank read by the browser', `HTTP ${bankCall.status}`)

    // The same route, read again, as the reference the rendering is compared against.
    const bankResponse = await (await page.request.get(`${FRONTEND_URL}/api/account/bank`)).json()

    const renderedSlots = await page.$$eval('[data-test="bank-slot"]', (slots) =>
      slots.map((slot) => ({
        slot: slot.getAttribute('data-slot'),
        empty: slot.querySelector('[data-test="bank-empty-slot"]') !== null,
        identity: slot.querySelector('[data-test="item-identity"]')?.textContent.trim() ?? null,
        count: slot.querySelector('[data-test="item-count"]')?.textContent.trim() ?? null
      }))
    )

    assertEqual(
      renderedSlots.map((slot) => slot.slot),
      bankResponse.slots.map((slot) => String(slot.slot)),
      'The rendered slot order does not match the response.'
    )
    assertEqual(
      renderedSlots.flatMap((slot, index) => (slot.empty ? [index] : [])),
      bankResponse.slots.flatMap((slot, index) =>
        slot.itemId === null && slot.count === null ? [index] : []
      ),
      'The empty slot positions do not match the response.'
    )
    assertEqual(
      renderedSlots.filter((slot) => !slot.empty).map((slot) => `${slot.identity} ${slot.count}`),
      bankResponse.slots
        .filter((slot) => !(slot.itemId === null && slot.count === null))
        .map((slot) => `#${slot.itemId} × ${slot.count}`),
      'The rendered item identities or counts do not match the response.'
    )
    const emptySlots = renderedSlots.filter((slot) => slot.empty).length
    record(
      'bank rendering matches the response',
      `${renderedSlots.length} slots (slotCount ${bankResponse.slotCount}), ${emptySlots} empty, in the supplied order`
    )

    // An explicit reload repeats that one read and nothing else.
    const beforeReload = apiCalls.length
    const reloadAnswered = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/account/bank',
      { timeout: TIMEOUT_MS }
    )
    await page.click('[data-test="bank-reload"]')
    await reloadAnswered
    await page.waitForSelector('[data-test="bank-slots"], [data-test="bank-no-slots"]', {
      timeout: TIMEOUT_MS
    })
    // The reload re-reads the bank once. Images on the shared icon route may arrive alongside it —
    // re-rendered rows can bring further icons into loading distance — so they are separated out and
    // counted rather than treated as an unexpected call; anything else at all is a failure.
    const reloadCalls = apiCalls.slice(beforeReload).map((call) => call.path)
    const reloadImages = reloadCalls.filter((path) => ICON_ROUTE.test(path))
    assertEqual(
      reloadCalls.filter((path) => !ICON_ROUTE.test(path)),
      ['/api/account/bank'],
      'Reloading the bank requested something other than that one read and its item images.'
    )
    record(
      'bank reload repeats only that read',
      `/api/account/bank, plus ${reloadImages.length} image request(s) on the shared icon route`
    )

    // ----------------------------------------------------------- Materials
    await page.click('[data-test="nav-materials"]')
    await page.waitForSelector(
      '[data-test="material-category"], [data-test="materials-empty"], [data-test="materials-error"]',
      { timeout: TIMEOUT_MS }
    )

    const materialsCall = apiCalls.find((call) => call.path === '/api/account/materials')
    if (materialsCall === undefined) {
      throw new Error('Opening Materials never called /api/account/materials.')
    }
    if (materialsCall.status !== 200) {
      throw new Error(`/api/account/materials answered HTTP ${materialsCall.status}.`)
    }
    if (await page.$('[data-test="materials-error"]')) {
      throw new Error(await page.$eval('[data-test="materials-error"]', (el) => el.textContent.trim()))
    }
    record('materials read by the browser', `HTTP ${materialsCall.status}`)

    const materialsResponse = await (
      await page.request.get(`${FRONTEND_URL}/api/account/materials`)
    ).json()

    const renderedCategories = await page.$$eval('[data-test="material-category"]', (categories) =>
      categories.map((category) => ({
        name: category.querySelector('[data-test="material-category-name"]').textContent.trim(),
        stacks: Array.from(category.querySelectorAll('[data-test="material-stack"]')).map(
          (stack) =>
            `${stack.querySelector('[data-test="item-identity"]').textContent.trim()} ` +
            `${stack.querySelector('[data-test="item-count"]').textContent.trim()} ` +
            `${stack.querySelector('[data-test="material-stack-category"]').textContent.trim()}`
        )
      }))
    )

    assertEqual(
      renderedCategories.map((category) => category.name),
      materialsResponse.categories.map((category) => category.name),
      'The rendered category labels or their order do not match the response.'
    )
    assertEqual(
      renderedCategories.map((category) => category.stacks),
      materialsResponse.categories.map((category) =>
        category.materials.map(
          (stack) =>
            `${stack.itemId === null ? '— (no item id supplied)' : `#${stack.itemId}`} ` +
            `× ${stack.count} category id ${stack.category}`
        )
      ),
      'The rendered stacks do not match the response.'
    )
    const stackCount = renderedCategories.reduce((sum, category) => sum + category.stacks.length, 0)
    record(
      'materials rendering matches the response',
      `${renderedCategories.length} categories (categoryCount ${materialsResponse.categoryCount}), ` +
        `${stackCount} stacks, labels and order as supplied`
    )

    // ------------------------------------------------------------- Overall
    // Images are this application's own delivery route and nothing else (STORY-WEB-010,
    // TARGET_ARCHITECTURE.md 12.1). An entry without retained metadata renders the shared neutral
    // fallback, which is inline and asks for nothing.
    const images = await page.$$eval('img', (elements) =>
      elements.map((image) => ({
        src: image.getAttribute('src'),
        referrer: image.getAttribute('referrerpolicy')
      }))
    )
    const offRoute = images.filter((image) => !ICON_ROUTE.test(image.src ?? ''))
    if (offRoute.length > 0) {
      throw new Error(`An image was rendered off the icon route: ${JSON.stringify(offRoute)}`)
    }
    const withoutPolicy = images.filter((image) => image.referrer !== 'no-referrer')
    if (withoutPolicy.length > 0) {
      throw new Error(`An image was rendered without the no-referrer policy: ${withoutPolicy.length}`)
    }
    const fallbacks = await page.$$eval(
      '[data-test="item-icon"][data-icon-state="no-url"]',
      (elements) => elements.length
    )
    record(
      'images come only from this application',
      `${images.length} on /api/items/{id}/icon/…, all no-referrer; ${fallbacks} entries on the fallback`
    )

    const syncCalls = apiCalls.filter(
      (call) => call.path.startsWith('/api/sync') || call.path.startsWith('/api/prices')
    )
    if (syncCalls.length > 0) {
      throw new Error(`Synchronization routes were called: ${JSON.stringify(syncCalls)}`)
    }
    const foreignCalls = apiCalls.filter((call) => !call.path.startsWith('/api/'))
    if (foreignCalls.length > 0) {
      throw new Error(`Non-backend API calls observed: ${JSON.stringify(foreignCalls)}`)
    }
    // Not one request to ArenaNet, for an image or for anything else: the browser talks to this
    // application only, and an image it could not get here has no upstream fallback.
    const upstream = browserRequests.filter((request) => /guildwars2\.com/i.test(request.url))
    if (upstream.length > 0) {
      throw new Error(`The browser requested ArenaNet: ${JSON.stringify(upstream)}`)
    }
    const pageOrigin = new URL(FRONTEND_URL).origin
    const otherOrigins = browserRequests.filter((request) => !request.url.startsWith(pageOrigin))
    if (otherOrigins.length > 0) {
      throw new Error(`The browser left this origin: ${JSON.stringify(otherOrigins.slice(0, 5))}`)
    }
    if (consoleErrors.length > 0) throw new Error(`Uncaught page errors: ${consoleErrors.join(' | ')}`)
    record(
      'no synchronization, no page error, no non-backend call',
      `${browserRequests.length} browser requests, all to ${new URL(FRONTEND_URL).origin}`
    )

    console.log(`\nAccount browser smoke PASSED (${steps.length} steps).`)
    console.log(`Backend calls observed: ${apiCalls.map((call) => `${call.path} ${call.status}`).join(', ')}`)
  } finally {
    await browser.close()
  }
}

run().catch((error) => {
  console.error(`\nAccount browser smoke FAILED after ${steps.length} step(s): ${error.message}`)
  console.error('Are both the backend (./mvnw spring-boot:run) and the dev server (npm run dev) running?')
  process.exitCode = 1
})
