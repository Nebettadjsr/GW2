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
  for (const row of body.rows) {
    assert.ok(Object.hasOwn(row, 'totalSellValueCopper'),
      'Missing gross total: rebuild/restart the backend serving this origin')
    assert.ok(row.totalSellValueCopper === null || Number.isInteger(row.totalSellValueCopper))
  }
}

function names(node) {
  return [node.itemName ?? `Item #${node.itemId}`, ...node.children.flatMap(names)]
}

/** Every returned requirement in document order, so a displayed fact can be read back per node. */
function flatten(node) {
  return [node, ...node.children.flatMap(flatten)]
}

/**
 * The compact summary of every requirement, compared against the body the backend really sent
 * (`DOMAIN_SPEC.md` 2.1.1). Only supplied facts are compared — no quantity or cost is derived here —
 * and the groups are collapsed at this point, which is exactly what must not withhold a requirement.
 */
async function checkCompactSummaries(body) {
  const supplied = flatten(body.tree)
  assert.deepEqual(
    await locator('node-requested').allTextContents().then((texts) => texts.map((t) => t.trim())),
    supplied.map((node) => `${node.requestedQuantity} needed`),
    'A displayed required quantity is not the one the backend supplied'
  )
  assert.deepEqual(
    await locator('node-effective-cost').allTextContents().then((texts) => texts.map((t) => t.trim())),
    supplied.map((node) => money(node.effectiveCostCopper)),
    'A displayed effective cost is not the one the backend supplied'
  )
  assert.deepEqual(
    await locator('node-crafter').allTextContents().then((texts) => texts.map((t) => t.trim())),
    supplied.filter((node) => node.characterName !== null).map((node) => `Crafted by ${node.characterName}`),
    'The crafter lines are not the characters the backend named'
  )
}

/**
 * Keyboard disclosure against live data: the first group is reachable by focus, Enter expands that
 * one group, and any group nested inside it stays collapsed until it is asked for itself.
 */
async function checkKeyboardDisclosure() {
  const summaries = page.locator('[data-test="node-children"] > summary')
  const groups = await summaries.count()
  if (groups === 0) return { groups, expanded: null, nestedLeftCollapsed: 0 }

  await summaries.first().focus()
  assert.ok(
    await page.evaluate(
      () => document.activeElement?.parentElement?.getAttribute('data-test') === 'node-children'
    ),
    'Focus did not reach an ingredient group control'
  )
  await page.keyboard.press('Enter')
  const openPaths = () =>
    page.$$eval('[data-test="node-children"]', (elements) =>
      elements
        .filter((element) => element.open)
        .map((element) => element.parentElement?.getAttribute('data-path') ?? '?')
    )
  const expanded = await openPaths()
  assert.deepEqual(expanded.length, 1, `Enter expanded ${expanded.length} groups rather than one`)
  const nested = await page.$$eval(
    '[data-test="node-children"]',
    (elements, parent) =>
      elements
        .map((element) => ({
          path: element.parentElement?.getAttribute('data-path') ?? '?',
          open: element.open
        }))
        .filter((group) => group.path.startsWith(`${parent}.`)),
    expanded[0]
  )
  assert.ok(
    nested.every((group) => !group.open),
    `Expanding "${expanded[0]}" cascaded into ${JSON.stringify(nested)}`
  )
  await page.keyboard.press('Enter')
  assert.equal((await openPaths()).length, 0, 'Enter did not collapse the group again')
  return { groups, expanded: expanded[0], nestedLeftCollapsed: nested.length }
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
  // DOMAIN_SPEC 2.1.1: the fresh row's own figures are no longer printed beside the table's, and
  // every ingredient group starts collapsed while still rendering every returned requirement.
  assert.equal(await locator('resolution-total-sell-value').count(), 0)
  assert.equal(await locator('resolution-row').count(), 0)
  assert.equal(
    await page.locator('[data-test="node-children"][open]').count(),
    0,
    'Ingredient groups did not start collapsed'
  )
  await checkCompactSummaries(body)
  const disclosure = await checkKeyboardDisclosure()
  console.log(JSON.stringify({ route: detailPath, status: 200, recipeId,
    nodes: names(body.tree).length, treeBasis: body.treeBasis,
    groups: disclosure.groups, expandedByKeyboard: disclosure.expanded,
    nestedLeftCollapsed: disclosure.nestedLeftCollapsed }))
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
    listingSell: table.settings.listingSell,
    outputCount: row.outputCount, craftableCount: row.craftableCount,
    totalSellValueCopper: row.totalSellValueCopper, displayed: money(row.totalSellValueCopper) }))
}

try {
  const initial = nextResponse(profitPath)
  await page.goto(origin, { waitUntil: 'domcontentloaded' })
  let table = (await successful(initial)).body
  checkContract(table)
  await settled()
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

  const reload = nextResponse(profitPath)
  const reloadedDetail = nextResponse(detailPath)
  await locator('reload').click()
  table = (await successful(reload)).body
  checkContract(table)
  await settled()
  await checkDetail(reloadedDetail, table, candidate.recipeId)

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
  console.log('Profit live smoke PASSED: gross values, resolution and reload.')
} finally {
  await browser.close()
}
