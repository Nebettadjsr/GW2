/**
 * Real-browser check of the shared item image (`TARGET_ARCHITECTURE.md` 12.1, AR-005;
 * `STORY-WEB-010`).
 *
 * Runs the built frontend against a *controlled* API boundary (`scripts/stubOrigin.mjs`), so every
 * answer — the page data and the image bytes — comes from this process. That is what lets it assert
 * the cases a real backend will not produce on demand: a 503, a 404, an image that arrives late, and
 * bytes that are not a decodable image. It evidences the browser's own behaviour around those cases
 * and nothing about real data, real delivery or page-load performance — `smoke:icons:live` and the
 * `profit-page-perf` measurement own those.
 *
 * What it pins:
 *   1. every request the page makes goes to this application's own origin, and every image goes to
 *      `/api/items/.../icon/...` — no ArenaNet request and no fallback to one;
 *   2. the delivery attributes of 12.1: the supplied URL verbatim, `referrerpolicy=no-referrer`,
 *      reserved width/height, prompt loading for the detail and native lazy loading in the lists;
 *   3. one bundled neutral fallback for a null URL, a 404, a 503 and a decode failure, each
 *      requested exactly once — no retry loop;
 *   4. an image that arrives late shifts nothing, at a wide and a narrow viewport;
 *   5. an empty bank slot stays empty, and item text, counts, rarity and row/detail interaction
 *      survive every image failure.
 *
 * Usage:  npm run build && npm run smoke:icons
 * Environment:
 *   GW2_ICON_SMOKE_PORT   port for the stub origin  (default 5177)
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

const PORT = Number(process.env.GW2_ICON_SMOKE_PORT ?? 5177)
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
const DIST_DIR = fileURLToPath(new URL('../dist/', import.meta.url))

const WIDE = { width: 1440, height: 900 }
const NARROW = { width: 360, height: 800 }

/** A 1×1 PNG and a 1×1 JPEG, both genuinely decodable — the browser is the one judging them. */
const PNG_BYTES = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
)
const JPEG_BYTES = Buffer.from(
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwc' +
    'KDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAA' +
    'AAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==',
  'base64'
)
/** Declared as a PNG, and deliberately not one: the browser must fail to decode this. */
const UNDECODABLE_BYTES = Buffer.from('this is not an image at all, whatever the header says')

const KEY = (digit) => String(digit).repeat(64)

/**
 * The items on screen and what this origin does with each one's image. The URLs are in the backend's
 * exact shape; the frontend is given them and must use them unchanged.
 */
const ITEMS = {
  ok: { id: 1101, url: `/api/items/1101/icon/${KEY(1)}.png`, behaviour: 'png' },
  okJpeg: { id: 1202, url: `/api/items/1202/icon/${KEY(2)}.jpg`, behaviour: 'jpeg' },
  unavailable: { id: 2101, url: `/api/items/2101/icon/${KEY(3)}.png`, behaviour: '503' },
  gone: { id: 2202, url: `/api/items/2202/icon/${KEY(4)}.png`, behaviour: '404' },
  undecodable: { id: 3101, url: `/api/items/3101/icon/${KEY(5)}.png`, behaviour: 'garbage' },
  slow: { id: 4101, url: `/api/items/4101/icon/${KEY(6)}.png`, behaviour: 'slow' },
  none: { id: 5101, url: null, behaviour: 'no metadata' }
}

/** How long the slow image is held back, so the reserved box is observably empty first. */
const SLOW_IMAGE_MS = 1_200

const steps = []

