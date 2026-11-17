/** #243: production App journeys, controlled HTTP only; not instrumented source-code coverage. */
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { setTimeout as pause } from 'node:timers/promises'
import { chromium, selectors } from 'playwright-core'
import { requireFreshBundle } from './bundleFreshness.mjs'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startStubOrigin } from './stubOrigin.mjs'
import { createJourneyBackend, KEY_A, KEY_B, BAD_KEY, ACCOUNT_A, ACCOUNT_B, storageKey } from './userJourneyFixtures.mjs'

selectors.setTestIdAttribute('data-test')
const OUTPUT = resolve(process.env.GW2_SMOKE_OUTPUT ?? 'quality-reports/desktop-journeys')
const TIMEOUT = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 15_000)
const WIDE = { width: 1440, height: 900 }
const results = []
let browser
process.on('message', async message => {
  if (message?.type !== 'gw2-journey-timeout') return
  process.exitCode = 1
  await browser?.close().catch(() => {}) // Cancels waits, then each active journey's finally closes its origin.
})
process.channel?.unref()

async function waitFor(predicate, message) {
  const end = Date.now() + TIMEOUT
  while (!predicate()) {
    assert.ok(Date.now() < end, message)
    await pause(10)
  }
}
const get = (page, id) => page.getByTestId(id)
const count = (api, path) => api.calls.filter(call => call.path === path).length
async function go(page, id) { await get(page, `nav-${id}`).click() }
async function connect(page, key) {
  await get(page, 'api-key-input').fill(key)
  await get(page, 'connect').click()
  await get(page, 'disconnect').waitFor()
}
async function quietFrame(page) {
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
}
async function focusIs(locator) { assert.equal(await locator.evaluate(node => document.activeElement === node), true) }
async function notice(page) {
  const region = get(page, 'desktop-width-notice')
  await region.waitFor()
  assert.match(await region.innerText(), /Please use a PC or a wider browser window/)
  assert.match(await region.innerText(), /1024/)
  assert.ok(await region.locator('h1, h2').count(), 'Notice has no heading')
  assert.ok(await region.getByRole('link', { name: /Home/i }).count(), 'Notice has no public Home link')
  const role = await region.getAttribute('role')
  assert.ok(['region', 'status', 'note'].includes(role) || await region.getAttribute('aria-labelledby'), 'Notice is not an accessible named region')
  for (const id of ['api-key-input', 'calculation-controls', 'bank-slots', 'material-grid']) {
    assert.equal(await get(page, id).isVisible(), false, `Unsupported ${id} is usable`)
  }
}

async function journey(name, run, viewport = WIDE) {
  const api = createJourneyBackend()
  const log = []
  const pageErrors = []
  const consoleErrors = []
  const external = []
  const fixture = await startStubOrigin({ port: 0, distDir: fileURLToPath(new URL('../dist/', import.meta.url)), answerApi: api.answerApi })
  let context
  let page
  let failure
  let traceStarted = false
  const step = label => { log.push(label); console.log(`  ok ${name}: ${label}`) }
  try {
    context = await browser.newContext({ viewport })
    await context.tracing.start({ screenshots: true, snapshots: true, sources: false })
    traceStarted = true
    await context.route('**/*', route => {
      const url = new URL(route.request().url())
      if (url.origin !== fixture.origin) {
        external.push(`${url.origin}${url.pathname}`)
        return route.abort()
      }
      return route.continue()
    })
    page = await context.newPage()
    page.setDefaultTimeout(TIMEOUT)
    page.setDefaultNavigationTimeout(TIMEOUT)
    page.on('pageerror', error => pageErrors.push(error.message))
    page.on('console', message => {
      log.push(`console ${message.type()}: ${message.text()}`)
      // Only the deliberately refused proof and ordinary non-owner discovery produce these HTTP errors.
      // Route/method/context validation above still fails unexpected traffic independently.
      if (message.type() === 'error' && !/^Failed to load resource: the server responded with a status of (401|403)\b/.test(message.text())) {
        consoleErrors.push(message.text())
      }
    })
    await run({ page, api, origin: fixture.origin, step })
    assert.ok(fixture.servedCount() > 0, 'Owned loopback origin served no frontend')
    api.verify()
    assert.deepEqual(external, [], 'Off-origin browser request attempted')
    assert.deepEqual(pageErrors, [], 'Uncaught browser errors')
    assert.deepEqual(consoleErrors, [], 'Unexpected browser console errors')
    results.push({ name, status: 'passed', viewport, steps: log.filter(line => !line.startsWith('console ')) })
  } catch (error) {
    failure = error
    results.push({ name, status: 'failed', viewport, steps: log, error: error.stack })
    await page?.screenshot({ path: join(OUTPUT, `${name}.png`), fullPage: true }).catch(() => {})
    if (traceStarted) {
      await context.tracing.stop({ path: join(OUTPUT, `${name}.trace.zip`) }).catch(() => {})
      traceStarted = false
    }
  } finally {
    api.cleanup()
    try {
      if (traceStarted) await context.tracing.stop()
      await context?.close()
    } finally { await fixture.close() }
    await writeFile(join(OUTPUT, `${name}.json`), JSON.stringify({ result: results.at(-1), requests: api.calls,
      proofs: api.proofs, pageErrors, consoleErrors, external, boundaryFailures: api.failures }, null, 2))
  }
  if (failure) throw failure
}

