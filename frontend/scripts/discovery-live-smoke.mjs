/**
 * STORY-WEB-012: read-only live comparison for Crafting Discovery, complementing smoke:discovery.
 *
 * Requires the current backend and Vite origin, with an already populated database. It compares what
 * the browser *rendered* against the very responses the two Discovery routes returned: the listed
 * recipes and their supplied economics, and one selected recipe's fresh tree.
 *
 * Read-only by construction. Both routes are calculations over stored data, the script triggers no
 * synchronization and clicks no synchronization control, and it writes nothing. There is no implicit
 * live synchronization: if the database is empty the run fails and says so rather than filling it.
 *
 * It asserts no domain value — every expected number is a value the response supplied, formatted the
 * way the page formats it — and establishes no performance (`TARGET_ARCHITECTURE.md` 33). It prints
 * recipe and calculation evidence only, never account inventories or credentials.
 *
 * Prerequisites, both started by hand:
 *   1. the backend    — `./mvnw spring-boot:run` in the repository root
 *   2. the dev server — `npm run dev` in `frontend/` (it proxies `/api` to the backend)
 *
 * Usage:  npm run smoke:discovery:live
 * Environment: GW2_FRONTEND_URL, GW2_BROWSER_PATH and GW2_SMOKE_TIMEOUT_MS match the other checks.
 */
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'
import { resolveBrowserPath } from './resolveBrowserPath.mjs'

const origin = process.env.GW2_FRONTEND_URL ?? 'http://localhost:5173'
const timeout = Number(process.env.GW2_SMOKE_TIMEOUT_MS ?? 60_000)
const discoveryPath = '/api/crafting/discovery'
const detailPath = `${discoveryPath}/resolution`

const browser = await chromium.launch({ executablePath: resolveBrowserPath() })
const page = await browser.newPage()
page.setDefaultTimeout(timeout)
const locator = (name) => page.locator(`[data-test="${name}"]`)

/** Formatting only: the copper value is always one the response supplied, never derived here. */
function money(copper) {
  if (copper === null || copper === undefined) return '—'
  const amount = Math.abs(copper)
  const gold = Math.floor(amount / 10_000)
  const silver = Math.floor((amount % 10_000) / 100)
  const tail = `${amount % 100}c`
  const sign = copper < 0 ? '-' : ''
  return `${sign}${gold ? `${gold}g ${silver}s ` : silver ? `${silver}s ` : ''}${tail}`
}

function signedMoney(copper) {
  if (copper === null || copper === undefined) return '—'
  return copper > 0 ? `+${money(copper)}` : money(copper)
}

function count(value) {
  return value === null || value === undefined ? '—' : String(value)
}

function nextResponse(path) {
  const pending = page.waitForResponse((response) => new URL(response.url()).pathname === path)
  // A run may legitimately decide not to await one of these (a reload can drop the selected recipe).
  // Marking it handled keeps an abandoned wait from crashing the process and masking the real error.
  pending.catch(() => undefined)
  return pending
}

async function successful(responsePromise) {
  const response = await responsePromise
  assert.equal(response.status(), 200, `${new URL(response.url()).pathname}: expected HTTP 200`)
  return { body: await response.json(), request: response.request().postDataJSON() }
}

async function settled() {
  await locator('discovery-loading').waitFor({ state: 'hidden' })
  await locator('discovery-reload').waitFor()
  assert.equal(await locator('discovery-request-error').count(), 0, 'The live calculation failed')
  assert.equal(await locator('discovery-selector-error').count(), 0, 'The live selector read failed')
}

/** Every item name a tree carries, parents before children, in the order the backend supplied. */
function names(node) {
  return [node.itemName ?? `Item #${node.itemId}`, ...node.children.flatMap(names)]
}