function record(name, detail) {
  steps.push({ name, detail })
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

function check(condition, message) {
  if (!condition) throw new Error(message)
}

function row(recipeId, outputItemId, outputName, iconUrl, overrides = {}) {
  return {
    recipeId,
    outputItemId,
    outputName,
    outputCount: 1,
    disciplines: 'Armorsmith',
    minRating: 400,
    resultAvailable: true,
    craftableCount: 4,
    buyCostCopper: 1_000,
    matsSellValueCopper: 500,
    revenueCopper: 9_000,
    profitCopper: 8_000,
    totalSellValueCopper: 36_000,
    totalProfitCopper: 32_000,
    blockedReason: 'NONE',
    outputPrice: { buyUnitCopper: 8_000, sellUnitCopper: 9_000 },
    missingToBuy: [],
    missingToBuyOne: [],
    iconUrl,
    ...overrides
  }
}

/** One row per image behaviour, so every case is on screen at once. */
const ROWS = [
  row(1, ITEMS.ok.id, 'Delivered Ingot', ITEMS.ok.url, {
    missingToBuy: [
      { itemId: ITEMS.okJpeg.id, itemName: 'Jpeg Material', quantity: 3, price: null, iconUrl: ITEMS.okJpeg.url },
      { itemId: ITEMS.none.id, itemName: 'Material Without Metadata', quantity: 1, price: null, iconUrl: null }
    ],
    missingToBuyOne: [
      { itemId: ITEMS.okJpeg.id, itemName: 'Jpeg Material', quantity: 1, price: null, iconUrl: ITEMS.okJpeg.url }
    ]
  }),
  row(2, ITEMS.unavailable.id, 'Unavailable Image Ring', ITEMS.unavailable.url),
  row(3, ITEMS.gone.id, 'Missing Image Plank', ITEMS.gone.url),
  row(4, ITEMS.undecodable.id, 'Undecodable Image Bolt', ITEMS.undecodable.url),
  row(5, ITEMS.slow.id, 'Slow Image Hammer', ITEMS.slow.url),
  row(6, ITEMS.none.id, 'No Metadata Blade', null)
]

function treeNode(itemId, itemName, iconUrl, children = []) {
  return {
    itemId,
    itemName,
    requestedQuantity: 2,
    inventoryQuantity: 0,
    craftedQuantity: 0,
    boughtQuantity: 2,
    missingQuantity: 0,
    recipeId: null,
    craftCount: 0,
    producedQuantity: 0,
    characterName: null,
    methods: ['BUY'],
    states: [],
    blockedReasons: [],
    cashCostCopper: 100,
    opportunityCostCopper: 0,
    effectiveCostCopper: 100,
    children,
    iconUrl
  }
}

/** Every image behaviour again, in the tree, with one item repeated in two branches. */
function treeFor(recipeId) {
  const source = ROWS.find((candidate) => candidate.recipeId === recipeId)
  return treeNode(source.outputItemId, source.outputName, source.iconUrl, [
    treeNode(ITEMS.okJpeg.id, 'Jpeg Material', ITEMS.okJpeg.url),
    treeNode(ITEMS.unavailable.id, 'Unavailable Material', ITEMS.unavailable.url),
    treeNode(ITEMS.none.id, 'Material Without Metadata', null, [
      // The same item as the first branch, and it must render its own icon rather than share one.
      treeNode(ITEMS.okJpeg.id, 'Jpeg Material', ITEMS.okJpeg.url)
    ])
  ])
}

const BANK = {
  slotCount: 5,
  slots: [
    { slot: 0, itemId: ITEMS.ok.id, count: 42, iconUrl: ITEMS.ok.url, rarity: 'Basic' },
    { slot: 1, itemId: null, count: null, iconUrl: null, rarity: null },
    { slot: 2, itemId: ITEMS.unavailable.id, count: 7, iconUrl: ITEMS.unavailable.url, rarity: 'Rare' },
    { slot: 3, itemId: null, count: null, iconUrl: null, rarity: null },
    { slot: 4, itemId: ITEMS.none.id, count: 250, iconUrl: null, rarity: 'Fine' }
  ]
}

const MATERIALS = {
  categoryCount: 1,
  categories: [
    {
      name: 'Controlled Materials',
      materials: [
        { category: 5, itemId: ITEMS.okJpeg.id, count: 3, iconUrl: ITEMS.okJpeg.url, rarity: 'Fine' },
        { category: 5, itemId: ITEMS.undecodable.id, count: 11, iconUrl: ITEMS.undecodable.url, rarity: null }
      ]
    }
  ]
}

/** Every image request this origin answered, by URL, so "no retry" is countable rather than assumed. */
const imageRequests = new Map()

function serveImage(response, pathname) {
  imageRequests.set(pathname, (imageRequests.get(pathname) ?? 0) + 1)
  const item = Object.values(ITEMS).find((candidate) => candidate.url === pathname)

  const sendImage = (bytes, type) => {
    response.writeHead(200, {
      'Content-Type': type,
      'Content-Length': bytes.length,
      // The real backend's success headers, so the browser treats these like the real ones.
      'Cache-Control': 'public, max-age=86400',
      'X-Content-Type-Options': 'nosniff',
      ETag: `"${pathname.length}-${bytes.length}"`
    })
    response.end(bytes)
  }

  if (item === undefined) {
    response.writeHead(404, { 'Cache-Control': 'no-store', 'Content-Type': 'application/json' })
    return response.end(JSON.stringify({ error: 'ICON_NOT_FOUND', message: 'No such icon.' }))
  }
  switch (item.behaviour) {
    case 'png':
      return sendImage(PNG_BYTES, 'image/png')
    case 'jpeg':
      return sendImage(JPEG_BYTES, 'image/jpeg')
    case 'garbage':
      return sendImage(UNDECODABLE_BYTES, 'image/png')
    case 'slow':
      return setTimeout(() => sendImage(PNG_BYTES, 'image/png'), SLOW_IMAGE_MS)
    case '404':
      response.writeHead(404, { 'Cache-Control': 'no-store', 'Content-Type': 'application/json' })
      return response.end(JSON.stringify({ error: 'ICON_NOT_FOUND', message: 'No such icon.' }))
    default:
      response.writeHead(503, {
        'Cache-Control': 'no-store',
        'Retry-After': '30',
        'Content-Type': 'application/json'
      })
      return response.end(JSON.stringify({ error: 'ICON_UNAVAILABLE', message: 'Try again later.' }))
  }
}

function answerApi({ response, url, body, sendJson }) {
  if (/^\/api\/items\/\d+\/icon\//.test(url.pathname)) return serveImage(response, url.pathname)

  if (url.pathname === '/api/crafting/selector-options') {
    return sendJson(200, {
      defaultScopeKind: 'ALL',
      disciplines: ['Armorsmith'],
      characterOptionCount: 0,
      characterOptions: []
    })
  }
  if (url.pathname === '/api/crafting/profit') {
    const request = body === '' ? {} : JSON.parse(body)
    return sendJson(200, {
      scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 },
      settings: request.settings ?? {
        useOwnMats: true,
        allowBuying: true,
        maxBuyCopper: 250_000,
        listingSell: false,
        listingBuy: false,
        dailyBuyInsteadOfCraft: true,
      },
      rowCount: ROWS.length,
      rows: ROWS
    })
  }
  if (url.pathname === '/api/crafting/profit/resolution') {
    const request = JSON.parse(body)
    const selected = ROWS.find((candidate) => candidate.recipeId === request.recipeId)
    if (selected === undefined) {
      return sendJson(404, { error: 'RECIPE_NOT_IN_CALCULATION', message: 'Not in this calculation.' })
    }
    return sendJson(200, {
      recipeId: request.recipeId,
      calculation: {
        scope: { kind: 'ALL', discipline: null, characterName: null, rating: 0 },
        settings: request.calculation?.settings ?? null
      },
      consistency: 'FRESH_CALCULATION',
      calculatedAt: '2026-09-27T09:00:00Z',
      row: selected,
      treeStatus: 'AVAILABLE',
      treeBasis: 'SINGLE_OUTPUT_REQUIREMENT',
      tree: treeFor(request.recipeId)
    })
  }
  if (url.pathname === '/api/account/bank') return sendJson(200, BANK)
  if (url.pathname === '/api/account/materials') return sendJson(200, MATERIALS)

  sendJson(404, { error: 'NOT_FOUND', message: url.pathname })
}

/** The state of every shared icon on screen, in document order. */
async function iconReport(page) {
  return page.$$eval('[data-test="item-icon"]', (icons) =>
    icons.map((icon) => {
      const image = icon.querySelector('img')
      const box = icon.getBoundingClientRect()
      return {
        state: icon.getAttribute('data-icon-state'),
        src: image?.getAttribute('src') ?? null,
        referrerPolicy: image?.getAttribute('referrerpolicy') ?? null,
        loading: image?.getAttribute('loading') ?? null,
        width: image?.getAttribute('width') ?? null,
        height: image?.getAttribute('height') ?? null,
        alt: image?.getAttribute('alt') ?? null,
        ariaHidden: icon.querySelector('[aria-hidden="true"]') !== null,
        complete: image?.complete ?? null,
        naturalWidth: image?.naturalWidth ?? null,
        boxWidth: Math.round(box.width),
        boxHeight: Math.round(box.height)
      }
    })
  )
}

/**
 * Waits until at most `expectedPending` of the images *in the viewport* are still loading.
 *
 * Deliberately scoped to the viewport: an offscreen `loading="lazy"` image is never fetched by the
 * browser at all, so its `complete` stays false forever and a whole-document wait could not finish.
 * That deferral is the behaviour 12.1 asks for — it is reported, not waited out.
 */
async function waitForSettledIcons(page, expectedPending = 0) {
  await page.waitForFunction(
    (pending) => {
      const images = [...document.querySelectorAll('[data-test="item-icon-image"]')]
      const inView = images.filter((image) => {
        const box = image.getBoundingClientRect()
        return box.bottom > 0 && box.top < window.innerHeight
      })
      return inView.filter((image) => !image.complete).length <= pending
    },
    expectedPending,
    { timeout: TIMEOUT_MS }
  )
}

async function pageOverflow(page) {
  return page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth
  }))
}

