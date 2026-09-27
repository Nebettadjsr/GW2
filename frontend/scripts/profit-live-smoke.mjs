/**
 * STORY-WEB-014: strict live contract check, complementing smoke:browser and smoke:profit.
 * Requires the current backend and Vite origin, with an already populated database. Never syncs.
 * GW2_FRONTEND_URL, GW2_BROWSER_PATH and GW2_SMOKE_TIMEOUT_MS match smoke:browser.
 * Prints only recipe/calculation evidence, never account inventories or credentials.
 * Missing fields, a missing route, or a reset control fail this check, including on an old backend.
 */
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

const origin = process.env.GW2_FRONTEND_URL ?? 'http://localhost:5173'
const timeout = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 60_000)
const profitPath = '/api/crafting/profit'
const detailPath = `${profitPath}/resolution`
const browser = await chromium.launch({ executablePath: resolveBrowserPath() })
const page = await browser.newPage()
page.setDefaultTimeout(timeout)
const locator = (name) => page.locator(`[data-test="${name}"]`)

// Formatting only: the expected copper value is always supplied, never economically derived.
function money(copper) {
  if (copper === null) return '—'
  const amount = Math.abs(copper)
  const gold = Math.floor(amount / 10_000)
  const silver = Math.floor(amount % 10_000 / 100)
  const tail = `${amount % 100}c`
  return `${copper < 0 ? '-' : ''}${gold ? `${gold}g ${silver}s ` : silver ? `${silver}s ` : ''}${tail}`
}

function nextResponse(path) {
  return page.waitForResponse((response) => new URL(response.url()).pathname === path)
}

async function successful(responsePromise) {
  const response = await responsePromise
  assert.equal(response.status(), 200, `${new URL(response.url()).pathname}: expected HTTP 200`)
  return { body: await response.json(), request: response.request().postDataJSON() }
}

async function settled() {
  await locator('loading').waitFor({ state: 'hidden' })
  await locator('reload').waitFor()
  assert.equal(await locator('request-error').count(), 0)
}

function checkContract(body) {
  assert.equal(typeof body.settings.allowNonTradeableMaterials, 'boolean',
    'Missing non-TP setting: rebuild/restart the backend serving this origin')
  for (const row of body.rows) {
    assert.ok(Object.hasOwn(row, 'totalSellValueCopper'),
      'Missing gross total: rebuild/restart the backend serving this origin')
    assert.ok(row.totalSellValueCopper === null || Number.isInteger(row.totalSellValueCopper))
  }
}

function names(node) {
  return [node.itemName ?? `Item #${node.itemId}`, ...node.children.flatMap(names)]
}

async function checkDetail(result, table, recipeId) {
  const { body, request } = await successful(result)
  assert.equal(request.recipeId, recipeId)
  assert.deepEqual(request.calculation.settings, table.settings)
  assert.deepEqual(body.calculation, { scope: table.scope, settings: table.settings })
  assert.equal(body.recipeId, recipeId)
  assert.equal(body.treeBasis, 'SINGLE_OUTPUT_REQUIREMENT')
  assert.equal(body.treeStatus, 'AVAILABLE', 'Choose a valid available candidate in the live database')
  assert.ok(body.tree)
  await locator('resolution-tree').waitFor()
  assert.deepEqual(await locator('node-name').allTextContents(), names(body.tree))
  assert.equal((await locator('resolution-total-sell-value').textContent()).trim(),
    money(body.row.totalSellValueCopper))
  console.log(JSON.stringify({ route: detailPath, status: 200, recipeId,
    nodes: names(body.tree).length, treeBasis: body.treeBasis,
    allowNonTradeableMaterials: body.calculation.settings.allowNonTradeableMaterials }))
}

async function checkGross(table, recipeId, rowLabel) {
  const row = table.rows.find((candidate) => candidate.recipeId === recipeId)
  assert.ok(row?.resultAvailable)
  assert.notEqual(row.totalSellValueCopper, null)
  const displayed = locator('profit-row').filter({
    has: page.getByRole('button', { name: `${rowLabel} Selected`, exact: true })
  })
  assert.equal((await displayed.locator('[data-test="total-sell-value"]').textContent()).trim(),
    money(row.totalSellValueCopper))
  assert.equal((await locator('detail-total-sell-value').textContent()).trim(), money(row.totalSellValueCopper))
  console.log(JSON.stringify({ route: profitPath, status: 200, recipeId,
    listingSell: table.settings.listingSell, allowNonTradeableMaterials: table.settings.allowNonTradeableMaterials,
    outputCount: row.outputCount, craftableCount: row.craftableCount,
    totalSellValueCopper: row.totalSellValueCopper, displayed: money(row.totalSellValueCopper) }))
}

try {
  const initial = nextResponse(profitPath)
  await page.goto(origin, { waitUntil: 'domcontentloaded' })
  let table = (await successful(initial)).body
  checkContract(table)
  await settled()
  assert.equal(table.settings.allowNonTradeableMaterials, true, 'Initial backend default must be enabled')
  await locator('settings-disclosure').click()
  // Keep the candidate visible even if changing calculation settings makes its count/profit zero.
  for (const name of ['filter-zero-craftable', 'filter-not-allowed', 'filter-non-positive-profit']) {
    await locator(name).uncheck()
  }
  const candidate = table.rows.find((row) => row.resultAvailable && row.totalSellValueCopper !== null
    && row.outputCount > 1 && row.craftableCount > 1 && row.outputName !== null)
  assert.ok(candidate, 'Live fixture needs a multi-output, multi-craft available candidate')
  await locator('search').fill(candidate.outputName)
  const rowLabel = `${candidate.outputName} recipe ${candidate.recipeId}`
  const detail = nextResponse(detailPath)
  await page.getByRole('button', { name: rowLabel, exact: true }).click()
  await checkDetail(detail, table, candidate.recipeId)
  await checkGross(table, candidate.recipeId, rowLabel)

  const nonTp = locator('setting-allowNonTradeableMaterials')
  for (const allowed of [false, true]) {
    const calculation = nextResponse(profitPath)
    const resolution = nextResponse(detailPath)
    await nonTp.setChecked(allowed)
    const result = await successful(calculation)
    table = result.body
    checkContract(table)
    assert.equal(result.request.settings.allowNonTradeableMaterials, allowed)
    assert.equal(table.settings.allowNonTradeableMaterials, allowed)
    await settled()
    assert.equal(await nonTp.isChecked(), allowed)
    await checkDetail(resolution, table, candidate.recipeId)
    await checkGross(table, candidate.recipeId, rowLabel)

    const reload = nextResponse(profitPath)
    const reloadedDetail = nextResponse(detailPath)
    await locator('reload').click()
    const reloaded = await successful(reload)
    table = reloaded.body
    assert.equal(reloaded.request.settings.allowNonTradeableMaterials, allowed)
    assert.equal(table.settings.allowNonTradeableMaterials, allowed)
    await settled()
    assert.equal(await nonTp.isChecked(), allowed)
    await checkDetail(reloadedDetail, table, candidate.recipeId)
  }

  for (const mode of ['listing', 'instant']) {
    const calculation = nextResponse(profitPath)
    const resolution = nextResponse(detailPath)
    await locator('setting-listingSell').selectOption(mode)
    table = (await successful(calculation)).body
    assert.equal(table.settings.listingSell, mode === 'listing')
    await settled()
    await checkDetail(resolution, table, candidate.recipeId)
    await checkGross(table, candidate.recipeId, rowLabel)
  }
  console.log('Profit live smoke PASSED: gross values, resolution, both non-TP states and reloads.')
} finally {
  await browser.close()
}