/** The contract fields this page depends on; a missing one means an old backend, not a page defect. */
function checkContract(body, request) {
  assert.ok(body.scope, 'Missing echoed scope: rebuild/restart the backend serving this origin')
  assert.equal(typeof body.scope.rating, 'number', 'Missing echoed rating')
  assert.ok(
    Object.hasOwn(body, 'inventoryCharacterName'),
    'Missing echoed inventoryCharacterName: rebuild/restart the backend serving this origin'
  )
  assert.equal(typeof body.settings.dailyBuyInsteadOfCraft, 'boolean', 'Missing echoed daily value')
  // The route does not accept the fixed daily setting.
  if (request?.settings !== undefined) {
    assert.equal(request.settings.dailyBuyInsteadOfCraft, undefined, 'The page sent the fixed daily setting')
  }
  for (const row of body.rows) {
    assert.ok(Object.hasOwn(row, 'totalSellValueCopper'), 'Missing gross total on a row')
    assert.ok(Object.hasOwn(row, 'minRating'), 'Missing recipe level on a row')
  }
}

/** The rendered list, compared against the rows the response actually returned. */
async function checkList(table) {
  const rendered = await page.$$eval('[data-test="discovery-row"]', (rows) =>
    rows.map((row) => ({
      name: row.querySelector('.recipe-name').textContent.trim(),
      recipe: row.querySelector('.recipe-ids').textContent.trim(),
      level: row.querySelector('[data-test="discovery-level"]').textContent.trim(),
      craftable: row.querySelector('[data-test="discovery-craftable"]').textContent.trim(),
      buyCost: row.querySelector('[data-test="discovery-buy-cost"]').textContent.trim(),
      sellValue: row.querySelector('[data-test="discovery-sell-value"]').textContent.trim(),
      profit: row.querySelector('[data-test="discovery-profit"]').textContent.trim()
    }))
  )

  // The page opens on highest recipe level first; nothing is added to or removed from the result.
  const expected = [...table.rows]
    .sort((left, right) => right.minRating - left.minRating || left.recipeId - right.recipeId)
    .map((row) => ({
      name: row.outputName ?? `Item #${row.outputItemId}`,
      recipe: `recipe ${row.recipeId}`,
      level: String(row.minRating),
      craftable: count(row.craftableCount),
      buyCost: money(row.buyCostCopper),
      sellValue: money(row.totalSellValueCopper),
      profit: signedMoney(row.profitCopper)
    }))

  assert.equal(rendered.length, table.rows.length, 'The page listed a different number of recipes')
  assert.deepEqual(rendered, expected, 'The rendered rows do not match the response')

  const losses = table.rows.filter((row) => row.profitCopper !== null && row.profitCopper <= 0)
  console.log(
    JSON.stringify({
      route: discoveryPath,
      status: 200,
      scope: table.scope,
      inventoryCharacterName: table.inventoryCharacterName,
      rowCount: table.rowCount,
      rendered: rendered.length,
      nonPositiveProfitCandidatesKept: losses.length
    })
  )
}

/** One selected recipe's fresh detail, compared against the response it came from. */
async function checkDetail(result, table, candidate) {
  const { body, request } = await successful(result)

  // The detail was asked for with the inputs the *table response* echoed, inventory character included.
  assert.equal(request.recipeId, candidate.recipeId)
  assert.deepEqual(request.calculation.scope, {
    discipline: table.scope.discipline,
    characterName: table.scope.characterName,
    rating: table.scope.rating
  })
  assert.equal(
    request.calculation.inventoryCharacterName ?? null,
    table.inventoryCharacterName,
    'The detail request did not carry the table\'s echoed inventory character'
  )
  assert.equal(body.recipeId, candidate.recipeId)
  assert.deepEqual(body.calculation.scope, table.scope)
  assert.equal(body.calculation.inventoryCharacterName, table.inventoryCharacterName)
  assert.equal(body.treeBasis, 'SINGLE_OUTPUT_REQUIREMENT')
  assert.equal(
    body.treeStatus,
    'AVAILABLE',
    'Choose a candidate the live calculation can resolve, or widen the live data'
  )
  assert.ok(body.tree, 'An AVAILABLE detail carried no tree')

  await locator('resolution-tree').waitFor()
  // Every requirement the backend returned, in its own order, with nothing cut off or reordered.
  assert.deepEqual(
    await locator('node-name').allTextContents().then((texts) => texts.map((text) => text.trim())),
    names(body.tree),
    'The rendered tree does not match the response'
  )
  // DOMAIN_SPEC 2.1.1: the fresh row's own figures are not printed beside the table row's, and the
  // table row's own total is untouched by the fresh answer.
  assert.equal(await locator('resolution-total-profit').count(), 0)
  assert.equal(await locator('resolution-row').count(), 0)
  assert.equal(
    (await locator('discovery-detail-total-profit').textContent()).trim(),
    signedMoney(candidate.totalProfitCopper),
    'The fresh calculation overwrote the table row\'s own total'
  )
  assert.equal(
    await page.locator('[data-test="node-children"][open]').count(),
    0,
    'Ingredient groups did not start collapsed'
  )

  console.log(
    JSON.stringify({
      route: detailPath,
      status: 200,
      recipeId: candidate.recipeId,
      nodes: names(body.tree).length,
      treeBasis: body.treeBasis,
      consistency: body.consistency,
      inventoryCharacterName: body.calculation.inventoryCharacterName
    })
  )
}

