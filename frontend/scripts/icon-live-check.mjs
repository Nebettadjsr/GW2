/**
 * Live integration evidence for the shared item image (`STORY-WEB-010`, `TARGET_ARCHITECTURE.md`
 * 12.1) — a real browser, the real backend, the real user database and real ArenaNet images.
 *
 * Nothing here is a fixture. What it establishes, and how:
 *
 *   - **Only this application delivers images.** Every request the browser issues is recorded, and
 *     every one of them has to be on the page's own origin and, for images, on
 *     `/api/items/{id}/icon/{key}.{ext}`. Not one request to ArenaNet.
 *   - **Development routing forwards image paths.** The page is served by the Vite dev server, which
 *     proxies `/api` to a recording proxy in this process, which forwards to the backend. An image
 *     appearing in those records *is* the proof that the dev server forwarded it.
 *   - **Browser caching is observed, not inferred.** The reload phase asserts the backend saw no
 *     image request at all, and the revalidation phase forces a conditional reload and asserts the
 *     backend answered 304.
 *   - **Upstream requests are counted.** The backend runs behind `scripts/upstream-proxy.mjs`, so
 *     "zero upstream image requests" is a line count in that proxy's log and not a guess.
 *
 * It measures nothing: no timing here is an `TARGET_ARCHITECTURE.md` §33 claim. That is
 * `profit-page-perf.mjs`.
 *
 * Phases (run one per invocation, because the backend restart between them is external):
 *   pre-restart     cold browser profile → first delivery, warm-browser reload, forced revalidation
 *   post-restart    fresh cold browser profile against a backend restarted over the same storage
 *   upstream-down   the same, with the upstream proxy refusing every tunnel
 *
 * Environment:
 *   GW2_LIVE_PAGE_URL        the page origin (a dev server that proxies /api here)
 *   GW2_LIVE_RECORDER_PORT   port this script's recording proxy listens on
 *   GW2_LIVE_BACKEND_ORIGIN  the backend the recorder forwards to
 *   GW2_LIVE_PROFILE_DIR     the browser profile directory — an isolated, disposable location, never
 *                            the user's own browser profile or cache
 *   GW2_LIVE_UPSTREAM_LOG    the upstream proxy's log file, read to count CONNECT tunnels
 *   GW2_BROWSER_PATH         browser executable
 *
 * Exits 0 when every step passed, 1 otherwise. It reads no credential and holds none.
 */
import { existsSync, readFileSync, rmSync } from 'node:fs'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startRecordingProxy } from './recordingProxy.mjs'

const PHASE = process.argv[2] ?? 'pre-restart'
const PAGE_URL = process.env.GW2_LIVE_PAGE_URL ?? 'http://127.0.0.1:5178'
const RECORDER_PORT = Number(process.env.GW2_LIVE_RECORDER_PORT ?? 8199)
const BACKEND_ORIGIN = process.env.GW2_LIVE_BACKEND_ORIGIN ?? 'http://127.0.0.1:8091'
const PROFILE_DIR = process.env.GW2_LIVE_PROFILE_DIR
const UPSTREAM_LOG = process.env.GW2_LIVE_UPSTREAM_LOG
const TIMEOUT_MS = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 60_000)

const ICON_ROUTE = /^\/api\/items\/\d+\/icon\/[0-9a-f]{64}\.(png|jpg)$/

const steps = []

