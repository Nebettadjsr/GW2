/**
 * #235 controlled local acceptance: native tile/menu/modal interaction, autosave, latest-on-return
 * Profit context, fixed targets and cached search at a supported desktop width. This supplies backend
 * answers; authoritative quantity arithmetic is covered by backend QA, not this browser fixture.
 *
 * Run after npm run build: node scripts/reservation-browser-smoke.mjs
 * GW2_RESERVATION_SMOKE_PORT defaults to 5184; GW2_SMOKE_HEADLESS=false shows Chromium.
 * No real key/backend/database/upstream is used. Every owned browser/server closes in finally.
 */
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { requireFreshBundle } from './bundleFreshness.mjs'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'
import { startStubOrigin } from './stubOrigin.mjs'
import { createControlledAccountSession } from './controlledAccountSession.mjs'
import { ITEM, TABLE, DETAIL, bankSnapshot, materials, tableResponse, detailResponse } from './reservationBrowserFixtures.mjs'

const TIMEOUT = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 15000)
const OUTPUT = resolve(process.env.GW2_SMOKE_OUTPUT ?? 'quality-reports/desktop-journeys')
let browser
process.on('message', async message => {
  if (message?.type !== 'gw2-journey-timeout') return
  process.exitCode = 1
  await browser?.close().catch(() => {}) // The cancelled active check closes its origin in finally.
})
process.channel?.unref()
const PREFIX = 'gw2.materialProtection.v1.00000000-0000-4000-8000-000000000194'
const session = createControlledAccountSession({ calculationRoutes: [TABLE, DETAIL], searchQueries: ['Copper Ore', '19698'],
  searchResults: { '19698': [11] },
  accountReadRoutes: ['/api/account/bank', '/api/account/materials'] })
const failures = []
const steps = []
let stock = 300
let tableSequence = 0
const record = (name, detail = '') => { steps.push(name); console.log(`  ok   ${name}${detail ? ` — ${detail}` : ''}`) }

function answerApi(context) {
  if (session.answer(context)) return
  const { url, body, sendJson } = context
  try {
    if (url.pathname === '/api/account/bank') return sendJson(200, bankSnapshot(stock))
    if (url.pathname === '/api/account/materials') return sendJson(200, materials)
    if (url.pathname === '/api/crafting/selector-options') return sendJson(200, {
      defaultScopeKind: 'ALL', disciplines: ['Armorsmith', 'Chef'], characterOptionCount: 0, characterOptions: [] })
    if (url.pathname === TABLE) return sendJson(200, tableResponse(JSON.parse(body), `reservation-table-${++tableSequence}`, stock))
    if (url.pathname === DETAIL) return sendJson(200, detailResponse(JSON.parse(body), stock))
    throw new Error(`Unanswered controlled route ${url.pathname}`)
  } catch (error) {
    failures.push(error.message)
    sendJson(400, { error: 'INVALID_CONTROLLED_REQUEST', message: error.message })
  }
}

