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
 * The comparison is taken from the hooks the current screens render (STORY-WEB-029). Both screens
 * place `InventoryTile.vue` inside their own wrapper, which carries no identity hook: an entry's item
 * id and owned count are stated in the tile's `title`, and `[data-test="item-count"]` is the painted
 * quantity badge, which the tile deliberately omits where the game omits it too — a bank slot holding
 * one item, and a material position that is not owned at all. Both omissions are therefore part of
 * what is compared, not gaps in it.
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

/**
 * The read-only task-status route the shared HTTP client polls when a read answers
 * `ACCOUNT_DATA_STALE` with a `taskStatusUrl` (`frontend/src/api/http.ts`): it waits for the refresh
 * the backend already had running and then repeats the one read, once. That recovery is part of every
 * account read's current behavior, so it is separated out and counted here rather than treated as an
 * unexpected call — but it starts nothing, which the synchronization step below still asserts.
 */
const TASK_STATUS_ROUTE = /^\/api\/sync\/tasks\/[0-9a-fA-F-]+$/

const steps = []

/** Every backend call the browser really made, so the run proves the network path was used. */
const apiCalls = []

/**
 * How many distinct call shapes the summary below prints before it reports the rest as a count.
 */
const CALL_SUMMARY_LIMIT = 15

/**
 * The path with its identifying segments replaced by the shape they belong to.
 *
 * Only the two routes whose path carries an id need this: a live account's items produce one icon
 * path per item, and a stale read produces one task path per refresh.
 */
function callShape(path) {
  if (ICON_ROUTE.test(path)) return '/api/items/{id}/icon/{hash}'
  if (TASK_STATUS_ROUTE.test(path)) return '/api/sync/tasks/{id}'
  return path
}

/**
 * The observed backend calls as a bounded summary — one line per distinct shape, method and status
 * with how often it was seen, never one line per request.
 *
 * A live account holds hundreds of items, and listing every call individually put all of them on one
 * line — 30 KB of a 31 KB run, measured against a 180-slot bank and 681 material positions — which
 * pushed the step evidence above out of bounded log views (STORY-WEB-031). Collapsing
 * the icon and task-status paths to their shape and capping the printed lines keeps this the same
 * size however much the account holds, while still naming every route that was called and every
 * status it answered — which is what an unexpected call has to be recognized by.
 */