function record(name, detail) {
  steps.push({ name, detail })
  console.log(`  ok   ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

function check(condition, message) {
  if (!condition) throw new Error(message)
}

/** CONNECT tunnels the backend has opened to ArenaNet so far, counted in the proxy's own log. */
function upstreamTunnels() {
  if (UPSTREAM_LOG === undefined || !existsSync(UPSTREAM_LOG)) return null
  return readFileSync(UPSTREAM_LOG, 'utf8').split('\n').filter((line) => line.startsWith('CONNECT')).length
}

/** Icons in the viewport that are still loading; offscreen lazy images are never fetched at all. */
async function waitForVisibleIcons(page) {
  await page.waitForFunction(
    () =>
      [...document.querySelectorAll('[data-test="item-icon-image"]')]
        .filter((image) => {
          const box = image.getBoundingClientRect()
          return box.bottom > 0 && box.top < window.innerHeight
        })
        .every((image) => image.complete),
    undefined,
    { timeout: TIMEOUT_MS }
  )
}

/**
 * What every icon on the page is doing.
 *
 * `pending` is deliberately split: an image in the viewport that has not finished is a real gap,
 * while `deferred` counts the offscreen entries the browser has not fetched because they carry
 * `loading="lazy"` — the behaviour 12.1 asks for. Both are reported rather than one hiding the
 * other.
 */
async function iconStates(page) {
  return page.$$eval('[data-test="item-icon"]', (icons) => {
    const report = { image: 0, 'no-url': 0, failed: 0, pending: 0, deferred: 0, decoded: 0 }
    for (const icon of icons) {
      report[icon.getAttribute('data-icon-state')] += 1
      const image = icon.querySelector('img')
      if (image === null) continue
      const box = image.getBoundingClientRect()
      const inView = box.bottom > 0 && box.top < window.innerHeight
      if (image.complete) {
        if (image.naturalWidth > 0) report.decoded += 1
      } else if (inView) report.pending += 1
      else report.deferred += 1
    }
    return report
  })
}

async function openBank(page) {
  await page.goto(`${PAGE_URL}/#/bank`, { waitUntil: 'domcontentloaded', timeout: TIMEOUT_MS })
  await page.waitForSelector('[data-test="bank-slots"]', { timeout: TIMEOUT_MS })
  await waitForVisibleIcons(page)
}

async function run() {
  check(PROFILE_DIR !== undefined, 'GW2_LIVE_PROFILE_DIR must name a disposable browser profile directory.')
  const browserPath = resolveBrowserPath()
  const recorder = await startRecordingProxy({ port: RECORDER_PORT, backendOrigin: BACKEND_ORIGIN })

  console.log(`Phase   : ${PHASE}`)
  console.log(`Browser : ${browserPath}`)
  console.log(`Page    : ${PAGE_URL} (dev server → ${recorder.origin} → ${BACKEND_ORIGIN})`)
  console.log(`Profile : ${PROFILE_DIR}`)
  console.log(`Upstream: ${UPSTREAM_LOG ?? '(not counted)'}\n`)

  // A cold browser cache is a *fresh, isolated* profile directory, created here and thrown away —
  // never the user's own browser storage, which this check must not touch (TEST_STRATEGY §34).
  if (PHASE !== 'warm-profile') rmSync(PROFILE_DIR, { recursive: true, force: true })

  const context = await chromium.launchPersistentContext(PROFILE_DIR, {
    executablePath: browserPath,
    viewport: { width: 1440, height: 900 }
  })
  const page = context.pages()[0] ?? (await context.newPage())

  const browserRequests = []
  page.on('request', (request) =>
    browserRequests.push({ url: request.url(), type: request.resourceType() })
  )
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(String(error)))

  const tunnelsAtStart = upstreamTunnels()

  try {
    // ------------------------------------------------------------ first opening
    const firstMark = recorder.mark()
    await openBank(page)

    const backendFirst = recorder.since(firstMark)
    check(
      backendFirst.some((entry) => entry.path === '/api/account/bank'),
      'The page did not reach the backend through the recording proxy at all — the dev server is ' +
        'pointed somewhere else, so nothing here was measured.'
    )
    const imagesFirst = recorder.imagesSince(firstMark)
    check(
      imagesFirst.length > 0,
      'The backend was never asked for an image. Either no bank item has retained metadata, or the ' +
        'dev server is not forwarding /api/items — this check proves nothing in that state.'
    )
    record(
      'development routing forwards image paths to the backend',
      `${imagesFirst.length} image requests arrived through the dev server's /api proxy`
    )

    const upstreamRequests = browserRequests.filter((request) => /guildwars2\.com/i.test(request.url))
    check(upstreamRequests.length === 0, `The browser requested ArenaNet: ${JSON.stringify(upstreamRequests)}`)
    const offOrigin = browserRequests.filter((request) => !request.url.startsWith(new URL(PAGE_URL).origin))
    check(offOrigin.length === 0, `The browser left this origin: ${JSON.stringify(offOrigin.slice(0, 5))}`)
    const browserImages = browserRequests.filter((request) => request.type === 'image')
    const offRoute = browserImages.filter((request) => !ICON_ROUTE.test(new URL(request.url).pathname))
    check(offRoute.length === 0, `An image was requested off the icon route: ${JSON.stringify(offRoute)}`)
    record(
      'the browser asked only this application for images',
      `${browserImages.length} image requests, 0 to ArenaNet, 0 to any other origin`
    )

    const served = imagesFirst.filter((entry) => entry.status === 200)
    const refused = imagesFirst.filter((entry) => entry.status !== 200)
    check(
      served.every(
        (entry) =>
          entry.cacheControl === 'max-age=86400, public' &&
          entry.etag !== null &&
          /^image\/(png|jpeg)$/.test(entry.contentType ?? '')
      ),
      `A delivered image did not carry 12.1's headers: ${JSON.stringify(served.slice(0, 3))}`
    )
    const states = await iconStates(page)
    check(states.pending === 0, `${states.pending} visible images never finished loading.`)
    record(
      'real backend images arrive with the documented headers',
      `${served.length} delivered (public, max-age=86400, strong ETag, verified type), ` +
        `${refused.length} refused; on screen: ${states.image} images (${states.decoded} decoded, ` +
        `${states.deferred} offscreen and still deferred), ${states['no-url']} without metadata, ` +
        `${states.failed} failed`
    )

    const tunnelsAfterFirst = upstreamTunnels()
    console.log(
      `  info upstream CONNECT tunnels: ${tunnelsAtStart} before this phase, ${tunnelsAfterFirst} after ` +
        'the first opening'
    )

    if (PHASE === 'pre-restart') {
      // ------------------------------------------------- warm browser cache
      // An *ordinary navigation*, not a reload: a reload is a deliberate instruction to revalidate,
      // so it would say nothing about whether the cache is used when a person simply opens the page
      // again. A second tab in the same profile shares that profile's HTTP cache.
      const secondPage = await context.newPage()
      secondPage.on('request', (request) =>
        browserRequests.push({ url: request.url(), type: request.resourceType() })
      )
      const reopenMark = recorder.mark()
      await secondPage.goto(`${PAGE_URL}/#/bank`, { waitUntil: 'domcontentloaded', timeout: TIMEOUT_MS })
      await secondPage.waitForSelector('[data-test="bank-slots"]', { timeout: TIMEOUT_MS })
      await waitForVisibleIcons(secondPage)
      // Not "no request at all": lazy loading means the second opening can bring a *different*
      // offscreen image into loading distance, and that one has never been fetched. The claim is
      // the exact one — nothing the browser already holds is asked for again.
      const alreadyDelivered = new Set(imagesFirst.map((entry) => entry.path))
      const imagesOnReopen = recorder.imagesSince(reopenMark)
      const askedAgain = imagesOnReopen.filter((entry) => alreadyDelivered.has(entry.path))
      check(
        askedAgain.length === 0,
        `${askedAgain.length} images the browser already had were fetched again inside the ` +
          `freshness window: ${JSON.stringify(askedAgain.slice(0, 3))}`
      )
      const reopenedStates = await iconStates(secondPage)
      check(
        reopenedStates.image > 0 && reopenedStates.pending === 0,
        `The reopened page did not show its images: ${JSON.stringify(reopenedStates)}`
      )
      record(
        'reopening the page reuses the browser’s own cache',
        `${reopenedStates.image} images on screen; of ${alreadyDelivered.size} already delivered, ` +
          `0 were requested again; ${imagesOnReopen.length} first-time requests for images lazy ` +
          'loading had deferred'
      )

      // -------------------------------------------- revalidation (304)
      // Chrome will not revalidate a *fresh* entry — inside the one-day window a reload, hard or not,
      // either serves the stored copy or re-downloads it, so neither one exercises the 304 path. The
      // browser's own "validate this with the server" mode does: `fetch(url, {cache: 'no-cache'})`
      // sends the stored ETag as `If-None-Match` and uses the local body if the server agrees.
      //
      // What this establishes is precise: the validator the *browser* stored, sent by the browser,
      // answered 304 by the backend from its own local copy, with the body still usable and no
      // upstream access. What it does not establish is the passage of a day — the stale-entry path
      // this same mechanism serves was not waited out, and this run does not claim it was.
      const revalidateMark = recorder.mark()
      const tunnelsBeforeRevalidation = upstreamTunnels()
      const toRevalidate = [...alreadyDelivered].slice(0, 12)
      const revalidationResults = await page.evaluate(async (paths) => {
        const results = []
        for (const path of paths) {
          const response = await fetch(path, { cache: 'no-cache' })
          const bytes = await response.arrayBuffer()
          results.push({
            path,
            status: response.status,
            type: response.headers.get('content-type'),
            cacheControl: response.headers.get('cache-control'),
            etag: response.headers.get('etag'),
            bytes: bytes.byteLength
          })
        }
        return results
      }, toRevalidate)

      const revalidated = recorder.imagesSince(revalidateMark)
      const conditional = revalidated.filter((entry) => entry.conditional && entry.status === 304)
      const redownloaded = revalidated.filter(
        (entry) => !entry.conditional && alreadyDelivered.has(entry.path)
      )
      check(
        conditional.length === toRevalidate.length,
        `${conditional.length} of ${toRevalidate.length} revalidations reached the backend as ` +
          `If-None-Match → 304: ${JSON.stringify(revalidated.slice(0, 3))}`
      )
      check(
        redownloaded.length === 0,
        `${redownloaded.length} already-cached images were re-downloaded instead of revalidated: ` +
          JSON.stringify(redownloaded.slice(0, 3))
      )
      // The 304 kept Cache-Control and the ETag (§12.1), and the browser still produced the bytes.
      check(
        revalidationResults.every(
          (result) =>
            result.status === 200 &&
            result.bytes > 0 &&
            /^image\/(png|jpeg)$/.test(result.type ?? '') &&
            result.cacheControl === 'max-age=86400, public' &&
            result.etag !== null
        ),
        `A revalidated image did not come back usable with its headers intact: ${JSON.stringify(
          revalidationResults.slice(0, 3)
        )}`
      )
      check(
        upstreamTunnels() === tunnelsBeforeRevalidation,
        'Revalidating against the local copy opened an upstream tunnel.'
      )
      const revalidatedStates = await iconStates(page)
      check(
        revalidatedStates.pending === 0 && revalidatedStates.image > 0,
        `Images did not survive revalidation: ${JSON.stringify(revalidatedStates)}`
      )
      record(
        'the browser’s stored validator revalidates against the backend’s local copy',
        `${conditional.length} of ${toRevalidate.length} were If-None-Match → 304 with Cache-Control ` +
          `and ETag retained, ${revalidationResults[0]?.bytes} bytes still delivered to the page, ` +
          `0 re-downloaded, 0 upstream tunnels; ${revalidatedStates.image} images still on screen`
      )
      await secondPage.close()
    }

    if (PHASE === 'post-restart' || PHASE === 'upstream-down') {
      const tunnelsUsed = tunnelsAfterFirst === null ? null : tunnelsAfterFirst - tunnelsAtStart
      check(
        tunnelsUsed === 0,
        `The backend opened ${tunnelsUsed} upstream tunnel(s) while serving a warm cache — a stored ` +
          'entry must be served without any upstream access.'
      )
      check(
        states.image > 0 && states.failed === 0,
        `Warm storage did not deliver every image: ${JSON.stringify(states)}`
      )
      record(
        PHASE === 'post-restart'
          ? 'a restarted backend reuses its stored images'
          : 'stored images are delivered while upstream is unavailable',
        `${served.length} images served, ${tunnelsUsed} upstream tunnels opened`
      )
    }

    check(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`)

    console.log(`\nIcon live check (${PHASE}) PASSED (${steps.length} steps).`)
    console.log(
      `Backend requests recorded: ${recorder.records.length} ` +
        `(${recorder.records.filter((entry) => ICON_ROUTE.test(entry.path)).length} on the icon route)`
    )
    console.log(`Upstream CONNECT tunnels: ${tunnelsAtStart} → ${upstreamTunnels()}`)
  } finally {
    await context.close()
    recorder.close()
  }
}

run().catch((error) => {
  console.error(`\nIcon live check (${PHASE}) FAILED after ${steps.length} step(s): ${error.message}`)
  process.exitCode = 1
})