async function publicReading({ page, api, origin, step }) {
  await page.addInitScript(key => sessionStorage.setItem('gw2.apiKey', key), KEY_A)
  await page.goto(`${origin}/welcome`)
  await get(page, 'home-screen').waitFor()
  for (const id of ['features', 'guide', 'tips', 'welcome']) {
    await page.locator(`#home-tab-${id}`).click()
    assert.equal(await page.locator(`#home-tab-${id}`).getAttribute('aria-selected'), 'true')
    assert.equal(await page.locator(`#home-panel-${id}`).isVisible(), true)
  }
  for (const id of ['legal', 'privacy', 'storage']) {
    await get(page, `footer-link-${id}`).click()
    await get(page, `info-${id}`).waitFor()
    assert.equal(await page.locator('meta[name="robots"]').getAttribute('content'), 'noindex, nofollow')
    await go(page, 'home')
    await get(page, 'home-screen').waitFor()
  }
  await page.reload()
  await get(page, 'home-screen').waitFor()
  await quietFrame(page)
  assert.equal(await get(page, 'desktop-width-notice').count(), 0)
  assert.deepEqual(api.calls, [], 'Public reading proved a retained key or loaded account data')
  assert.equal(await page.evaluate(() => sessionStorage.getItem('gw2.apiKey')), KEY_A)
  step('Home/guide/legal and canonical reload remain readable at360px with retained key and zero API calls')
}

async function connectionJourney({ page, api, origin, step }) {
  await page.goto(`${origin}/#/bank`)
  await get(page, 'connection-needed').waitFor()
  assert.deepEqual(api.calls, [])
  await get(page, 'connection-needed-link').click()
  await get(page, 'api-key-input').waitFor()
  const help = get(page, 'help-create')
  await help.focus()
  await page.keyboard.press('Enter')
  await get(page, 'help-dialog').waitFor()
  await focusIs(page.locator('#api-key-help-title'))
  await page.keyboard.press('Escape')
  await get(page, 'help-dialog').waitFor({ state: 'hidden' })
  await focusIs(help)
  assert.equal(await get(page, 'api-key-input').getAttribute('type'), 'password')
  await get(page, 'api-key-input').fill(BAD_KEY)
  await get(page, 'connect').click()
  await get(page, 'connection-error').waitFor()
  assert.equal(await get(page, 'connection-error').getAttribute('role'), 'alert')
  assert.equal(api.calls.some(call => call.path.startsWith('/api/account/')), false)
  assert.equal(await page.evaluate(() => sessionStorage.getItem('gw2.apiKey')), null)
  step('disconnected Bank links to key form; keyboard help restores focus; refused proof loads no account')
  await connect(page, KEY_A)
  assert.equal(await get(page, 'api-key-input').getAttribute('type'), 'password')
  assert.equal(await get(page, 'api-key-input').inputValue(), '')
  await go(page, 'bank')
  await get(page, 'bank-slots').waitFor()
  assert.match(await get(page, 'bank-slot').first().getAttribute('title') ?? await get(page, 'bank-slot').first().innerText(), /77|200/)
  assert.equal(api.proofs.length, 1)
  await page.reload()
  await get(page, 'bank-slots').waitFor()
  assert.equal(api.proofs.length, 2, 'Reload must prove retained key once')
  const proofIndex = api.calls.findLastIndex(call => call.path === '/api/session')
  assert.equal(api.calls[proofIndex + 1].path, '/api/account/bank')
  await go(page, 'synchronization')
  await get(page, 'disconnect').click()
  await get(page, 'disconnect').waitFor({ state: 'hidden' })
  assert.equal(await page.evaluate(() => sessionStorage.getItem('gw2.apiKey')), null)
  await go(page, 'bank')
  await get(page, 'connection-needed').waitFor()
  assert.equal(await get(page, 'bank-slots').count(), 0)
  assert.equal(count(api, '/api/session/logout'), 1)
  step('successful normal proof, masked cleared draft, retained reload ordering and Disconnect clear account UI')
}

