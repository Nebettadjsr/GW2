/**
 * Real-browser check of the replaceable browser favicon (STORY-WEB-009, `Request-004` item 1).
 *
 * Establishes the whole delivery chain of one static asset: `public/favicon.ico` is copied verbatim
 * into the build output, the built document references it through the build's base path, and a real
 * browser loads those exact bytes from that URL and decodes them as an image. Replacing the icon is
 * therefore replacing that one file — nothing in the application source names its contents.
 *
 * The API boundary here is a `scripts/stubOrigin.mjs` origin that answers every `/api/` call 404 on
 * purpose: this check is about a static asset and must not be read as evidence about any screen's
 * data, navigation state or performance. `smoke:icons`, `smoke:ecto` and the other checks own that.
 * What it does assert about the application is that the icon needs no `/api/` route and that the
 * document sends nothing off its own origin to obtain it.
 *
 * Usage:  npm run build && npm run smoke:favicon
 * Environment:
 *   GW2_FAVICON_SMOKE_PORT  port for the stub origin  (default 5186)
 *   GW2_BROWSER_PATH        browser executable        (default: the first installed Chrome/Edge)
 *   GW2_SMOKE_TIMEOUT_MS    per-step wait             (default 30000)
 *
 * Exits 0 when every step passed, 1 otherwise.
 */
import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { requireFreshBundle } from './bundleFreshness.mjs'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startStubOrigin } from './stubOrigin.mjs'

const PORT = Number(process.env.GW2_FAVICON_SMOKE_PORT ?? 5186)
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 30_000)
const FRONTEND_DIR = fileURLToPath(new URL('../', import.meta.url))
const DIST_DIR = `${FRONTEND_DIR}dist/`

/** The documented replaceable file and the URL the document must ask for. */
const SOURCE_FILE = `${FRONTEND_DIR}public/favicon.ico`
const BUILT_FILE = `${DIST_DIR}favicon.ico`
const ICON_PATH = '/favicon.ico'

const steps = []

