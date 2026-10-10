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
  assert.equal(typeof body.scope.discipline, 'string', 'Missing echoed discipline')
  assert.equal(typeof body.scope.characterName, 'string', 'Missing echoed character')
  assert.equal(typeof body.scope.rating, 'number', 'Missing echoed rating')
  assert.equal(typeof body.settings.allowDailyCrafts, 'boolean', 'Missing echoed daily value')
  // `CraftingDiscoveryResponse` carries one combined character/discipline scope: that character is
  // both the crafter and the owned-material inventory, so there is no second inventory character to
  // echo. Requiring one here is what made this check fail its own contract step against a current
  // backend; it is now the *presence* of the removed field that means an old build is serving this
  // origin (`web.dto.CraftingDiscoveryResponse`, `CraftingDiscoveryApiControllerTest`).
  assert.ok(
    !Object.hasOwn(body, 'inventoryCharacterName'),
    'The response still carries inventoryCharacterName, which this route no longer has: ' +
      'rebuild/restart the backend serving this origin'
  )
  if (request !== undefined && request !== null) {
    // The route does not accept the fixed daily setting, and has no inventory character to send.
    assert.equal(request.settings?.allowDailyCrafts, undefined, 'The page sent the fixed daily setting')
    assert.ok(
      !Object.hasOwn(request, 'inventoryCharacterName'),
      'The page sent a separate inventory character the route does not accept'
    )
  }
  for (const row of body.rows) {
    assert.ok(Object.hasOwn(row, 'totalSellValueCopper'), 'Missing gross total on a row')
    assert.ok(Object.hasOwn(row, 'minRating'), 'Missing recipe level on a row')
    assert.ok(Object.hasOwn(row, 'iconUrl'), 'Missing output item icon URL on a row')
  }
}

/**
 * How many of the calculation's rows the page is showing: its own changeable maximum, or all of them
 * while "Show all" is ticked (DOMAIN_SPEC 2.1.1, initially 250).
 *
 * Read from the page's own controls rather than assumed, because a live calculation returns far more
 * discoverable recipes than the maximum: this check compares the rows that are actually on screen,
 * and a result larger than the maximum is the page working as specified, not a mismatch.
 */
async function displayedCount(rowCount) {
  if (await locator('discovery-show-all').isChecked()) return rowCount
  const maximum = Number(await locator('discovery-max-displayed').inputValue())
  assert.ok(
    Number.isInteger(maximum) && maximum > 0,
    `The page's maximum displayed count is not a count: ${maximum}`
  )
  return Math.min(maximum, rowCount)
}

/**
 * The rendered list, compared against the rows the response actually returned.
 *
 * One entry per column `DiscoveryTable.vue` renders, and the response field each column is supplied
 * from: the recipe level, the materials to buy, the sell value and the *total* profit. There is no
 * craftable-count column and no per-craft profit column on this table — Discovery compares one
 * discovery craft — so a cell for either is not something this check may read.
 */
async function checkList(table) {
  const rendered = await page.$$eval('[data-test="discovery-row"]', (rows) =>
    rows.map((row) => ({
      name: row.querySelector('.recipe-name').textContent.trim(),
      recipe: row.querySelector('.recipe-ids').textContent.trim(),
      level: row.querySelector('[data-test="discovery-level"]').textContent.trim(),
      buyCost: row.querySelector('[data-test="discovery-buy-cost"]').textContent.trim(),
      sellValue: row.querySelector('[data-test="discovery-sell-value"]').textContent.trim(),
      profit: row.querySelector('[data-test="discovery-profit"]').textContent.trim()
    }))
  )

  // The page opens on highest recipe level first; nothing is added to or removed from the result, and
  // what the maximum leaves out is cut from the end of that order rather than chosen some other way.
  const visible = await displayedCount(table.rows.length)
  const expected = [...table.rows]
    .sort((left, right) => right.minRating - left.minRating || left.recipeId - right.recipeId)
    .slice(0, visible)
    .map((row) => ({
      name: row.outputName ?? `Item #${row.outputItemId}`,
      recipe: `recipe ${row.recipeId}`,
      level: String(row.minRating),
      buyCost: money(row.buyCostCopper),
      sellValue: money(row.totalSellValueCopper),
      profit: signedMoney(row.totalProfitCopper)
    }))

  assert.equal(
    rendered.length,
    visible,
    'The page listed a different number of recipes than its own maximum allows'
  )
  assert.deepEqual(rendered, expected, 'The rendered rows do not match the response')

  // A loss does not disqualify a discovery, so these rows have to still be listed; the count is the
  // one the column above shows, which is the total.
  const losses = table.rows.filter(
    (row) => row.totalProfitCopper !== null && row.totalProfitCopper <= 0
  )
  console.log(
    JSON.stringify({
      route: discoveryPath,
      status: 200,
      scope: table.scope,
      settings: table.settings,
      rowCount: table.rowCount,
      displayedMaximum: visible,
      rendered: rendered.length,
      nonPositiveProfitCandidatesKept: losses.length
    })
  )
}

/** One selected recipe's fresh detail, compared against the response it came from. */
async function checkDetail(result, table, candidate) {
  const { body, request } = await successful(result)

  // The detail was asked for with the inputs the *table response* echoed: Discovery's one combined
  // character/discipline scope, which is also the owned-material context, and the four settings the
  // route accepts — no second inventory character, and not the daily value the route fixes itself.
  assert.equal(request.recipeId, candidate.recipeId)
  assert.deepEqual(request.calculation.scope, {
    discipline: table.scope.discipline,
    characterName: table.scope.characterName,
    rating: table.scope.rating
  })
  assert.deepEqual(
    request.calculation.settings,
    {
      useOwnMats: table.settings.useOwnMats,
      allowBuying: table.settings.allowBuying,
      listingSell: table.settings.listingSell,
      listingBuy: table.settings.listingBuy
    },
    'The detail request did not carry the settings the table response echoed'
  )
  assert.ok(
    !Object.hasOwn(request.calculation, 'inventoryCharacterName'),
    'The detail request sent a separate inventory character'
  )
  assert.equal(body.recipeId, candidate.recipeId)
  // `TARGET_ARCHITECTURE.md` 13.4: the browser only accepts a detail whose echoed calculation is the
  // one on screen, so these two are what tie this tree to the table above it.
  assert.deepEqual(body.calculation.scope, table.scope)
  assert.deepEqual(body.calculation.settings, table.settings)
  assert.ok(
    !Object.hasOwn(body.calculation, 'inventoryCharacterName'),
    'The echoed calculation still carries inventoryCharacterName: rebuild/restart the backend'
  )
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
    (await locator('discovery-detail-profit').textContent()).trim(),
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
      calculation: body.calculation
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

  // A reload keeps the scope and the settings, and asks for the detail again. `checkContract` is what
  // establishes that the reloaded request carries no inventory character and no fixed daily setting.
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
  assert.deepEqual(reloaded.body.scope, table.scope, 'The reload calculated a different scope')
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