async function recoveryJourney({ page, api, origin, step }) {
  api.staleBank()
  await page.goto(`${origin}/#/synchronization`)
  await connect(page, KEY_A)
  await go(page, 'bank')
  await page.locator('.refresh-status').waitFor()
  await waitFor(api.hasHeldTask, 'Automatic Bank refresh never requested task status')
  assert.equal(await get(page, 'bank-slot').first().getByTestId('item-count').innerText(), '42')
  assert.equal(count(api, '/api/sync/account/bank'), 1)
  api.failRefresh()
  await page.locator('.refresh-error').waitFor()
  assert.match(await page.locator('.refresh-error').innerText(), /Update failed.*showing saved data/)
  assert.equal(await get(page, 'bank-slot').first().getByTestId('item-count').innerText(), '42')
  step('automatic stale refresh displays saved42 during controlled task and preserves42 on failure')
  await page.locator('.refresh-error a').click()
  await get(page, 'sync-trigger-ACCOUNT_SYNC').click()
  await get(page, 'sync-state-ACCOUNT_SYNC').filter({ hasText: /Completed/ }).waitFor()
  await go(page, 'bank')
  await get(page, 'bank-slots').waitFor()
  assert.equal(await get(page, 'bank-slot').first().getByTestId('item-count').innerText(), '77')
  assert.equal(count(api, '/api/sync/account'), 1)
  assert.equal(await page.locator('.refresh-error').count(), 0)
  step('real System Status recovery action and return to Bank install supplied fresh77 without removed manual controls')
}

async function isolationJourney({ page, api, origin, step }) {
  await page.goto(`${origin}/#/synchronization`)
  await connect(page, KEY_A)
  await go(page, 'bank')
  await get(page, 'bank-slots').waitFor()
  await page.locator('.inventory-tile').first().click()
  assert.ok(await page.evaluate(key => localStorage.getItem(key), storageKey(ACCOUNT_A)), 'A reservation was not saved')
  await go(page, 'crafting')
  await get(page, 'profit-table').waitFor()
  await get(page, 'select-row').click()
  await get(page, 'resolution-tree').waitFor()
  await go(page, 'synchronization')
  api.holdNextBank()
  await go(page, 'bank')
  await waitFor(api.hasHeldBank, 'Expected held old-account Bank response')
  await go(page, 'synchronization')
  await get(page, 'disconnect').click()
  await get(page, 'disconnect').waitFor({ state: 'hidden' })
  await connect(page, KEY_B)
  await go(page, 'bank')
  await get(page, 'bank-slots').waitFor()
  assert.match(await page.locator('.inventory-tile').first().getAttribute('title'), /B Copper Ore/)
  assert.equal(await page.locator('.inventory-tile input').first().isChecked(), false)
  const bRead = api.calls.findLast(call => call.path === '/api/account/bank')
  assert.equal(bRead.account, ACCOUNT_B)
  assert.equal(bRead.context, api.proofs.at(-1).context)
  const late = page.waitForResponse(response => response.url().endsWith('/api/account/bank') && response.status() === 200)
  api.releaseBank()
  await late
  await quietFrame(page)
  assert.match(await page.locator('.inventory-tile').first().getAttribute('title'), /B Copper Ore/)
  assert.equal(await page.locator('body').innerText().then(text => text.includes('Late A stack')), false)
  assert.equal(await get(page, 'bank-slot').first().getByTestId('item-count').innerText(), '200')
  await go(page, 'crafting')
  await get(page, 'profit-table').waitFor()
  assert.equal(await get(page, 'detail-name').count(), 0, 'A selected detail survived into B')
  const settings = JSON.parse(api.calls.findLast(call => call.path === '/api/crafting/profit').body).settings ?? {}
  assert.equal(settings.materialProtectionEnabled ?? false, false)
  assert.deepEqual(settings.protectedBankItemIds ?? [], [])
  assert.deepEqual(settings.protectedBankQuantities ?? {}, {})
  step('A reservation and selected detail are discarded from B; late A Bank response cannot repaint B')
}