const requestCount = (stub, path) => stub.requests.filter(request => request.path === path).length
const lastBody = (stub, path) => JSON.parse(stub.requests.filter(request => request.path === path).at(-1).body)
const tile = page => page.locator('.inventory-tile').first()
const checkbox = page => tile(page).locator('input[type="checkbox"]')
const saved = page => page.evaluate(key => JSON.parse(localStorage.getItem(key)), PREFIX)
const text = async locator => (await locator.innerText()).replace(/\s+/g, ' ').trim()
async function focused(locator) {
  const state = await locator.evaluate(element => ({ focused: document.activeElement === element,
    expected: element.getAttribute('data-test'), visibility: getComputedStyle(element).visibility,
    actual: document.activeElement?.getAttribute('data-test'), tag: document.activeElement?.tagName }))
  assert.equal(state.focused, true, `Focus moved outside its expected control: ${JSON.stringify(state)}`)
}
async function settled(page) { await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))) }
async function navigate(page, destination) {
  await page.locator(`[data-test="nav-${destination}"]`).click()
  await page.locator(destination === 'crafting' ? '[data-test="calculation-controls"]' : `[data-test="${destination}-screen"]`).waitFor()
  await settled(page)
}
async function waitForCount(stub, path, count) {
  const deadline = Date.now() + TIMEOUT
  while (requestCount(stub, path) < count) {
    if (Date.now() >= deadline) throw new Error(`Expected ${count} ${path} requests; saw ${requestCount(stub, path)}`)
    await new Promise(resolve => setTimeout(resolve, 20))
  }
}
async function openEditor(page, key = 'Shift+F10', expectedCount, origin = tile(page)) {
  const input = origin.locator('input[type="checkbox"]')
  await input.focus()
  await input.press(key)
  await page.locator('[data-test="item-menu"]').waitFor()
  await focused(page.locator('[data-test="menu-reserve"]'))
  await page.locator('[data-test="menu-reserve"]').click()
  await page.locator('[data-test="reserve-dialog"]').waitFor()
  await focused(page.locator('[data-test="reserve-input"]'))
  if (expectedCount !== undefined) assert.match(await text(page.locator('[data-test="reserve-dialog"]')), new RegExp(`In (bank|material storage): ${expectedCount.toLocaleString('en-US')}\\b`))
}
async function apply(page, value, origin = checkbox(page)) {
  await page.locator('[data-test="reserve-input"]').fill(String(value))
  await page.locator('[data-test="reserve-input"]').press('Enter')
  await page.locator('[data-test="reserve-dialog"]').waitFor({ state: 'hidden' })
  await focused(origin)
}
async function geometry(page, selector) {
  return page.locator(selector).evaluate(element => {
    const box = element.getBoundingClientRect()
    return { left: box.left, right: box.right, top: box.top, bottom: box.bottom,
      viewportWidth: window.innerWidth, viewportHeight: window.innerHeight }
  })
}
async function contained(page, selector) {
  const box = await geometry(page, selector)
  assert.ok(box.left >= 0 && box.right <= box.viewportWidth + 1 && box.top >= 0 && box.bottom <= box.viewportHeight + 1,
    `${selector} escaped viewport: ${JSON.stringify(box)}`)
}
async function noPageOverflow(page) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1), true, 'The page overflows horizontally')
}
async function reservationPresentation(page, gridSelector, quantity, source) {
  assert.equal(await page.locator('.inventory-tile').evaluateAll(tiles => tiles.some(tile => /Keep\s+\d/i.test(tile.innerText))), false,
    'Reservation text overlaps a tile instead of appearing beside its grid')
  const section = page.locator('section').filter({ has: page.locator(gridSelector) }).first()
  const notes = section.locator('[data-test="reservation-notes"]')
  const note = notes.locator('[data-test="reservation-note"]')
  assert.equal(await note.count(), 1, 'Duplicate stacks must not repeat the item note within a grid')
  const content = await text(note)
  for (const fact of ['Copper Ore', String(quantity), source]) assert.ok(content.includes(fact), `Reservation note omitted ${fact}: ${content}`)
  const layout = await section.evaluate((element, selector) => {
    const grid = element.querySelector(selector).getBoundingClientRect()
    const notes = element.querySelector('[data-test="reservation-notes"]')
    const box = notes.getBoundingClientRect()
    const walker = document.createTreeWalker(notes, NodeFilter.SHOW_TEXT)
    const lines = []
    while (walker.nextNode()) {
      if (!walker.currentNode.textContent.trim()) continue
      const range = document.createRange()
      range.selectNodeContents(walker.currentNode)
      lines.push(...Array.from(range.getClientRects(), line => ({ left: line.left, right: line.right, top: line.top, bottom: line.bottom })))
    }
    return { grid: { right: grid.right, top: grid.top, bottom: grid.bottom },
      notes: { left: box.left, right: box.right, top: box.top, bottom: box.bottom }, lines,
      clipped: notes.scrollWidth > notes.clientWidth + 1 || notes.scrollHeight > notes.clientHeight + 1,
      width: window.innerWidth }
  }, gridSelector)
  assert.equal(layout.clipped, false, `Reservation notes are clipped: ${JSON.stringify(layout)}`)
  assert.ok(layout.notes.left >= 0 && layout.notes.right <= layout.width + 1, `Reservation notes escape the viewport: ${JSON.stringify(layout)}`)
  assert.ok(layout.lines.length > 0, 'Reservation notes have no rendered text')
  for (const line of layout.lines) assert.ok(line.left >= layout.notes.left - 1 && line.right <= layout.notes.right + 1 &&
    line.top >= layout.notes.top - 1 && line.bottom <= layout.notes.bottom + 1, `Reservation note text is clipped: ${JSON.stringify(layout)}`)
  assert.ok(layout.notes.left >= layout.grid.right - 1 && layout.notes.top < layout.grid.bottom,
    `Desktop reservation notes are not beside their own grid: ${JSON.stringify(layout)}`)
  await noPageOverflow(page)
}