try {
  const initial = nextResponse(discoveryPath)
  await page.goto(`${origin}/#/discovery`, { waitUntil: 'domcontentloaded' })

  // Waited for first: while the selector is still being read the page is in its loading state, and
  // none of the states below is established yet.
  await page.waitForSelector(
    '[data-test="discovery-table"], [data-test="discovery-empty"], ' +
      '[data-test="discovery-no-character"], [data-test="discovery-request-error"], ' +
      '[data-test="discovery-selector-error"]'
  )

  // No character discipline means there is nothing to compare; the page correctly sends nothing.
  if ((await locator('discovery-no-character').count()) > 0) {
    throw new Error(
      'The live selector offers no character discipline, so no calculation was made. Synchronize the ' +
        'account first — this check will not do it.'
    )
  }

  const first = await successful(initial)
  let table = first.body
  checkContract(table, first.request)
  await settled()
  assert.ok(
    table.rows.length > 0,
    'The live calculation returned no discoverable recipe, so there is nothing to compare'
  )
  await checkList(table)

  // A candidate the live calculation actually resolved, chosen by its own supplied fields.
  const candidate = table.rows.find(
    (row) => row.resultAvailable && row.outputName !== null && row.craftableCount > 0
  )
  assert.ok(candidate, 'The live data needs one available candidate with a name and a craftable count')

  await locator('discovery-search').fill(candidate.outputName)
  const detail = nextResponse(detailPath)
  await page.getByRole('button', { name: `${candidate.outputName} recipe ${candidate.recipeId}`, exact: true }).click()
  await checkDetail(detail, table, candidate)

  // A reload keeps the scope, the inventory character and the settings, and asks for the detail again.
  await locator('discovery-search').fill('')
  const reload = nextResponse(discoveryPath)
  const reloadedDetail = nextResponse(detailPath)
  await locator('discovery-reload').click()
  const reloaded = await successful(reload)
  checkContract(reloaded.body, reloaded.request)
  assert.deepEqual(reloaded.request.scope, {
    discipline: table.scope.discipline,
    characterName: table.scope.characterName,
    rating: table.scope.rating
  })
  assert.equal(reloaded.request.inventoryCharacterName ?? null, table.inventoryCharacterName)
  table = reloaded.body
  await settled()
  const stillSelected = table.rows.find((row) => row.recipeId === candidate.recipeId)
  if (stillSelected === undefined) {
    console.log('The reloaded calculation no longer offers that recipe; its detail is cleared by design.')
  } else {
    await checkDetail(reloadedDetail, table, stillSelected)
  }
  await checkList(table)

  console.log(
    'Discovery live smoke PASSED: listed rows, one fresh tree and a reload matched the actual responses.'
  )
} catch (error) {
  // Reported here rather than left to the process: a closed browser rejects every wait still in
  // flight, and one of those would otherwise be the only message printed.
  console.error(`\nDiscovery live smoke FAILED: ${error?.message ?? error}`)
  if (error?.stack !== undefined) console.error(error.stack)
  process.exitCode = 1
} finally {
  await browser.close()
}