async function boundaryJourney({ page, api, origin, step }) {
  await page.addInitScript(key => sessionStorage.setItem('gw2.apiKey', key), KEY_A)
  await page.setViewportSize({ width: 1023, height: 900 })
  for (const destination of ['crafting', 'discovery', 'ecto', 'synchronization', 'bank', 'materials', 'characters']) {
    await page.goto(`${origin}/#/${destination}`)
    await notice(page)
    assert.equal(new URL(page.url()).hash, `#/${destination}`)
    assert.deepEqual(api.calls, [], 'Blocked initial tool issued API work')
  }
  step('all seven direct1023px tool URLs show accessible notice and never prove retained key')
  // Last destination was Characters; choose Bank while still narrow to avoid inventing roster inputs.
  await go(page, 'bank')
  await notice(page)
  await page.setViewportSize({ width: 1024, height: 900 })
  await get(page, 'bank-slots').waitFor()
  assert.equal(api.proofs.length, 1)
  await page.locator('.inventory-tile').first().click({ button: 'right' })
  await get(page, 'menu-reserve').click()
  await get(page, 'reserve-input').fill('100')
  await get(page, 'reserve-input').press('Enter')
  await get(page, 'reserve-dialog').waitFor({ state: 'hidden' })
  assert.equal(await page.evaluate(key => JSON.parse(localStorage.getItem(key)).quantities.bank['19697'], storageKey(ACCOUNT_A)), 100)
  await go(page, 'crafting')
  await get(page, 'profit-table').waitFor()
  await get(page, 'select-row').click()
  await get(page, 'resolution-tree').waitFor()
  await get(page, 'max-displayed').fill('7')
  await get(page, 'max-displayed').press('Tab')
  const selected = await get(page, 'detail-name').innerText()
  const before = api.calls.length
  const storage = await page.evaluate(() => ({ key: sessionStorage.getItem('gw2.apiKey'), preferences: { ...localStorage } }))
  await page.setViewportSize({ width: 1023, height: 900 })
  await notice(page)
  assert.equal(api.calls.length, before)
  assert.equal(new URL(page.url()).hash, '#/crafting')
  await page.setViewportSize({ width: 1024, height: 900 })
  await get(page, 'calculation-controls').waitFor()
  assert.equal(await get(page, 'detail-name').innerText(), selected)
  assert.equal(await get(page, 'max-displayed').inputValue(), '7')
  assert.equal(api.calls.length, before, 'Widening repeated a completed calculation/proof/detail')
  assert.deepEqual(await page.evaluate(() => ({ key: sessionStorage.getItem('gw2.apiKey'), preferences: { ...localStorage } })), storage)
  step('exact1024 is supported; live1023→1024 preserves route, key, preferences, selection and controls without duplicate work')
  await page.setViewportSize({ width: 1023, height: 900 })
  await notice(page)
  await go(page, 'ecto')
  await notice(page)
  assert.equal(api.calls.length, before, 'A previously admitted Profit screen enabled hidden first Ecto reads')
  step('visiting previously unopened Ecto while narrow starts no hidden initial requests')
}