function summarizeBackendCalls(calls) {
  if (calls.length === 0) return 'no backend call observed'
  const byShape = new Map()
  for (const call of calls) {
    const key = `${call.method} ${callShape(call.path)} → HTTP ${call.status}`
    byShape.set(key, (byShape.get(key) ?? 0) + 1)
  }
  const ranked = [...byShape.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
  const lines = ranked
    .slice(0, CALL_SUMMARY_LIMIT)
    .map(([key, count]) => `  ${String(count).padStart(4)} × ${key}`)
  if (ranked.length > CALL_SUMMARY_LIMIT) {
    lines.push(`  … and ${ranked.length - CALL_SUMMARY_LIMIT} further distinct call shape(s)`)
  }
  return (
    `${calls.length} backend call(s) in ${ranked.length} distinct shape(s):\n${lines.join('\n')}`
  )
}

/**
 * The quantity badge as `InventoryTile.vue` paints it, or null where it paints none.
 *
 * `showOne` is the one difference between the two screens: material storage shows a single owned
 * unit, a bank slot does not, and neither shows anything for a position holding nothing. The grouping
 * digits are pinned to `en-US` by the component itself, so this is browser-locale independent.
 */
function quantityBadge(count, { showOne }) {
  if (count === null || count === undefined || count <= 0) return null
  if (count === 1 && !showOne) return null
  return count.toLocaleString('en-US')
}

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

  /** Every request the browser issued, whatever its origin — what proves where images came from. */
  const browserRequests = []

  /**
   * The same backend calls, but in the order the browser *issued* them and each marked once it has
   * been answered. A window over this list attributes a call to whatever the browser was doing when
   * the call left, which is what the Bank reload step below needs.
   */
  const apiRequests = []
  const apiRequestsByRequest = new Map()

  page.on('request', (request) => {
    browserRequests.push({ url: request.url(), type: request.resourceType() })
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/')) return
    const entry = { path: url.pathname, method: request.method(), answered: false }
    apiRequests.push(entry)
    apiRequestsByRequest.set(request, entry)
  })

  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/')) {
      apiCalls.push({
        path: url.pathname,
        method: response.request().method(),
        status: response.status()
      })
    }
    const entry = apiRequestsByRequest.get(response.request())
    if (entry !== undefined) entry.answered = true
  })

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
    if (bankResponse.slots.length === 0) {
      throw new Error(
        `The live bank read returned no slots at all (slotCount ${bankResponse.slotCount}), so this ` +
          'run would compare nothing — synchronize the account before taking this as evidence.'
      )
    }

    const renderedSlots = await page.$$eval('[data-test="bank-slot"]', (slots) =>
      slots.map((slot) => ({
        slot: slot.getAttribute('data-slot'),
        empty: slot.querySelector('[data-test="bank-empty-slot"]') !== null,
        // The slot's own stated identity and owned count. `BankSlotTile.vue` has no identity hook —
        // it names the item here, on the slot itself, and on the tile inside it.
        title: slot.getAttribute('title'),
        quantity: slot.querySelector('[data-test="item-count"]')?.textContent.trim() ?? null
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
      renderedSlots.map((slot) => slot.title),
      bankResponse.slots.map((slot) =>
        slot.itemId === null && slot.count === null
          ? `Empty slot #${slot.slot}`
          : `Item #${slot.itemId ?? 'unknown'} · ${slot.count ?? 'unknown'} in slot #${slot.slot}`
      ),
      'The item identities or owned counts the slots state do not match the response.'
    )
    assertEqual(
      renderedSlots.map((slot) => slot.quantity),
      bankResponse.slots.map((slot) => quantityBadge(slot.count, { showOne: false })),
      'The painted slot quantities do not match the response, badge for badge and omission for omission.'
    )
    const emptySlots = renderedSlots.filter((slot) => slot.empty).length
    const occupiedSlots = renderedSlots.length - emptySlots
    if (occupiedSlots === 0) {
      throw new Error(
        `All ${renderedSlots.length} rendered slots are empty, so no item identity or quantity was ` +
          'compared — synchronize the account before taking this as evidence.'
      )
    }
    const paintedQuantities = renderedSlots.filter((slot) => slot.quantity !== null).length
    record(
      'bank rendering matches the response',
      `${renderedSlots.length} slots (slotCount ${bankResponse.slotCount}), ${emptySlots} empty, ` +
        `${occupiedSlots} occupied, in the supplied order; ${paintedQuantities} painted quantities and ` +
        `${occupiedSlots - paintedQuantities} deliberately omitted for a single held item`
    )

    // An explicit reload repeats that one read and nothing else.
    //
    // The window is delimited by when a call was issued, not by when it was answered. The application
    // lands on its default screen before this script clicks `nav-bank`, and that screen's
    // `/api/crafting/profit` call can still be in flight here; its *response* would otherwise land
    // inside the window and fail a step it has nothing to do with (STORY-SYNC-004, Follow-up Finding
    // F002). Anything the reload itself causes is necessarily issued after the click, so no call this
    // assertion exists to catch is excluded — only calls that were already on the wire.
    //
    // Such a call also gets one continuation: the shared client repeats a read once after waiting out
    // an `ACCOUNT_DATA_STALE` refresh, and that repeat is issued inside the window although it belongs
    // to the earlier read. One further request per in-flight path is therefore allowed for below.
    const beforeReload = apiRequests.length
    const inFlight = apiRequests.filter((call) => !call.answered).map((call) => call.path)
    const inFlightOther = inFlight.filter((path) => !ICON_ROUTE.test(path))
    const inFlightExcluded =
      `${JSON.stringify(inFlightOther)} plus ${inFlight.length - inFlightOther.length} ` +
      'image request(s) on the shared icon route'
    const reloadAnswered = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/account/bank',
      { timeout: TIMEOUT_MS }
    )
    await page.click('[data-test="bank-reload"]')
    await reloadAnswered
    await page.waitForSelector('[data-test="bank-slots"], [data-test="bank-no-slots"]', {
      timeout: TIMEOUT_MS
    })
    // The reload re-reads the bank, and nothing but the bank. Two kinds of accompanying request are
    // separated out and counted rather than treated as an unexpected call, because both belong to
    // that same read: images on the shared icon route — re-rendered rows can bring further icons into
    // loading distance — and the task-status polls the shared client makes while the backend reports
    // the account data stale, after which it repeats the read once. Anything else at all is a failure.
    const reloadCalls = apiRequests.slice(beforeReload).map((call) => call.path)
    const reloadImages = reloadCalls.filter((path) => ICON_ROUTE.test(path))
    const reloadPolls = reloadCalls.filter((path) => TASK_STATUS_ROUTE.test(path))
    const continuations = new Map()
    for (const path of inFlightOther) continuations.set(path, (continuations.get(path) ?? 0) + 1)
    const carriedOver = []
    const reloadOther = []
    for (const path of reloadCalls) {
      if (ICON_ROUTE.test(path) || TASK_STATUS_ROUTE.test(path)) continue
      const remaining = continuations.get(path) ?? 0
      if (remaining > 0) {
        continuations.set(path, remaining - 1)
        carriedOver.push(path)
        continue
      }
      reloadOther.push(path)
    }
    // One bank read, or two when the first answered `ACCOUNT_DATA_STALE` and the client waited out the
    // refresh the backend already had running before repeating it. A second read without a poll is not
    // that path, and is a reload asking for the bank twice.
    const bankReads = reloadOther.filter((path) => path === '/api/account/bank').length
    const allowedReads = reloadPolls.length > 0 ? [1, 2] : [1]
    if (reloadOther.length !== bankReads || !allowedReads.includes(bankReads)) {
      throw new Error(
        'Reloading the bank requested something other than that one read, its item images and the ' +
          'stale-data recovery for that read.\n' +
          `  issued by the reload: ${JSON.stringify(reloadOther)}\n` +
          `  expected            : ${allowedReads.join(' or ')} × "/api/account/bank" and nothing else\n` +
          `  plus ${reloadImages.length} image request(s) on the shared icon route and ` +
          `${reloadPolls.length} task-status poll(s)\n` +
          `  continuations of calls already on the wire: ${JSON.stringify(carriedOver)}\n` +
          `  already in flight when the reload was clicked, so not attributed to it: ${inFlightExcluded}`
      )
    }
    record(
      'bank reload repeats only that read',
      `${bankReads} × /api/account/bank, plus ${reloadImages.length} image request(s) on the shared ` +
        `icon route and ${reloadPolls.length} task-status poll(s) for a refresh the backend already had ` +
        `running; ${inFlight.length} call(s) already in flight excluded — ${inFlightExcluded}` +
        (carriedOver.length === 0 ? '' : `; continuations of those: ${JSON.stringify(carriedOver)}`)
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
    const suppliedStacks = materialsResponse.categories.reduce(
      (sum, category) => sum + category.materials.length,
      0
    )
    if (suppliedStacks === 0) {
      throw new Error(
        `The live materials read returned no stacks at all (categoryCount ` +
          `${materialsResponse.categoryCount}), so this run would compare nothing — synchronize the ` +
          'account before taking this as evidence.'
      )
    }

    const renderedCategories = await page.$$eval('[data-test="material-category"]', (categories) =>
      categories.map((category) => ({
        name: category.querySelector('[data-test="material-category-name"]').textContent.trim(),
        stacks: Array.from(category.querySelectorAll('[data-test="material-stack"]')).map((stack) => {
          // One tile per official position, owned or not. The position states its item on the tile;
          // the badge and the greyed-out treatment are what distinguish owned from unowned.
          const tile = stack.querySelector('.inventory-tile')
          return {
            title: tile === null ? null : tile.getAttribute('title'),
            quantity: stack.querySelector('[data-test="item-count"]')?.textContent.trim() ?? null,
            unowned: tile !== null && tile.classList.contains('inventory-tile--empty')
          }
        })
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
        category.materials.map((stack) => ({
          title: `Item #${stack.itemId}`,
          quantity: quantityBadge(stack.count, { showOne: true }),
          unowned: stack.count === 0
        }))
      ),
      'The rendered stacks do not match the response.'
    )
    const stackCount = renderedCategories.reduce((sum, category) => sum + category.stacks.length, 0)
    const ownedStacks = renderedCategories.reduce(
      (sum, category) => sum + category.stacks.filter((stack) => !stack.unowned).length,
      0
    )
    record(
      'materials rendering matches the response',
      `${renderedCategories.length} categories (categoryCount ${materialsResponse.categoryCount}), ` +
        `${stackCount} positions, labels and order as supplied; ${ownedStacks} owned with a painted ` +
        `quantity, ${stackCount - ownedStacks} unowned positions kept in place and greyed out`
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
      // One misrouted image is the defect; a live page holds hundreds of them, so name the count and
      // the first few rather than every src (STORY-WEB-031).
      throw new Error(
        `${offRoute.length} image(s) were rendered off the icon route, first: ` +
          JSON.stringify(offRoute.slice(0, 5))
      )
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

    // Nothing here starts synchronization or refreshes prices. The task-status route is the one
    // `/api/sync` path this run may touch: it is a GET that reports on a refresh the backend decided
    // to run, and waiting for it is how a stale read recovers — it begins nothing.
    const started = apiCalls.filter(
      (call) =>
        call.path.startsWith('/api/prices') ||
        (call.path.startsWith('/api/sync') &&
          !(TASK_STATUS_ROUTE.test(call.path) && call.method === 'GET'))
    )
    if (started.length > 0) {
      throw new Error(`Synchronization or price-refresh routes were called: ${JSON.stringify(started)}`)
    }
    const statusPolls = apiCalls.filter((call) => TASK_STATUS_ROUTE.test(call.path))
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
      'nothing synchronized, no page error, no non-backend call',
      `${browserRequests.length} browser requests, all to ${new URL(FRONTEND_URL).origin}; ` +
        `${statusPolls.length} read-only task-status poll(s) and no start of any kind`
    )

    console.log(`\nAccount browser smoke PASSED (${steps.length} steps).`)
    console.log(`Backend calls observed: ${summarizeBackendCalls(apiCalls)}`)
  } finally {
    await browser.close()
  }
}

run().catch((error) => {
  console.error(`\nAccount browser smoke FAILED after ${steps.length} step(s): ${error.message}`)
  // The same bounded summary a passing run prints: a failing step's own message names what it
  // compared, and this says which routes the browser had reached by then and what they answered,
  // without the icon requests growing the report (STORY-WEB-031).
  console.error(`Backend calls observed: ${summarizeBackendCalls(apiCalls)}`)
  console.error('Are both the backend (./mvnw spring-boot:run) and the dev server (npm run dev) running?')
  process.exitCode = 1
})