async function runViewport(browser, stub, viewport) {
  const page = await browser.newPage({ viewport })
  page.setDefaultTimeout(TIMEOUT)
  page.on('pageerror', error => failures.push(error.message))
  page.on('console', message => { if (message.type() === 'error') failures.push(message.text()) })
  await session.install(page, stub.origin)
  stock = 300
  const label = `${viewport.width}×${viewport.height}`
  const initialTables = requestCount(stub, TABLE)
  await page.context().tracing.start({ screenshots: true, snapshots: true, sources: false })
  let traced = true
  try {
    await page.goto(`${stub.origin}/#/crafting`, { waitUntil: 'networkidle' })
    await page.locator('[data-test="profit-table"]').waitFor()
    assert.equal(requestCount(stub, TABLE), initialTables + 1, 'Initial activation calculated twice')
    await page.locator('[data-test="select-row"]').click()
    await page.locator('[data-test="resolution-tree"]').waitFor()
    await page.locator('[data-test="max-displayed"]').fill('7')
    await page.locator('[data-test="max-displayed"]').press('Tab')
    await navigate(page, 'bank')
    assert.equal(await page.locator('[data-test="bank-empty-slot"]').count(), 2)
    assert.equal(await page.locator('[data-slot="4"] input').count(), 0, 'Ineligible Bank tile became selectable')
    const beforeHover = stub.requests.length
    await tile(page).hover()
    assert.match(await tile(page).getAttribute('title'), /Copper Ore/)
    assert.match(await checkbox(page).getAttribute('aria-label'), /Copper Ore/)
    await settled(page)
    assert.equal(stub.requests.length, beforeHover, 'Hover requested metadata')
    await openEditor(page, 'ContextMenu', 300)
    assert.equal(await page.locator('[data-test="reserve-dialog"]').getAttribute('aria-modal'), 'true')
    assert.match(await text(page.locator('[data-test="reserve-dialog"]')), /Copper Ore/)
    await apply(page, 100)
    assert.deepEqual((await saved(page)).quantities.bank, { [ITEM]: 100 })
    await navigate(page, 'crafting')
    assert.equal(requestCount(stub, TABLE), initialTables + 1, 'Disabled protection recalculated after edits')
    await page.locator('[data-test="protection-toggle"]').check()
    await waitForCount(stub, TABLE, initialTables + 2)
    await page.waitForFunction(() => document.querySelector('[data-test="craftable-count"]')?.textContent.trim() === '200')
    record(`${label} normal proof, cached names, duplicate total and first autosaved finite target`)

    await navigate(page, 'bank')
    const beforeTables = requestCount(stub, TABLE)
    const beforeDetails = requestCount(stub, DETAIL)
    await page.locator('[data-test="selection-toggle"]').click() // Bulk remains item-wide ALL.
    assert.deepEqual((await saved(page)).selections.bank, [ITEM])
    assert.deepEqual((await saved(page)).quantities.bank, {})
    await page.locator('[data-test="page-heading"]').focus()
    const allColor = await tile(page).evaluate(element => getComputedStyle(element).outlineColor)
    await page.locator('[data-test="selection-toggle"]').click()
    await checkbox(page).click() // Only this stack becomes fully reserved (200).
    assert.deepEqual((await saved(page)).quantities.bank, { [ITEM]: 200 })
    assert.deepEqual((await saved(page)).selections.bank, [])
    assert.equal(await page.locator('[data-slot="1"] input').isChecked(), false)
    // Playwright's position is padding-relative: x58 can miss a 60px tile with a 3px finite border.
    // Use the actual border box so the background click stays on the tile as its border changes.
    const background = await tile(page).boundingBox()
    assert.ok(background)
    await page.mouse.click(background.x + 1, background.y + background.height / 2)
    assert.deepEqual((await saved(page)).quantities.bank, {})
    await tile(page).locator('[data-test="item-icon-fallback"]').click()
    assert.deepEqual((await saved(page)).quantities.bank, { [ITEM]: 200 })
    await tile(page).locator('[data-test="item-count"]').click()
    assert.deepEqual((await saved(page)).quantities.bank, {})
    await checkbox(page).focus()
    await checkbox(page).press('Space')
    assert.equal(await checkbox(page).isChecked(), true)
    await checkbox(page).press('Space')
    assert.equal(await checkbox(page).isChecked(), false)
    assert.deepEqual((await saved(page)).quantities.bank, {})
    record(`${label} native checkbox/background/icon/count/Space each toggle exactly once`)

    await checkbox(page).focus()
    await tile(page).dispatchEvent('contextmenu', { button: 2, clientX: viewport.width - 2, clientY: viewport.height - 2 })
    await page.locator('[data-test="item-menu"]').waitFor()
    await contained(page, '[data-test="item-menu"]')
    await page.keyboard.press('Escape')
    await focused(checkbox(page))
    await openEditor(page, 'Shift+F10', 300)
    await contained(page, '[data-test="reserve-dialog"]')
    await page.locator('[data-test="reserve-input"]').press('Shift+Tab')
    await focused(page.locator('[data-test="reserve-cancel"]'))
    await page.keyboard.press('Tab')
    await focused(page.locator('[data-test="reserve-input"]'))
    await page.locator('[data-test="reserve-input"]').fill('1.5')
    await page.locator('[data-test="reserve-input"]').press('Enter')
    await page.locator('[data-test="reserve-error"]').waitFor()
    assert.deepEqual((await saved(page)).quantities.bank, {})
    await apply(page, 250)
    const finiteBorder = await tile(page).evaluate(element => {
      const style = getComputedStyle(element)
      return { color: style.borderTopColor, styles: [style.borderTopStyle, style.borderRightStyle, style.borderBottomStyle, style.borderLeftStyle] }
    })
    const finiteColor = finiteBorder.color
    assert.deepEqual(finiteBorder.styles, ['solid', 'solid', 'solid', 'solid'], 'Finite reservation border must be solid')
    const [red, green, blue] = finiteColor.match(/\d+/g).map(Number)
    assert.ok(red > 180 && green > 140 && blue < 170 && red >= green && green > blue, `Finite reservation border must be yellow: ${finiteColor}`)
    assert.notEqual(finiteColor, allColor, `Finite border must use a different color than ALL (${allColor})`)
    assert.match(await checkbox(page).getAttribute('aria-label'), /Copper Ore/)
    assert.equal(await checkbox(page).isChecked(), true, 'The first finite200 allocation must be fully checked')
    assert.equal(await page.locator('[data-slot="1"] input').evaluate(input => input.indeterminate), true,
      'The remaining50 allocation must have native mixed state')
    await reservationPresentation(page, '[data-test="bank-slots"]', 250, 'bank')
    assert.match(await text(page.locator('[data-test="selection-committed"]')), /1 item\./)
    await noPageOverflow(page)
    record(`${label} menu clamping, native modal focus trap, validation and distinct finite color`, `${finiteColor} vs ${allColor}`)

    for (const dismiss of ['Cancel', 'Escape', 'outside click']) {
      const previous = await saved(page)
      await page.locator('[data-test="selection-toggle"]').focus()
      await tile(page).locator('[data-test="item-icon-fallback"]').click({ button: 'right' })
      await page.locator('[data-test="menu-reserve"]').click()
      await page.locator('[data-test="reserve-input"]').fill('999')
      if (dismiss === 'Cancel') await page.locator('[data-test="reserve-cancel"]').click()
      else if (dismiss === 'Escape') await page.keyboard.press('Escape')
      else await page.locator('[data-test="item-actions-backdrop"]').click({ position: { x: 2, y: 2 } })
      await page.locator('[data-test="reserve-dialog"]').waitFor({ state: 'hidden' })
      assert.deepEqual(await saved(page), previous, `Mouse ${dismiss} saved a cancelled draft`)
      assert.equal(await checkbox(page).evaluate(element => document.activeElement === element), true,
        `Mouse ${dismiss} did not restore the initiating tile after another control was focused`)
    }
    record(`${label} native mouse menu restores its initiating tile for Cancel/Escape/outside`)

    await navigate(page, 'materials')
    for (let action = 0; action < 2; action++) await page.locator('[data-test="selection-toggle"]').click()
    await openEditor(page, 'ContextMenu', 0)
    await apply(page, 17)
    assert.deepEqual((await saved(page)).quantities, { bank: { [ITEM]: 250 }, materials: { [ITEM]: 17 } })
    assert.match(await checkbox(page).getAttribute('aria-label'), /17/)
    await reservationPresentation(page, '[data-test="material-grid"]', 17, 'material storage')
    assert.equal((await saved(page)).enabled, false, 'Clearing the last selection must disable protection; later picks must not enable it')
    assert.equal(requestCount(stub, TABLE), beforeTables, 'Offscreen autosaves calculated Profit')
    assert.equal(requestCount(stub, DETAIL), beforeDetails, 'Offscreen autosaves requested detail')
    await navigate(page, 'crafting')
    await waitForCount(stub, TABLE, beforeTables + 1)
    await page.waitForFunction(() => document.querySelector('[data-test="craftable-count"]')?.textContent.trim() === '300')
    assert.equal(await page.locator('[data-test="protection-toggle"]').isChecked(), false, 'Protection silently re-enabled after an empty selection')
    assert.equal(lastBody(stub, TABLE).settings.materialProtectionEnabled, false)
    assert.equal(requestCount(stub, TABLE), beforeTables + 1, 'Latest disabled context calculated more than once on return')
    await page.locator('[data-test="protection-toggle"]').check()
    await waitForCount(stub, TABLE, beforeTables + 2)
    await page.waitForFunction(() => document.querySelector('[data-test="craftable-count"]')?.textContent.trim() === '50')
    await page.locator('[data-test="resolution-tree"]').waitFor()
    assert.equal(requestCount(stub, TABLE), beforeTables + 2, 'Explicit protection activation calculated more than once')
    assert.deepEqual(lastBody(stub, TABLE).settings, lastBody(stub, DETAIL).calculation.settings)
    assert.deepEqual(lastBody(stub, TABLE).settings.protectedItemIds, [])
    assert.deepEqual(lastBody(stub, TABLE).settings.protectedBankQuantities, { [ITEM]: 250 })
    assert.deepEqual(lastBody(stub, TABLE).settings.protectedMaterialQuantities, { [ITEM]: 17 })
    assert.equal(await text(page.locator('[data-test="total-profit"]')), '+1g 23s 45c')
    assert.equal(await text(page.locator('[data-test="total-sell-value"]')), '5g 67s 89c')
    await page.locator('[data-path="0"] > [data-test="node-children"]').evaluate(element => { element.open = true })
    const requirement = page.locator('[data-path="0.0"]')
    assert.match(await text(requirement), /From stock ×50/)
    assert.match(await text(requirement), /Keep 250 in bank/)
    assert.match(await text(requirement), /Keep 17 in material storage/)
    record(`${label} zero offscreen calculations, latest return once, explicit enable, echoed detail and supplied 50-stock result`)

    const beforeSearch = requestCount(stub, TABLE)
    const beforeLookups = requestCount(stub, '/api/crafting/ingredient-search')
    await navigate(page, 'bank')
    await tile(page).click({ button: 'right' })
    await page.locator('[data-test="menu-open-profit"]').click()
    await page.locator('[data-test="search"]').waitFor()
    assert.equal(await page.locator('[data-test="search"]').inputValue(), 'Copper Ore')
    await waitForCount(stub, '/api/crafting/ingredient-search', beforeLookups + 1)
    await settled(page)
    assert.equal(requestCount(stub, TABLE), beforeSearch, 'Search navigation calculated unnecessarily')
    assert.equal(await page.locator('[data-test="max-displayed"]').inputValue(), '7')
    await page.goBack()
    await page.locator('[data-test="bank-screen"]').waitFor()
    await page.goForward()
    await page.locator('[data-test="search"]').waitFor()
    assert.equal(await page.locator('[data-test="search"]').inputValue(), 'Copper Ore')
    assert.equal(requestCount(stub, TABLE), beforeSearch, 'Unchanged return recalculated')
    assert.equal(new URL(page.url()).hash, '#/crafting')
    record(`${label} cached search prefill and Back/Forward preserve settings without recalculation`)

    await navigate(page, 'materials')
    const unknown = page.locator('.inventory-tile').nth(2)
    assert.equal(await unknown.getAttribute('title'), 'Item #19698')
    await unknown.click({ button: 'right' })
    assert.match(await page.locator('[data-test="item-menu"]').getAttribute('aria-label'), /Item #19698/)
    const [lookup] = await Promise.all([
      page.waitForRequest(request => new URL(request.url()).pathname === '/api/crafting/ingredient-search'),
      page.locator('[data-test="menu-open-profit"]').click()
    ])
    assert.equal(lookup.method(), 'GET')
    assert.equal(new URL(lookup.url()).searchParams.get('query'), '19698', 'Unknown-name action lost the selected ingredient ID')
    assert.equal(await page.locator('[data-test="search"]').inputValue(), '19698')
    await page.locator('[data-test="profit-row"]').waitFor()
    assert.deepEqual(await page.locator('[data-test="profit-row"] .recipe-name').allTextContents(), ['Iron Ingot'])
    assert.equal(requestCount(stub, TABLE), beforeSearch, 'ID search navigation calculated unnecessarily')
    assert.equal(await page.locator('[data-test="max-displayed"]').inputValue(), '7')
    record(`${label} unnamed material keeps ID label and resolves its cached consuming recipe by bare ID`)

    await page.reload({ waitUntil: 'networkidle' })
    await page.waitForFunction(() => document.querySelector('[data-test="craftable-count"]')?.textContent.trim() === '50')
    assert.deepEqual((await saved(page)).quantities.bank, { [ITEM]: 250 })
    await navigate(page, 'bank')
    for (const currentStock of [200, 400]) {
      stock = currentStock
      await navigate(page, 'materials')
      await navigate(page, 'bank')
      await openEditor(page, 'Shift+F10', currentStock)
      assert.equal(await page.locator('[data-test="reserve-input"]').inputValue(), '250')
      await page.locator('[data-test="reserve-cancel"]').click()
      await focused(checkbox(page))
      assert.deepEqual((await saved(page)).quantities.bank, { [ITEM]: 250 })
    }
    await checkbox(page).click() // Remove only this full 200-stack, preserving the second stack's 50.
    assert.equal(await checkbox(page).isChecked(), false)
    assert.equal(await page.locator('[data-slot="1"] input').evaluate(input => input.indeterminate), true)
    assert.deepEqual((await saved(page)).selections.bank, [])
    assert.deepEqual((await saved(page)).quantities.bank, { [ITEM]: 50 })
    await noPageOverflow(page)
    record(`${label} restart and 200/400 stock preserve fixed250; normal click changes only its stack`)

    // The actual third stack is essential: a .first() helper cannot detect item-wide selection.
    stock = 1000
    await navigate(page, 'materials')
    await navigate(page, 'bank')
    const third = page.locator('[data-slot="2"] .inventory-tile')
    const bankInputs = page.locator('[data-test="bank-slot"] input')
    const states = async () => bankInputs.evaluateAll(inputs => inputs.map(input => ({ checked: input.checked, mixed: input.indeterminate })))
    const none = { checked: false, mixed: false }, full = { checked: true, mixed: false }, partial = { checked: false, mixed: true }
    const stackTables = requestCount(stub, TABLE), stackDetails = requestCount(stub, DETAIL)
    await openEditor(page, 'ContextMenu', 1000, third)
    await apply(page, 300, third.locator('input'))
    assert.deepEqual(await states(), [full, partial, none, none])
    await checkbox(page).click()
    assert.deepEqual(await states(), [none, partial, none, none])
    assert.deepEqual((await saved(page)).quantities.bank, { [ITEM]: 50 })
    await openEditor(page, 'Shift+F10', 1000, third)
    await apply(page, 300, third.locator('input'))
    await third.locator('[data-test="item-count"]').click()
    assert.deepEqual(await states(), [full, partial, full, none])
    assert.deepEqual((await saved(page)).quantities.bank, { [ITEM]: 550 })
    await page.locator('[data-test="page-heading"]').focus()
    const borders = await page.locator('[data-test="bank-slot"] .inventory-tile').evaluateAll(tiles => tiles.slice(0, 4).map(tile => {
      const style = getComputedStyle(tile)
      return { color: style.borderTopColor, style: style.borderTopStyle }
    }))
    for (const index of [0, 1, 2]) assert.deepEqual(borders[index], { color: finiteColor, style: 'solid' })
    assert.notEqual(borders[3].color, finiteColor, 'Untouched fourth stack was colored as reserved')
    assert.equal(requestCount(stub, TABLE), stackTables, 'Stack clicks calculated Profit offscreen')
    assert.equal(requestCount(stub, DETAIL), stackDetails, 'Stack clicks requested detail offscreen')
    await navigate(page, 'crafting')
    await waitForCount(stub, TABLE, stackTables + 1)
    await page.waitForFunction(() => document.querySelector('[data-test="craftable-count"]')?.textContent.trim() === '45')
    const stackSettings = lastBody(stub, TABLE).settings
    assert.deepEqual(stackSettings.protectedItemIds, [])
    assert.deepEqual(stackSettings.protectedBankItemIds, [])
    assert.deepEqual(stackSettings.protectedBankQuantities, { [ITEM]: 550 })
    assert.deepEqual(stackSettings.protectedBankAllocations, [250, 50, 250, 0].map((reservedUnits, slot) => ({
      slot, itemId: ITEM, reservedUnits, observedCount: 250, binding: null, boundTo: null })))
    assert.equal(requestCount(stub, TABLE), stackTables + 1, 'Latest stack layout calculated more than once')
    await noPageOverflow(page)
    record(`${label} actual third-stack click preserves partial rows, yellow/native states and exact guarded wire intent`)
  } catch (error) {
    await page.screenshot({ path: join(OUTPUT, 'reservations.png'), fullPage: true }).catch(() => {})
    await page.context().tracing.stop({ path: join(OUTPUT, 'reservations.trace.zip') }).catch(() => {})
    traced = false
    throw error
  } finally {
    if (traced) await page.context().tracing.stop()
    await page.close()
  }
}

async function main() {
  await mkdir(OUTPUT, { recursive: true })
  console.log(`Bundle: ${requireFreshBundle()}`)
  const stub = await startStubOrigin({ port: Number(process.env.GW2_RESERVATION_SMOKE_PORT ?? 5184),
    distDir: fileURLToPath(new URL('../dist/', import.meta.url)), answerApi })
  try {
    browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: process.env.GW2_SMOKE_HEADLESS !== 'false' })
    await runViewport(browser, stub, { width: 1440, height: 900 })
    assert.ok(stub.servedCount() > 0, 'The owned loopback server served no frontend')
    assert.deepEqual(failures, [], 'Browser/controlled fixture errors')
    record('normal synthetic proof, exact methods/routes/context and no raw-key/sync/off-origin traffic', session.verify())
    console.log(`\nReservation browser check PASSED (${steps.length} steps).`)
    await writeFile(join(OUTPUT, 'reservations.json'), JSON.stringify({ results: [{ name: 'reserve-stock-and-use-profit',
      status: 'passed', viewport: { width: 1440, height: 900 }, steps }] }, null, 2))
  } finally {
    try { await browser?.close() } finally { await stub.close() }
  }
}
main().catch(async error => {
  console.error(`\nReservation browser check FAILED after ${steps.length} steps: ${error.stack}`)
  process.exitCode = 1
  await mkdir(OUTPUT, { recursive: true })
  await writeFile(join(OUTPUT, 'reservations.json'), JSON.stringify({ results: [{ name: 'reserve-stock-and-use-profit',
    status: 'failed', steps, error: error.stack }], boundaryFailures: failures }, null, 2))
})