async function pendingProfitResize({ page, api, origin, step }) {
  await page.goto(`${origin}/#/synchronization`)
  await connect(page, KEY_A)
  api.holdNextProfit()
  await go(page, 'crafting')
  await waitFor(api.hasHeldProfit, 'Expected held initial Profit answer')
  assert.equal(count(api, '/api/crafting/profit'), 1)
  await page.setViewportSize({ width: 1023, height: 900 })
  await notice(page)
  const answer = page.waitForResponse(response => new URL(response.url()).pathname === '/api/crafting/profit')
  api.releaseProfit()
  await answer
  await quietFrame(page)
  assert.equal(count(api, '/api/crafting/profit'), 1, 'A blocked pending answer caused another calculation')
  await page.setViewportSize({ width: 1024, height: 900 })
  await get(page, 'profit-table').waitFor()
  assert.equal(count(api, '/api/crafting/profit'), 1, 'Widening duplicated the pending calculation')
  assert.match(await get(page, 'profit-table').innerText(), /Held Profit Answer/)
  step('pending Profit answer released while blocked renders on widening with exactly one calculation')
}

async function ectoResizeState({ page, api, origin, step }) {
  await page.goto(`${origin}/#/synchronization`)
  await connect(page, KEY_A)
  await go(page, 'ecto')
  await get(page, 'ecto-result').waitFor()
  await page.locator('#ecto-count').fill('42')
  await page.getByRole('button', { name: /Basic \/ Copper-Fed/ }).click()
  await page.locator('.tool-choice').getByRole('button', { name: 'Copper-Fed', exact: true }).click()
  await page.locator('.tp-block').first().getByRole('button', { name: /Buy order/ }).click()
  await page.locator('.tp-block').last().getByRole('button', { name: /Listing sell/ }).click()
  const result = await get(page, 'ecto-calculation').innerText()
  const before = api.calls.length
  await page.setViewportSize({ width: 1023, height: 900 })
  await notice(page)
  assert.equal(await get(page, 'ecto-screen').isVisible(), false)
  await page.setViewportSize({ width: 1024, height: 900 })
  await get(page, 'ecto-result').waitFor()
  assert.equal(api.calls.length, before, 'Width-only Ecto change fetched inputs again')
  assert.equal(await page.locator('#ecto-count').inputValue(), '42')
  assert.match(await page.locator('.salvage-row[aria-pressed="true"]').innerText(), /Basic \/ Copper-Fed/)
  assert.equal(await page.locator('.tool-choice__button--selected').innerText(), 'Copper-Fed')
  assert.match(await page.locator('.tp-block').first().locator('[aria-pressed="true"]').innerText(), /Buy order/)
  assert.match(await page.locator('.tp-block').last().locator('[aria-pressed="true"]').innerText(), /Listing sell/)
  assert.equal(await get(page, 'ecto-calculation').innerText(), result)
  step('Ecto42, selected method/tool and TP settings survive width-only changes with no refetch')
  await go(page, 'bank')
  await get(page, 'bank-slots').waitFor()
  await go(page, 'ecto')
  await get(page, 'ecto-result').waitFor()
  assert.equal(await page.locator('#ecto-count').inputValue(), '100')
  for (const path of ['/api/items/metadata', '/api/items/prices', '/api/account/luck']) assert.equal(count(api, path), 2)
  step('actual navigation still resets Ecto and fetches fresh inputs once on return')
}