function record(name, detail) {
  steps.push({ name, detail })
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

function check(condition, message) {
  if (!condition) throw new Error(message)
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

/** Nothing here needs the backend; a 404 keeps an accidental data assertion from ever passing. */
function answerApi({ url, sendJson }) {
  sendJson(404, { error: 'NOT_FOUND', message: `${url.pathname} is not served by the favicon check` })
}

async function run() {
  const bundle = requireFreshBundle()
  check(existsSync(SOURCE_FILE), `The replaceable favicon is missing: ${SOURCE_FILE}`)

  // 1. The build copied the replaceable file itself, not a processed or renamed derivative.
  check(existsSync(BUILT_FILE), `\`npm run build\` did not emit ${BUILT_FILE}.`)
  const sourceBytes = readFileSync(SOURCE_FILE)
  const builtBytes = readFileSync(BUILT_FILE)
  const sourceDigest = sha256(sourceBytes)
  check(
    sha256(builtBytes) === sourceDigest,
    'dist/favicon.ico is not the bytes of public/favicon.ico — the documented file is not the one served.'
  )
  check(
    sourceBytes.length > 0 && sourceBytes.readUInt32LE(0) === 0x00_01_00_00,
    'public/favicon.ico does not start with the ICO signature 00 00 01 00.'
  )
  record(
    'public/favicon.ico is the built asset',
    `${sourceBytes.length} bytes, sha256 ${sourceDigest.slice(0, 12)}…`
  )

  // 2. The emitted document references it through the base path, with nothing left unresolved.
  const builtHtml = readFileSync(`${DIST_DIR}index.html`, 'utf8')
  const iconLinks = [...builtHtml.matchAll(/<link[^>]*rel="icon"[^>]*>/g)].map((match) => match[0])
  check(iconLinks.length === 1, `The built document has ${iconLinks.length} rel="icon" links, not 1.`)
  check(!/%BASE_URL%/.test(builtHtml), 'The built document still contains an unresolved %BASE_URL%.')
  const href = /href="([^"]+)"/.exec(iconLinks[0])?.[1]
  check(href === ICON_PATH, `The built icon href is ${href}, not the base-path form of ${ICON_PATH}.`)
  record('built document references the asset', iconLinks[0])

  const browserPath = resolveBrowserPath()
  const origin = await startStubOrigin({ port: PORT, distDir: DIST_DIR, answerApi })
  console.log(`Browser : ${browserPath}`)
  console.log(`Bundle  : ${bundle}`)
  console.log(`Page    : ${origin.origin} (every /api/ call answered 404 by this process)\n`)

  const browser = await chromium.launch({ executablePath: browserPath })
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))
  const browserRequests = []
  page.on('request', (request) =>
    browserRequests.push({ url: request.url(), type: request.resourceType() })
  )

  try {
    // 3. The page really came from this process, not from something else on the port.
    await page.goto(origin.origin, { waitUntil: 'domcontentloaded', timeout: TIMEOUT_MS })
    await page.waitForSelector('[data-test="screen-nav"]', { timeout: TIMEOUT_MS })
    check(
      origin.servedCount() > 0,
      `The page at ${origin.origin} was not served by this script — stop whatever else is listening ` +
        'on that port. Nothing was checked.'
    )
    record('page served by this script', `${origin.servedCount()} requests answered so far`)

    // 4. The loaded document — not the source file — points one icon link at this origin's asset.
    const linked = await page.$$eval('link[rel~="icon"]', (links) =>
      links.map((link) => ({ rel: link.getAttribute('rel'), type: link.type, href: link.href }))
    )
    check(linked.length === 1, `The loaded document exposes ${linked.length} icon links: ${JSON.stringify(linked)}`)
    check(
      linked[0].href === `${origin.origin}${ICON_PATH}`,
      `The document resolved its icon to ${linked[0].href}, not to ${origin.origin}${ICON_PATH}.`
    )
    record('document resolves one icon URL', `${linked[0].rel} (${linked[0].type}) → ${linked[0].href}`)

    // 5. A real browser request for that URL: the response status, media type and the bytes it got.
    // Counted on the server side, because Chromium attributes neither its own tab-icon load nor this
    // fetch to the page — `page.on('request')` reports no favicon request even when the origin serves
    // one, so a page-event count here would read as zero and prove nothing.
    const servedBeforeFetch = origin.servedCount()
    const fetched = await page.evaluate(async (path) => {
      const response = await fetch(path, { cache: 'no-store' })
      const bytes = new Uint8Array(await response.arrayBuffer())
      const digest = await crypto.subtle.digest('SHA-256', bytes)
      return {
        status: response.status,
        contentType: response.headers.get('content-type'),
        length: bytes.byteLength,
        digest: [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
      }
    }, ICON_PATH)
    const servedByFetch = origin.servedCount() - servedBeforeFetch
    check(
      servedByFetch >= 1,
      `The browser answered its own ${ICON_PATH} request from its cache — this origin served ` +
        'nothing, so no request was checked.'
    )
    check(fetched.status === 200, `The browser got HTTP ${fetched.status} for ${ICON_PATH}.`)
    check(
      /^image\//.test(fetched.contentType ?? ''),
      `${ICON_PATH} was served as ${fetched.contentType}, not as an image media type.`
    )
    check(
      fetched.length === sourceBytes.length && fetched.digest === sourceDigest,
      `The browser received ${fetched.length} bytes (sha256 ${fetched.digest.slice(0, 12)}…), not the ` +
        `${sourceBytes.length} bytes of public/favicon.ico (sha256 ${sourceDigest.slice(0, 12)}…).`
    )
    record(
      'browser loaded the asset from that URL',
      `HTTP ${fetched.status}, ${fetched.contentType}, ${fetched.length} bytes identical to ` +
        `public/favicon.ico, served ${servedByFetch}× by this origin`
    )

    // 6. Those bytes are a usable image, so the placeholder is valid rather than merely present.
    const decoded = await page.evaluate(async (path) => {
      const image = new Image()
      image.src = path
      await image.decode()
      return { width: image.naturalWidth, height: image.naturalHeight }
    }, ICON_PATH)
    check(
      decoded.width > 0 && decoded.height > 0,
      `The browser could not decode ${ICON_PATH} into an image: ${JSON.stringify(decoded)}`
    )
    record('the served icon decodes as an image', `${decoded.width}×${decoded.height}`)

    // 7. Navigation still works, and the icon is a static asset: no /api/ route is involved in it.
    const navigated = []
    for (const id of ['discovery', 'ecto', 'synchronization', 'bank', 'materials', 'crafting']) {
      await page.click(`[data-test="nav-${id}"]`)
      await page.waitForFunction((hash) => window.location.hash === hash, `#/${id}`, {
        timeout: TIMEOUT_MS
      })
      navigated.push(id)
    }
    check(
      origin.requestsTo('/api/').every((request) => !request.path.includes('favicon')),
      `An /api/ route was asked for the icon: ${JSON.stringify(origin.requestsTo('/api/'))}`
    )
    const foreign = browserRequests.filter((request) => !request.url.startsWith(origin.origin))
    check(foreign.length === 0, `The browser left this origin: ${JSON.stringify(foreign.slice(0, 5))}`)
    check(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`)
    record(
      'navigation unaffected, icon needs no API route',
      `${navigated.length} destinations opened; ${browserRequests.length} page requests and ` +
        `${origin.requestsTo('/api/').length} /api/ calls, all to this origin`
    )

    console.log(`\nFavicon browser smoke PASSED (${steps.length} steps).`)
  } finally {
    await browser.close()
    origin.close()
  }
}

run().catch((error) => {
  console.error(`\nFavicon browser smoke FAILED after ${steps.length} step(s): ${error.message}`)
  process.exitCode = 1
})