async function run() {
  check(existsSync(`${DIST_DIR}index.html`), 'frontend/dist is missing — run `npm run build` first.')

  const browserPath = resolveBrowserPath()
  const stub = await startStubOrigin({ port: PORT, distDir: DIST_DIR, answerApi })
  console.log(`Browser : ${browserPath}`)
  console.log(`Page    : ${stub.origin} (stub backend and stub images in this process)\n`)

  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage({ viewport: WIDE })

  /** Every request the browser issued, whatever its origin — the record AC 4 is asserted from. */
  const browserRequests = []
  page.on('request', (request) =>
    browserRequests.push({ url: request.url(), type: request.resourceType() })
  )
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))

  try {
    await page.goto(`${stub.origin}/#/crafting`, { waitUntil: 'load', timeout: TIMEOUT_MS })
    await page.waitForSelector('[data-test="profit-table"]', { timeout: TIMEOUT_MS })
    check(
      stub.servedCount() > 0,
      `The page at ${stub.origin} was not served by this script — stop whatever else is listening ` +
        'on that port. Nothing was checked.'
    )
    record('page served by this script', `${stub.servedCount()} requests answered so far`)

    // The images are the subject of every step below, so nothing is asserted until the browser has
    // actually issued their requests and resolved all but the one deliberately held back.
    await page.waitForSelector('[data-test="item-icon-image"]', { timeout: TIMEOUT_MS })
    await waitForSettledIcons(page, 1)

    // 1. Every request — page data and images alike — went to this application's own origin.
    const foreign = browserRequests.filter((request) => !request.url.startsWith(stub.origin))
    check(foreign.length === 0, `The browser left this origin: ${JSON.stringify(foreign.slice(0, 5))}`)
    const arenanet = browserRequests.filter((request) => /guildwars2\.com/i.test(request.url))
    check(arenanet.length === 0, `The browser requested ArenaNet: ${JSON.stringify(arenanet)}`)
    const imageCalls = browserRequests.filter((request) => request.type === 'image')
    check(imageCalls.length > 0, 'No image was requested at all, so nothing about delivery was checked.')
    const wrongPath = imageCalls.filter(
      (request) => !/\/api\/items\/\d+\/icon\/[0-9a-f]{64}\.(png|jpg)$/.test(new URL(request.url).pathname)
    )
    check(wrongPath.length === 0, `An image was requested off the icon route: ${JSON.stringify(wrongPath)}`)
    record(
      'only this application delivers images',
      `${imageCalls.length} image requests, all on /api/items/{id}/icon/{key}.{ext}; ` +
        '0 to ArenaNet, 0 to any other origin'
    )

    // 2. The delivery attributes of section 12.1, on the rows the table opened with.
    const rowIcons = await page.$$eval('[data-test="profit-row"] [data-test="item-icon"]', (icons) =>
      icons.map((icon) => ({
        state: icon.getAttribute('data-icon-state'),
        src: icon.querySelector('img')?.getAttribute('src') ?? null,
        loading: icon.querySelector('img')?.getAttribute('loading') ?? null,
        referrer: icon.querySelector('img')?.getAttribute('referrerpolicy') ?? null,
        width: icon.querySelector('img')?.getAttribute('width') ?? null
      }))
    )
    check(rowIcons.length === ROWS.length, `Expected one icon per row, got ${rowIcons.length}.`)
    // Row by row, in the supplied order: a usable image carries that row's own URL unchanged, and a
    // row whose image the origin refused carries no address at all rather than a repointed one.
    const expected = ROWS.map((candidate) => {
      const item = Object.values(ITEMS).find((known) => known.id === candidate.outputItemId)
      const delivers = item.behaviour === 'png' || item.behaviour === 'jpeg' || item.behaviour === 'slow'
      return { state: delivers ? 'image' : item.url === null ? 'no-url' : 'failed', src: delivers ? item.url : null }
    })
    const rendered = rowIcons.map((icon) => ({ state: icon.state, src: icon.src }))
    check(
      JSON.stringify(rendered) === JSON.stringify(expected),
      `The rendered image URLs are not the supplied ones:\n  rendered ${JSON.stringify(rendered)}\n` +
        `  expected ${JSON.stringify(expected)}`
    )
    const renderedUrls = rendered.map((icon) => icon.src).filter((src) => src !== null)
    check(
      rowIcons.every((icon) => icon.src === null || icon.referrer === 'no-referrer'),
      `An image was rendered without the no-referrer policy: ${JSON.stringify(rowIcons)}`
    )
    check(
      rowIcons.every((icon) => icon.src === null || (icon.loading === 'lazy' && icon.width === '20')),
      `A row image lost its lazy loading or its reserved width: ${JSON.stringify(rowIcons)}`
    )
    record(
      'row images use the supplied URL with 12.1’s delivery attributes',
      `${renderedUrls.length} URLs verbatim, no-referrer, reserved 20×20, native lazy loading`
    )

    // 3. Each failure ends in the one bundled fallback, and each failing URL was requested once.
    await page.waitForFunction(
      (urls) =>
        urls.every((url) => {
          const icon = [...document.querySelectorAll('[data-test="item-icon"]')].find(
            (candidate) => candidate.querySelector('img')?.getAttribute('src') === url
          )
          return icon === undefined
        }),
      [ITEMS.unavailable.url, ITEMS.gone.url, ITEMS.undecodable.url],
      { timeout: TIMEOUT_MS }
    )
    const afterFailures = await iconReport(page)
    const failedStates = afterFailures.filter((icon) => icon.state === 'failed')
    check(failedStates.length >= 3, `Expected the three failures to fall back: ${JSON.stringify(afterFailures)}`)
    check(
      failedStates.every((icon) => icon.src === null && icon.ariaHidden),
      `A fallback kept a broken image or announced itself: ${JSON.stringify(failedStates)}`
    )
    for (const failing of [ITEMS.unavailable.url, ITEMS.gone.url, ITEMS.undecodable.url]) {
      const answered = imageRequests.get(failing) ?? 0
      check(answered === 1, `${failing} was requested ${answered} times — a failure is not retried.`)
    }
    // The null-metadata cases never became a request at all.
    check(
      afterFailures.some((icon) => icon.state === 'no-url'),
      'No item exercised the null-metadata fallback, so that path is unchecked.'
    )
    record(
      'every unusable image ends in one fallback, requested once',
      `503, 404 and an undecodable body each asked once; ` +
        `${afterFailures.filter((icon) => icon.state === 'no-url').length} null-metadata items asked nothing`
    )

    // 4. The reserved box means a late image shifts nothing — the row text does not move.
    const before = await page.$$eval('[data-test="profit-row"] .recipe-name', (names) =>
      names.map((name) => {
        const box = name.getBoundingClientRect()
        return { text: name.textContent.trim(), x: Math.round(box.x), y: Math.round(box.y) }
      })
    )
    const slowIconState = (await iconReport(page)).find((icon) => icon.src === ITEMS.slow.url)
    check(
      slowIconState !== undefined && slowIconState.complete === false,
      'The slow image had already arrived, so no reservation was observed.'
    )
    check(
      slowIconState.boxWidth === 20 && slowIconState.boxHeight === 20,
      `The box was not reserved while the image was pending: ${JSON.stringify(slowIconState)}`
    )
    await waitForSettledIcons(page, 0)
    const after = await page.$$eval('[data-test="profit-row"] .recipe-name', (names) =>
      names.map((name) => {
        const box = name.getBoundingClientRect()
        return { text: name.textContent.trim(), x: Math.round(box.x), y: Math.round(box.y) }
      })
    )
    check(
      JSON.stringify(before) === JSON.stringify(after),
      `A late image moved the row text:\n  before ${JSON.stringify(before)}\n  after  ${JSON.stringify(after)}`
    )
    record(
      `a ${SLOW_IMAGE_MS}ms image shifts nothing`,
      `${after.length} row labels at identical positions before and after it arrived`
    )

    // 5. Selecting a row still works, and the detail and tree carry each item's own image.
    // Exact path: `requestsTo` matches by prefix, which would count the detail route as a table
    // calculation — two different contracts this check has to keep apart.
    const calculations = () => stub.requests.filter((request) => request.path === '/api/crafting/profit').length
    const calculationsBefore = calculations()
    await page.focus('[data-test="profit-table"] tbody [data-test="select-row"]')
    await page.keyboard.press('Enter')
    await page.waitForSelector('[data-test="tree-node"]', { timeout: TIMEOUT_MS })
    check(
      (await page.$eval('[data-test="detail-name"]', (el) => el.textContent.trim())) === ROWS[0].outputName,
      'Enter on a row with an image did not open that recipe.'
    )
    const detailIcon = await page.$eval('.detail__heading [data-test="item-icon"] img', (image) => ({
      src: image.getAttribute('src'),
      loading: image.getAttribute('loading'),
      width: image.getAttribute('width')
    }))
    check(
      detailIcon.src === ITEMS.ok.url && detailIcon.loading === 'eager' && detailIcon.width === '32',
      `The detail image is not the output item's, loaded promptly: ${JSON.stringify(detailIcon)}`
    )
    const treeIcons = await page.$$eval('[data-test="tree-node"] .node__identity', (nodes) =>
      nodes.map((node) => ({
        name: node.querySelector('[data-test="node-name"]').textContent.trim(),
        state: node.querySelector('[data-test="item-icon"]').getAttribute('data-icon-state'),
        src: node.querySelector('img')?.getAttribute('src') ?? null
      }))
    )
    const repeated = treeIcons.filter((icon) => icon.name === 'Jpeg Material')
    check(repeated.length === 2, `Expected the repeated item twice in the tree: ${JSON.stringify(treeIcons)}`)
    check(
      repeated.every((icon) => icon.src === ITEMS.okJpeg.url),
      `A repeated item did not render its own icon: ${JSON.stringify(repeated)}`
    )
    check(
      treeIcons.some((icon) => icon.name === 'Material Without Metadata' && icon.state === 'no-url'),
      `A node without metadata did not use the fallback: ${JSON.stringify(treeIcons)}`
    )
    const materialIcons = await page.$$eval('[data-test="missing-item"] img', (images) =>
      images.map((image) => image.getAttribute('src'))
    )
    check(
      JSON.stringify(materialIcons) === JSON.stringify([ITEMS.okJpeg.url]),
      `The materials-to-buy list did not use each material's own icon: ${JSON.stringify(materialIcons)}`
    )
    check(
      calculations() === calculationsBefore,
      `Rendering images submitted a calculation: ${calculationsBefore} before, ${calculations()} after.`
    )
    record(
      'detail, tree and material images are each item’s own',
      `detail eager at 32px, ${treeIcons.length} tree nodes including the repeated item twice, ` +
        'no calculation triggered'
    )

    // 6. Narrow viewport: nothing overflows and the text still holds its line.
    await page.setViewportSize(NARROW)
    await waitForSettledIcons(page, 0)
    const narrow = await pageOverflow(page)
    check(
      narrow.scrollWidth <= narrow.clientWidth + 1,
      `The page scrolls horizontally at ${NARROW.width}px (${narrow.scrollWidth} > ${narrow.clientWidth}).`
    )
    const narrowIcons = await iconReport(page)
    check(
      narrowIcons.every((icon) => icon.boxWidth === icon.boxHeight && icon.boxWidth > 0),
      `An icon box collapsed or stretched at ${NARROW.width}px: ${JSON.stringify(narrowIcons.slice(0, 4))}`
    )
    record(
      `layout holds at ${NARROW.width}×${NARROW.height}`,
      `${narrowIcons.length} square icon boxes, no horizontal page overflow`
    )
    await page.setViewportSize(WIDE)

    // 7. Bank and Materials: empty slots stay empty and item text survives every image failure.
    await page.click('[data-test="nav-bank"]')
    await page.waitForSelector('[data-test="bank-slots"]', { timeout: TIMEOUT_MS })
    const slots = await page.$$eval('[data-test="bank-slot"]', (elements) =>
      elements.map((slot) => ({
        empty: slot.querySelector('[data-test="bank-empty-slot"]') !== null,
        icons: slot.querySelectorAll('[data-test="item-icon"]').length,
        identity: slot.querySelector('[data-test="item-identity"]')?.textContent.trim() ?? null,
        count: slot.querySelector('[data-test="item-count"]')?.textContent.trim() ?? null,
        rarity: slot.querySelector('[data-test="item-rarity"]')?.textContent.trim() ?? null
      }))
    )
    check(
      slots.filter((slot) => slot.empty).every((slot) => slot.icons === 0),
      `An empty bank slot was given an icon: ${JSON.stringify(slots)}`
    )
    check(
      slots.filter((slot) => !slot.empty).every((slot) => slot.icons === 1 && slot.identity !== null),
      `An occupied slot lost its icon or its identity: ${JSON.stringify(slots)}`
    )
    const unavailableSlot = slots.find((slot) => slot.identity === `#${ITEMS.unavailable.id}`)
    check(
      unavailableSlot.count === '× 7' && unavailableSlot.rarity === 'Rare',
      `The slot whose image is unavailable lost its text: ${JSON.stringify(unavailableSlot)}`
    )
    record(
      'bank keeps empty slots empty and text intact',
      `${slots.filter((s) => s.empty).length} empty slots with no icon, ` +
        `${slots.filter((s) => !s.empty).length} occupied with one each`
    )

    await page.click('[data-test="nav-materials"]')
    await page.waitForSelector('[data-test="material-stack"]', { timeout: TIMEOUT_MS })
    await waitForSettledIcons(page, 0)
    const stacks = await page.$$eval('[data-test="material-stack"]', (elements) =>
      elements.map((stack) => ({
        state: stack.querySelector('[data-test="item-icon"]').getAttribute('data-icon-state'),
        identity: stack.querySelector('[data-test="item-identity"]').textContent.trim(),
        count: stack.querySelector('[data-test="item-count"]').textContent.trim()
      }))
    )
    check(
      stacks.some((stack) => stack.state === 'image') && stacks.some((stack) => stack.state === 'failed'),
      `Materials did not show both a delivered and a failed image: ${JSON.stringify(stacks)}`
    )
    check(
      stacks.every((stack) => stack.identity.startsWith('#') && stack.count.startsWith('×')),
      `A material stack lost its id or count: ${JSON.stringify(stacks)}`
    )
    record('materials keep id and count whatever the image did', JSON.stringify(stacks))

    // 8. Nothing left this origin at any point, and no image touched a JSON route.
    const late = browserRequests.filter((request) => !request.url.startsWith(stub.origin))
    check(late.length === 0, `The browser left this origin later on: ${JSON.stringify(late.slice(0, 5))}`)
    const jsonFromImages = browserRequests.filter(
      (request) => request.type === 'image' && !request.url.includes('/icon/')
    )
    check(jsonFromImages.length === 0, `An image request went somewhere else: ${JSON.stringify(jsonFromImages)}`)
    check(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`)
    // The 503 item is shown on three surfaces during this run — a profit row, a tree node and a bank
    // slot — and each of them asked for it exactly once. Three requests for three renderings is the
    // opposite of a retry loop: no element ever asked twice.
    const unavailableAsked = imageRequests.get(ITEMS.unavailable.url) ?? 0
    check(
      unavailableAsked === 3,
      `The unavailable image was requested ${unavailableAsked} times for the 3 places it appears — ` +
        'either a rendering was added or something retried.'
    )
    record(
      'no request ever left this application',
      `${browserRequests.length} browser requests, all to ${stub.origin}; no page error`
    )

    console.log(`\nIcon browser smoke PASSED (${steps.length} steps).`)
    console.log(
      'Image requests answered: ' +
        [...imageRequests.entries()].map(([url, count]) => `${url.slice(0, 44)}… ×${count}`).join('\n                         ')
    )
  } finally {
    await browser.close()
    stub.close()
  }
}

run().catch((error) => {
  console.error(`\nIcon browser smoke FAILED after ${steps.length} step(s): ${error.message}`)
  process.exitCode = 1
})