async function modalResize({ page, api, origin, step }, kind) {
  await page.goto(`${origin}/#/synchronization`)
  const initialStatus = page.waitForResponse(response => new URL(response.url()).pathname === '/api/system/status/details')
  await connect(page, KEY_A)
  await initialStatus
  const trigger = kind === 'help' ? 'help-create' : 'delete-cached-data'
  const dialog = kind === 'help' ? 'help-dialog' : 'delete-dialog'
  const open = async () => {
    await get(page, trigger).click()
    await get(page, dialog).waitFor()
    assert.equal(await get(page, dialog).evaluate(node => node.open && node.matches(':modal')), true,
      `${kind} did not enter the native modal top layer`)
  }
  await open()
  const beforeResize = api.calls.length
  await page.setViewportSize({ width: 1023, height: 900 })
  await notice(page)
  await quietFrame(page)
  const modalCount = await page.locator('dialog:modal').count()
  let homeError = null
  try {
    // No Escape, force-click or widening: the real pointer must reach the public destination now.
    const home = kind === 'help' ? get(page, 'desktop-width-notice').getByRole('link', { name: 'Back to Home', exact: true }) : get(page, 'nav-home')
    await home.click({ timeout: 1500 })
    await get(page, 'home-screen').waitFor()
  } catch (error) { homeError = error.message }
  assert.equal(modalCount, 0, `${kind} remained in the native top layer below1024px; Home click: ${homeError ?? 'succeeded'}`)
  assert.equal(homeError, null, `${kind} still prevented ordinary public Home navigation`)
  assert.equal(api.calls.length, beforeResize, 'Closing a width-blocked popup or reading Home started account work')
  assert.equal(await page.evaluate(() => sessionStorage.getItem('gw2.apiKey')), KEY_A)
  assert.equal(api.calls.some(call => call.method === 'DELETE' || /delete/i.test(call.path)), false, 'Opening a confirmation submitted deletion')
  step(`${kind} closes its native modal on narrowing; actual Home pointer navigation works without Escape or widening`)

  // Real Home navigation can recreate System Status. The next pass isolates width-only state retention.
  await go(page, 'synchronization')
  await notice(page)
  const returnedStatus = page.waitForResponse(response => new URL(response.url()).pathname === '/api/system/status/details')
  await page.setViewportSize(WIDE)
  await get(page, 'disconnect').waitFor()
  await returnedStatus
  await get(page, 'api-key-input').fill('synthetic-unsent-modal-draft')
  await open()
  const beforeWidthOnly = api.calls.length
  await page.setViewportSize({ width: 1023, height: 900 })
  await notice(page)
  await quietFrame(page)
  assert.equal(await page.locator('dialog:modal').count(), 0, `${kind} did not close on a second width-only change`)
  await page.setViewportSize(WIDE)
  await get(page, 'api-key-input').waitFor()
  assert.equal(await get(page, 'api-key-input').inputValue(), 'synthetic-unsent-modal-draft')
  assert.equal(await get(page, 'disconnect').isVisible(), true)
  assert.equal(await page.locator('dialog:modal').count(), 0, `${kind} reopened automatically on widening`)
  assert.equal(api.calls.length, beforeWidthOnly, 'Popup resize reset/reproved/refetched the admitted System Status screen')
  assert.equal(await page.evaluate(() => sessionStorage.getItem('gw2.apiKey')), KEY_A)
  assert.equal(api.calls.some(call => call.method === 'DELETE' || /delete/i.test(call.path)), false)
  step(`${kind} width-only closure keeps the admitted form draft/key/connection without reopening or extra requests`)
}

/** Compatibility inventory entry reuses the current recovery story rather than removed buttons. */
export async function runUserJourneys({ recoveryOnly = false } = {}) {
  await mkdir(OUTPUT, { recursive: true })
  const cases = recoveryOnly ? [['failure-recovery', recoveryJourney]] : [
    ['public-reading', publicReading, { width: 360, height: 800 }], ['connection', connectionJourney],
    ['failure-recovery', recoveryJourney], ['account-isolation', isolationJourney], ['desktop-boundary', boundaryJourney],
    ['pending-profit-resize', pendingProfitResize], ['ecto-resize-state', ectoResizeState],
    ['help-dialog-resize', context => modalResize(context, 'help')],
    ['delete-confirmation-resize', context => modalResize(context, 'delete')]
  ]
  try {
    console.log(`Bundle: ${requireFreshBundle()}`)
    browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: process.env.GW2_SMOKE_HEADLESS !== 'false' })
    for (const [name, run, viewport] of cases) {
      try { await journey(name, run, viewport) }
      catch (error) { console.error(`${name}: ${error.stack}`); process.exitCode = 1 }
    }
    const passed = results.filter(result => result.status === 'passed').length
    console.log(`User journeys: ${passed}/${cases.length} passed (browser evidence, not Vitest coverage).`)
  } catch (error) {
    results.push({ name: 'setup', status: 'failed', error: error.stack })
    process.exitCode = 1
    console.error(error.stack)
  } finally {
    await browser?.close()
    await writeFile(join(OUTPUT, 'user-journeys.json'), JSON.stringify({ measurement: 'controlled-browser-journeys', results }, null, 2))
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await runUserJourneys()
