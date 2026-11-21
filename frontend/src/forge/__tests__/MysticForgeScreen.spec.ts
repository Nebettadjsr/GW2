import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MysticForgeApi } from '@/api/mysticForgeApi'
import type { ForgeCatalogue, ForgeCraftingStep, ForgeNode, ForgeRecipePart, ForgeProject, ForgeRecipeRow } from '@/api/types'
import { destinationForHash, DESTINATIONS } from '@/shell/destinations'
import MysticForgeScreen from '../MysticForgeScreen.vue'
import { parseTarget, searchText, TARGET_DEBOUNCE_MS } from '../useMysticForge'

// Literal, independent fixture values (not derived from production code).
const row = (o: Partial<ForgeRecipeRow>): ForgeRecipeRow => ({
  recipeId: -1, outputItemId: 100, name: 'Item', iconUrl: null, itemType: 'Weapon', itemSubtype: 'Sword',
  category: 'WEAPONS', outcome: 'FIXED', outputCount: 1, expectedMean: null, expectedMax: null,
  buyUnitCopper: 12345, sellUnitCopper: 23456, sourcePage: null, sourceRevision: null, wikiUrl: null, ingredientKinds: 4, ...o
})
const catalogue: ForgeCatalogue = {
  status: 'OK', source: 'wiki', license: 'CC', recipeCount: 4, referenceCount: 1,
  recipes: [
    row({ recipeId: -1, outputItemId: 101, name: 'Sunrise Longbow', itemSubtype: 'LongBow' }),
    row({ recipeId: -2, outputItemId: 102, name: 'Shadow Shortbow', itemSubtype: 'ShortBow' }),
    row({ recipeId: -3, outputItemId: 103, name: 'Gift Coat', itemType: 'Armor', itemSubtype: 'Coat', category: 'ARMOR', buyUnitCopper: null, sellUnitCopper: null }),
    row({ recipeId: -4, outputItemId: 19745, name: 'Gossamer Scrap', itemType: 'CraftingMaterial', itemSubtype: null, category: 'MATERIALS', outcome: 'EXPECTED', outputCount: 10, expectedMean: '18.92', expectedMax: 40 }),
    row({ recipeId: -5, outputItemId: 104, name: 'Mystic Clover', itemType: 'CraftingMaterial', itemSubtype: null, category: 'MATERIALS', outcome: 'REFERENCE_RANDOM', outputCount: 0 })
  ]
}
const item = (id: number, name: string) => ({ itemId: id, name, iconUrl: null, wikiUrl: `https://wiki.guildwars2.com/wiki/${name}` })
const node = (o: Partial<ForgeNode>): ForgeNode => ({
  item: item(1, 'X'), needed: 1, fromOwned: 0, fromSurplus: 0, toCraft: 0, toAcquire: 1, recipeId: null, recipeSource: null,
  crafts: 0, minRating: 0, disciplines: null, cutoff: null, children: [], recipeKnown: null, performable: true,
  assignedCharacter: null, hint: null, produced: 0, newSurplus: 0, ...o
})
function project(target: number, o: Partial<ForgeProject> = {}): ForgeProject {
  return {
    recipeId: -1, output: item(101, 'Sunrise Longbow'), outcome: 'FIXED', targetKind: 'OUTPUT_QUANTITY', requestedTarget: target,
    batches: target, guaranteedOutput: target, complete: false, incompleteReason: null, walletKnown: true, knownGoldCopper: 777700,
    goldCostPartial: true, unpricedItems: [item(9, 'Gift of Fortune')],
    tree: node({ item: item(101, 'Sunrise Longbow'), needed: target, toCraft: target, toAcquire: 0, recipeId: -1, children: [
      node({ item: item(5, 'Locked Plank'), needed: 3, recipeId: 55, recipeSource: 'CRAFT', recipeKnown: false, performable: false,
        children: [node({ item: item(6, 'Ancient Wood Log'), needed: 9, fromOwned: 4, toAcquire: 5 })] })] }),
    shopping: [{ item: item(6, 'Ancient Wood Log'), needed: 9, fromOwned: 4, toAcquire: 5, unitCopper: 100, knownCopper: 500,
      source: 'TRADING_POST', purchases: 0, currencyCosts: [], hint: null }],
    currencies: [{ currencyId: 23, name: 'Spirit Shards', iconUrl: null, wikiUrl: 'https://wiki.guildwars2.com/wiki/Spirit_Shard', needed: 50, owned: null, missing: null }],
    note: null, seed: null,
    economics: { outputUnitSellCopper: 100, expectedGrossProceedsCopper: 3684, feeCopper: 553, expectedNetProceedsCopper: 3131, knownShoppingCopper: 1300,
      goldInputCopper: 1300, currencyInputs: [], unpricedInputs: [], marginOnShoppingCopper: null, marginCopper: -1831, conditional: true, fixedOutput: false },
    notes: null, freshness: null,
    settings: { priceMode: 'INSTANT_BUY', useOwn: false, valueOwn: false, daily: 'CRAFT', promotions: false, reduce: 'NO' },
    recipeTree: null, ownedItems: [], materialProgress: null, baseIngredients: [], craftingSteps: [],
    ownValue: null, clover: null, rootOwned: 0, comparison: null, ...o
  }
}

const part = (itemId: number, name: string, quantity: number, o: Partial<ForgeRecipePart> = {}): ForgeRecipePart => ({
  item: item(itemId, name), quantity, children: [], currencies: [], copper: null, attempts: null, approximate: false, cutoff: null,
  acquired: 0, progressPercent: 0, coveredByParent: false, ...o
})
const step = (itemId: number, name: string, o: Partial<ForgeCraftingStep> = {}): ForgeCraftingStep => ({
  item: item(itemId, name), recipeId: null, recipeSource: null, crafts: 0, produced: 0, toAcquire: 0, newSurplus: 0, disciplines: null,
  hint: null, recipeKnown: null, performable: true, assignedCharacter: null, cutoff: null, root: false, ingredients: [], ...o
})
let projectCalls: { recipeId: number; target: number; settings?: unknown }[]
let catalogueCalls: number
let pendingProject: ((p: ForgeProject) => void)[]
function fakeApi(manual = false): MysticForgeApi {
  return {
    async loadCatalogue() { catalogueCalls++; return catalogue },
    loadProject(recipeId, target, settings) {
      projectCalls.push({ recipeId, target, settings })
      if (manual) return new Promise((resolve) => { pendingProject.push(resolve) })
      return Promise.resolve(project(target, settings ? { settings } : {}))
    }
  }
}
beforeEach(() => { projectCalls = []; catalogueCalls = 0; pendingProject = []; vi.useFakeTimers() })
afterEach(() => { vi.useRealTimers() })

async function mounted(manual = false) {
  const w = mount(MysticForgeScreen, { props: { api: fakeApi(manual) }, attachTo: document.body })
  await flushPromises()
  return w
}

describe('navigation and helpers', () => {
  it('places Mystic Forge between Crafting Discovery and Ecto Salvage', () => {
    const ids = DESTINATIONS.map((d) => d.id)
    expect(ids.indexOf('forge')).toBe(ids.indexOf('discovery') + 1)
    expect(ids.indexOf('ecto')).toBe(ids.indexOf('forge') + 1)
    expect(destinationForHash('#/mystic-forge').label).toBe('Mystic Forge')
  })
  it('parses only whole positive targets and searches real type text', () => {
    expect(parseTarget('')).toBeNull()
    expect(parseTarget('0')).toBeNull()
    expect(parseTarget('12')).toBe(12)
    expect(parseTarget('1.5')).toBeNull()
    expect(searchText(catalogue.recipes[0]!)).toContain('longbow')
  })
})

describe('equipment detail layout', () => {
  it('applies each calculation choice through the backend after the debounce, keeps them across Back, and the amount stays connected', async () => {
    const w = await mounted()
    await w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Sunrise Longbow'))!.trigger('click')
    await flushPromises()
    expect(w.get('[data-test="forge-settings"]').text()).not.toContain('preview')
    await w.get('[data-test="forge-setting-price"]').setValue('BUY_ORDER')
    await w.get('[data-test="forge-setting-use-own"]').setValue('YES')
    await w.get('[data-test="forge-setting-reduce"]').setValue('TRADABLE')
    expect(projectCalls).toHaveLength(1) // nothing posts before the debounce
    await vi.advanceTimersByTimeAsync(TARGET_DEBOUNCE_MS + 1)
    expect(projectCalls).toHaveLength(2)
    expect(projectCalls.at(-1)).toEqual({ recipeId: -1, target: 1, settings: { priceMode: 'BUY_ORDER', useOwn: true, valueOwn: false, daily: 'CRAFT', promotions: false, reduce: 'TRADABLE' } })
    await w.get('[data-test="forge-setting-daily"]').setValue('BUY')
    await w.get('[data-test="forge-setting-promotions"]').setValue('YES')
    await w.get('[data-test="forge-setting-value-own"]').setValue('YES')
    await vi.advanceTimersByTimeAsync(TARGET_DEBOUNCE_MS + 1)
    expect(projectCalls.at(-1)!.settings).toEqual({ priceMode: 'BUY_ORDER', useOwn: true, valueOwn: true, daily: 'BUY', promotions: true, reduce: 'TRADABLE' })
    const help = w.get('button[aria-controls="forge-reduction-help"]')
    await help.trigger('click')
    await help.trigger('keydown', { key: 'Escape' })
    expect(w.find('#forge-reduction-help').exists()).toBe(false)
    await w.get('[data-test="forge-target"]').setValue('2')
    await vi.advanceTimersByTimeAsync(TARGET_DEBOUNCE_MS + 1)
    expect(projectCalls.at(-1)).toMatchObject({ recipeId: -1, target: 2, settings: { daily: 'BUY' } })
    w.unmount()
  })

  it('never paints an answer planned with other settings', async () => {
    const w = await mounted(true)
    await w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Sunrise Longbow'))!.trigger('click')
    await flushPromises()
    await w.get('[data-test="forge-setting-price"]').setValue('BUY_ORDER')
    pendingProject.shift()!(project(1)) // late answer for the default settings
    await flushPromises()
    expect(w.find('[data-test="forge-known-gold"]').exists()).toBe(false)
    w.unmount()
  })

  it('puts owned use and missing quantities in separate boxes, and lists steps in dependency order', async () => {
    // The UI renders the backend's instruction list and owned list; it derives neither from the tree.
    const steps = [
      step(5, 'Locked Plank', { recipeId: 55, recipeSource: 'CRAFT', recipeKnown: false, performable: false, crafts: 3, produced: 3,
        ingredients: [{ item: item(6, 'Ancient Wood Log'), quantity: 9 }] }),
      step(101, 'Sunrise Longbow', { recipeId: -1, crafts: 1, produced: 1, root: true })]
    const api: MysticForgeApi = { async loadCatalogue() { return catalogue }, async loadProject(_id, target) {
      return project(target, { craftingSteps: steps, ownedItems: [{ item: item(6, 'Ancient Wood Log'), count: 4, locations: ['Bank 4'], valueCopper: 400 }] }) } }
    const w = mount(MysticForgeScreen, { props: { api } })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Sunrise Longbow'))!.trigger('click')
    await flushPromises()
    expect(w.get('[data-test="forge-owned"]').text()).toContain('Own materials are not used.')
    expect(w.get('[data-test="forge-owned-line"]').get('strong').text()).toBe('4')
    expect(w.get('[data-test="forge-owned-value"]').text()).toBe('0c') // Value own = No: backend value is not shown
    expect(w.get('[data-test="forge-shopping-line"]').get('strong').text()).toBe('5')
    const rendered = w.findAll('[data-test="forge-crafting-step"]')
    expect(rendered).toHaveLength(2)
    expect(w.get('[data-test="forge-crafting-steps"]').text()).not.toContain('Acquire')
    expect(rendered[0]!.text()).toContain('Craft3Locked Plank')
    expect(rendered[0]!.text()).toContain('Recipe not unlocked')
    expect(rendered[1]!.text()).toContain('Craft1Sunrise Longbow')
    expect(w.get('[data-test="forge-shopping-currency"]').text()).toContain('wallet unknown')
    w.unmount()
  })

  it('shows the estimated Clover attempt and a useful Obsidian merchant step, never a generic acquire step', async () => {
    const api: MysticForgeApi = { async loadCatalogue() { return catalogue }, async loadProject(_id, target) {
      return project(target, { clover: { missing: 2, attempts: 7, percent: 31 }, craftingSteps: [
        step(19675, 'Mystic Clover', { toAcquire: 2, crafts: 7, hint: null }),
        step(19925, 'Obsidian Shard', { recipeId: -1_000_019_925, recipeSource: 'VENDOR', crafts: 3, produced: 3, toAcquire: 3,
          hint: 'Tactician Deathstrider, Temple of Balthazar: 2,100 Karma each.' }),
        step(101, 'Sunrise Longbow', { recipeId: -1, crafts: 1, produced: 1, root: true })] })
    } }
    const w = mount(MysticForgeScreen, { props: { api } })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Sunrise Longbow'))!.trigger('click')
    await flushPromises()
    const steps = w.findAll('[data-test="forge-crafting-step"]')
    expect(steps[0]!.text()).toContain('estimated')
    expect(steps[0]!.text()).toContain('≈7 attempts')
    expect(steps[1]!.text()).toContain('Buy')
    expect(steps[1]!.text()).toContain('Obsidian Shard')
    expect(w.get('[data-test="forge-crafting-steps"]').text()).not.toContain('Acquire')
    expect(w.get('[data-test="forge-clover-estimate"]').text()).toContain('7 Mystic Forge attempts')
    w.unmount()
  })

  it('shows a Material with its base recipe, expected yield and batches, and without equipment-only settings or steps', async () => {
    // The backend decides the estimated batch count for the desired output (literal oracle: 250 -> 43 batches).
    const api: MysticForgeApi = { async loadCatalogue() { return catalogue }, loadProject(recipeId, target, settings) {
      projectCalls.push({ recipeId, target, settings })
      return Promise.resolve(project(target, { batches: 43, targetKind: 'OUTPUT_QUANTITY' }))
    } }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Gossamer Scrap'))!.trigger('click')
    await flushPromises()
    expect(projectCalls.at(-1)!.target).toBe(250) // Materials default to a desired output of 250
    expect(w.find('[data-test="forge-base-recipe"]').exists()).toBe(true)
    for (const id of ['daily', 'promotions', 'reduce']) expect(w.find(`[data-test="forge-setting-${id}"]`).exists()).toBe(false)
    for (const id of ['price', 'use-own', 'value-own']) expect(w.find(`[data-test="forge-setting-${id}"]`).exists()).toBe(true)
    expect(w.find('[data-test="forge-crafting-steps"]').exists()).toBe(false)
    expect(w.get('[data-test="forge-yield"]').text()).toContain('10–40')
    expect(w.get('[data-test="forge-estimated-batches"]').text()).toBe('≈43')
    w.unmount()
  })
})

describe('Mystic Forge overview', () => {
  it('loads the catalogue once and never plans for overview, tab or search', async () => {
    const w = await mounted()
    expect(catalogueCalls).toBe(1)
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(5)
    await w.get('[data-test="forge-filter-category"]').setValue('ARMOR')
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(1)
    await w.get('[data-test="forge-filter-category"]').setValue('ALL')
    await w.get('[data-test="forge-search"]').setValue('bow')
    expect(w.findAll('[data-test="forge-row"]').map((r) => r.text())).toEqual([
      expect.stringContaining('Shadow Shortbow'), expect.stringContaining('Sunrise Longbow')])
    await w.get('[data-test="forge-search"]').setValue('103')
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(1)
    expect(projectCalls).toHaveLength(0)
  })
  it('labels quotes honestly and shows reference clovers without yield or price', async () => {
    const w = await mounted()
    const rows = w.findAll('[data-test="forge-row"]')
    const longbow = rows.find((r) => r.text().includes('Sunrise Longbow'))!
    // Shared coin amounts expose their value through accessible semantics: buy = ask 2g34s56c, sell = bid 1g23s45c.
    expect(longbow.findAll('[data-test="forge-row-prices"] [role="img"]').map(c => c.attributes('aria-label'))).toEqual(['2g 34s 56c', '1g 23s 45c'])
    expect(longbow.get('[data-test="forge-row-prices"]').text()).toContain('Instant buy')
    const coat = rows.find((r) => r.text().includes('Gift Coat'))!
    expect(coat.find('[data-test="forge-row-prices"]').exists()).toBe(false) // no repeated unavailable boilerplate
    expect(coat.text()).not.toContain('price unavailable')
    const clover = rows.find((r) => r.text().includes('Mystic Clover'))!
    expect(clover.find('[data-test="forge-row-prices"]').exists()).toBe(false)
  })
})

describe('selected project', () => {
  it('opens exactly one recipe with default target 1 and shows full path, currencies and partial cost', async () => {
    const recipeTree = part(101, 'Sunrise Longbow', 1, { children: [part(0, 'Group', 1, { children: [
      part(5, 'Locked Plank', 3, { children: [part(6, 'Ancient Wood Log', 9, { acquired: 4 })] })] })] })
    const api: MysticForgeApi = { async loadCatalogue() { return catalogue }, loadProject(recipeId, target, settings) {
      projectCalls.push({ recipeId, target, settings })
      return Promise.resolve(project(target, { settings: settings!, recipeTree }))
    } }
    const w = mount(MysticForgeScreen, { props: { api } })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]').find((r) => r.text().includes('Sunrise Longbow'))!.trigger('click')
    await flushPromises()
    expect(projectCalls).toEqual([{ recipeId: -1, target: 1, settings: { priceMode: 'INSTANT_BUY', useOwn: false, valueOwn: false, daily: 'CRAFT', promotions: false, reduce: 'NO' } }])
    expect(w.get('[data-test="forge-known-gold"]').text()).toContain('77g 77s 0c')
    expect(w.get('[data-test="forge-known-gold"]').text()).toContain('partial')
    expect(w.get('[data-test="forge-unpriced"]').text()).toContain('Gift of Fortune')
    expect(w.get('[data-test="forge-currency"]').get('strong').text()).toBe('50')
    expect(w.get('[data-test="forge-currency"]').text()).toContain('Spirit Shards')
    expect(w.get('[data-test="forge-currency"]').text()).toContain('Wallet balance unknown')
    expect(w.get('[data-test="forge-tree"]').text()).toContain('Locked Plank')
    expect(w.get('[data-test="forge-tree"]').text()).toContain('Ancient Wood Log')
    expect(w.get('[data-test="forge-margin"]').text()).toBe('-18s 31c')
    expect(w.get('[data-test="forge-margin"]').classes()).toContain('money--loss')
  })
  it('target change posts only the selected recipe once, after the debounce, and never an empty draft', async () => {
    const w = await mounted()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    projectCalls.length = 0
    const input = w.get('[data-test="forge-target"]')
    await input.setValue('')
    await input.setValue('3')
    await input.setValue('25')
    vi.advanceTimersByTime(TARGET_DEBOUNCE_MS + 1)
    await flushPromises()
    expect(projectCalls).toEqual([{ recipeId: -3, target: 25, settings: { priceMode: 'INSTANT_BUY', useOwn: false, valueOwn: false, daily: 'CRAFT', promotions: false, reduce: 'NO' } }])
  })
  it('discards a late answer for an earlier target and for a closed selection', async () => {
    const w = await mounted(true)
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    const input = w.get('[data-test="forge-target"]')
    await input.setValue('7')
    vi.advanceTimersByTime(TARGET_DEBOUNCE_MS + 1)
    await flushPromises()
    expect(pendingProject).toHaveLength(2)
    pendingProject[1]!(project(7))
    await flushPromises()
    pendingProject[0]!(project(1))
    await flushPromises()
    expect(w.get('[data-test="forge-output"]').text()).toContain('7')
    await w.get('[data-test="forge-back"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(false)
  })
  it('keeps list state and refocuses the opened row on back', async () => {
    const w = await mounted()
    await w.get('[data-test="forge-search"]').setValue('bow')
    await w.findAll('[data-test="forge-row"]')[1]!.trigger('click')
    await flushPromises()
    await w.get('[data-test="forge-back"]').trigger('click')
    await flushPromises()
    expect((w.get('[data-test="forge-search"]').element as HTMLInputElement).value).toBe('bow')
    expect(document.activeElement?.getAttribute('data-recipe-id')).toBe('-1')
    w.unmount()
  })
})

describe('first use source refresh and cold catalogue', () => {
  const credentialPending = (target: number): ForgeProject => project(target, {
    tree: null, output: null, // the real 202 carries no output

    freshness: { requestId: 'r', calculationId: 'c', evaluatedAt: '', provisional: true, resultAvailable: false, accountSources: [],
      tradingPost: {} as never, outstandingWork: [],
      failures: [{ kind: 'ACCOUNT', taskId: null, taskStatusUrl: null, code: 'CREDENTIAL_REQUIRED', message: 'key needed', retryable: true }] }
  })
  function sourceApi(states: string[]) {
    let ready = false
    const calls = { project: 0, start: 0, read: 0 }
    const api: MysticForgeApi = {
      async loadCatalogue() { return catalogue },
      async loadProject(_id, target) { calls.project++; return ready ? project(target) : credentialPending(target) },
      async startSourceRefresh() { calls.start++; return { statusUrl: '/t/1' } as never },
      async readSourceTask() {
        const state = states[Math.min(calls.read++, states.length - 1)]!
        if (state === 'SUCCEEDED') ready = true
        return { state, failure: state === 'FAILED' ? { message: 'boom' } : null } as never
      }
    }
    return { api, calls }
  }

  it('a keyless 202 starts one keyed refresh task, then rereads only the selected project', async () => {
    const { api, calls } = sourceApi(['RUNNING', 'SUCCEEDED'])
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    expect(calls.start).toBe(1)
    expect(w.find('[data-test="forge-refreshing"]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(2100)
    await flushPromises()
    expect(calls.start).toBe(1)
    expect(calls.project).toBe(2)
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(true)
    expect(w.find('[data-test="forge-source-failed"]').exists()).toBe(false)
    w.unmount()
  })

  it('a failed task stops with a truthful status and Retry starts a new attempt', async () => {
    const { api, calls } = sourceApi(['FAILED', 'SUCCEEDED'])
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    expect(w.get('[data-test="forge-source-failed"]').text()).toContain('boom')
    expect(w.find('[data-test="forge-pending"]').exists()).toBe(false)
    const startedBefore = calls.start
    await vi.advanceTimersByTimeAsync(60_000)
    expect(calls.start).toBe(startedBefore)
    await w.get('[data-test="forge-retry"]').trigger('click')
    await vi.advanceTimersByTimeAsync(100)
    await flushPromises()
    expect(calls.start).toBe(startedBefore + 1)
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(true)
    w.unmount()
  })

  const staleUsable = (target: number): ForgeProject => project(target, {
    freshness: { requestId: 'r', calculationId: 'c', evaluatedAt: '', provisional: true, resultAvailable: true,
      accountSources: [{ source: 'BANK', fetchedAt: '2020-01-01T00:00:00Z', state: 'STALE' }],
      tradingPost: {} as never, outstandingWork: [], failures: [] }
  })

  it('a usable but STALE plan stays visible, runs one keyed task, then rereads to a fresh plan', async () => {
    let ready = false
    const calls = { project: 0, start: 0 }
    const api: MysticForgeApi = {
      async loadCatalogue() { return catalogue },
      async loadProject(_id, target) { calls.project++; return ready ? project(target) : staleUsable(target) },
      async startSourceRefresh() { calls.start++; return { statusUrl: '/t/1', coversRequest: true } as never },
      async readSourceTask() { ready = true; return { state: 'SUCCEEDED', failure: null } as never }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    expect(calls.start).toBe(1)
    expect(calls.project).toBe(2)
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(calls.start).toBe(1)
    w.unmount()
  })

  it('a task that succeeds but leaves the plan stale is recovered a bounded number of times, then stops', async () => {
    const calls = { start: 0 }
    const api: MysticForgeApi = {
      async loadCatalogue() { return catalogue },
      async loadProject(_id, target) { return staleUsable(target) },
      async startSourceRefresh() { calls.start++; return { statusUrl: '/t/1', coversRequest: true } as never },
      async readSourceTask() { return { state: 'SUCCEEDED', failure: null } as never }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await vi.advanceTimersByTimeAsync(120_000)
    await flushPromises()
    expect(calls.start).toBe(2)
    w.unmount()
  })

  it('after the recovery cap a still-stale answer stops with a visible Retry, keeps the plan, and Retry resets the bound', async () => {
    const calls = { start: 0, load: 0 }
    const api: MysticForgeApi = {
      async loadCatalogue() { return catalogue },
      async loadProject(_id, target) { calls.load++; return staleUsable(target) },
      async startSourceRefresh() { calls.start++; return { statusUrl: '/t/1', coversRequest: true } as never },
      async readSourceTask() { return { state: 'SUCCEEDED', failure: null } as never }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await vi.advanceTimersByTimeAsync(120_000)
    await flushPromises()
    expect(calls.start).toBe(2)
    expect(w.find('[data-test="forge-source-failed"]').exists()).toBe(true)
    expect(w.find('[data-test="forge-retry"]').exists()).toBe(true)
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(true)
    const loads = calls.load
    await vi.advanceTimersByTimeAsync(120_000)
    expect(calls.start).toBe(2)
    expect(calls.load).toBe(loads)
    await w.get('[data-test="forge-retry"]').trigger('click')
    await vi.advanceTimersByTimeAsync(120_000)
    await flushPromises()
    expect(calls.start).toBe(4)
    w.unmount()
  })

  it('shows the per-batch yield range only for an EXPECTED recipe', async () => {
    const w = await mounted()
    await w.get('[data-recipe-id="-4"]').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(TARGET_DEBOUNCE_MS + 10)
    await flushPromises()
    expect(w.get('[data-test="forge-yield"]').text()).toContain('10–40')
    expect(w.get('[data-test="forge-yield"]').text()).toMatch(/18[.,]92 expected/)
    await w.get('[data-test="forge-back"]').trigger('click')
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    expect(w.find('[data-test="forge-yield"]').exists()).toBe(false)
    w.unmount()
  })

  it('a narrower task (coversRequest=false) is retried a bounded number of times, then stops with Retry', async () => {
    const calls = { start: 0 }
    const api: MysticForgeApi = {
      async loadCatalogue() { return catalogue },
      async loadProject(_id, target) { return credentialPending(target) },
      async startSourceRefresh() { calls.start++; return { statusUrl: '/t/9', coversRequest: false, retryAfterSeconds: 1 } as never },
      async readSourceTask() { throw new Error('a narrower task is never followed') }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await vi.advanceTimersByTimeAsync(120_000)
    await flushPromises()
    expect(calls.start).toBe(3)
    expect(w.get('[data-test="forge-source-failed"]').text()).toContain('busy')
    expect(w.find('[data-test="forge-retry"]').exists()).toBe(true)
    w.unmount()
  })

  it('a late task completion after the selection was closed cannot update the old view', async () => {
    const { api, calls } = sourceApi(['RUNNING', 'RUNNING', 'SUCCEEDED'])
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    await w.get('[data-test="forge-back"]').trigger('click')
    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()
    expect(calls.project).toBe(1)
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(false)
    w.unmount()
  })

  it('rereads the catalogue while public metadata is completing so a Bow search finds the type', async () => {
    let calls = 0
    const api: MysticForgeApi = {
      async loadCatalogue() {
        calls++
        const warm = calls > 1
        return { ...catalogue, completing: !warm, recipes: catalogue.recipes.map((r) => r.recipeId === -1
          ? { ...r, name: 'Aurene Thing', itemType: warm ? 'Weapon' : null, itemSubtype: warm ? 'LongBow' : null } : r) }
      },
      async loadProject(_id, target) { return project(target) }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.get('[data-test="forge-search"]').setValue('longbow')
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(5100)
    await flushPromises()
    expect(calls).toBe(2)
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(calls).toBe(2)
    w.unmount()
  })
})

describe('vendor shopping and bounded catalogue completion', () => {
  it('shows a vendor purchase batch in Shopping with its currency cost, never as free or a Trading Post price', async () => {
    const api: MysticForgeApi = {
      async loadCatalogue() { return catalogue },
      async loadProject(_id, target) {
        return project(target, { shopping: [
          { item: item(20796, 'Mystic Crystal'), needed: 5, fromOwned: 0, toAcquire: 10, unitCopper: null, knownCopper: null,
            source: 'VENDOR', purchases: 1, currencyCosts: [{ currencyId: 23, name: 'Spirit Shard', count: 1 }], hint: null },
          { item: item(6, 'Ancient Wood Log'), needed: 9, fromOwned: 4, toAcquire: 5, unitCopper: 100, knownCopper: 500,
            source: 'TRADING_POST', purchases: 0, currencyCosts: [], hint: null }] })
      }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    const lines = w.findAll('[data-test="forge-shopping-line"]')
    expect(lines).toHaveLength(2)
    expect(lines[0]!.get('a').text()).toBe('Mystic Crystal')
    expect(lines[0]!.get('strong').text()).toBe('10')
    expect(lines[0]!.get('[data-test="forge-shopping-vendor"]').text()).toContain('Miyani')
    expect(lines[0]!.text()).toContain('1Spirit Shard')
    expect(lines[0]!.text()).not.toContain('price unavailable')
    expect(lines[1]!.get('a').text()).toBe('Ancient Wood Log')
    expect(lines[1]!.get('strong').text()).toBe('5')
    expect(lines[1]!.text()).toContain('Trading Post')
    expect(lines[1]!.text()).toContain('5s 0c')
    w.unmount()
  })

  function completingApi(counter: { calls: number }, doneAfter: number): MysticForgeApi {
    return {
      async loadCatalogue() { counter.calls++; return { ...catalogue, completing: counter.calls < doneAfter } },
      async loadProject(_id, target) { return project(target) }
    }
  }

  it('stops at the reread cap with a visible Retry that keeps rows and query; Retry resets the bound and completes', async () => {
    const counter = { calls: 0 }
    const w = mount(MysticForgeScreen, { props: { api: completingApi(counter, 9) }, attachTo: document.body })
    await flushPromises()
    await w.get('[data-test="forge-search"]').setValue('longbow')
    for (let i = 0; i < 10; i++) { await vi.advanceTimersByTimeAsync(5100) }
    expect(counter.calls).toBe(7) // initial + 6 bounded rereads, then nothing more
    expect(w.find('[data-test="forge-catalogue-stalled"]').exists()).toBe(true)
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(1)
    expect((w.get('[data-test="forge-search"]').element as HTMLInputElement).value).toBe('longbow')
    await w.get('[data-test="forge-catalogue-retry"]').trigger('click')
    await flushPromises()
    for (let i = 0; i < 4; i++) { await vi.advanceTimersByTimeAsync(5100) }
    expect(counter.calls).toBe(9) // retried, completed at call 9, no further rereads
    expect(w.find('[data-test="forge-catalogue-stalled"]').exists()).toBe(false)
    w.unmount()
  })

  it('a cold OTHER equipment row gets its 6 selectors in place when authoritative metadata arrives, keeping state and without extra project requests', async () => {
    let calls = 0
    const cold = (r: ForgeRecipeRow) => r.recipeId === -1 ? { ...r, itemType: null, itemSubtype: null, category: 'OTHER' as const } : r
    const api: MysticForgeApi = {
      async loadCatalogue() {
        calls++
        const warm = calls > 1
        return { ...catalogue, completing: !warm, recipes: warm ? catalogue.recipes : catalogue.recipes.map(cold) }
      },
      loadProject(recipeId, target, settings) {
        projectCalls.push({ recipeId, target, settings })
        return Promise.resolve(project(target, settings ? { settings } : {}))
      }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Sunrise Longbow'))!.trigger('click')
    await flushPromises()
    // Shared preferences are always editable; metadata completion only changes the summary, never the selection or plan.
    expect(w.find('[data-test="forge-settings"]').exists()).toBe(true)
    expect(projectCalls).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(5100)
    await flushPromises()
    expect(calls).toBe(2)
    for (const id of ['price', 'use-own', 'value-own', 'daily', 'promotions', 'reduce']) {
      expect(w.find(`[data-test="forge-setting-${id}"]`).exists()).toBe(true)
    }
    expect(projectCalls).toHaveLength(1) // no extra project/Profit request, selection kept
    expect(w.text()).toContain('Sunrise Longbow')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(calls).toBe(2) // metadata complete: no further polling
    w.unmount()
  })

  it('late metadata that makes an open cold row a Material replans once with the neutralized settings and keeps the detail result', async () => {
    let calls = 0
    const cold = (r: ForgeRecipeRow) => r.recipeId === -4 ? { ...r, itemType: null, itemSubtype: null, category: 'OTHER' as const } : r
    const api: MysticForgeApi = {
      async loadCatalogue() {
        calls++
        const warm = calls > 1
        return { ...catalogue, completing: !warm, recipes: warm ? catalogue.recipes : catalogue.recipes.map(cold) }
      },
      loadProject(recipeId, target, settings) {
        projectCalls.push({ recipeId, target, settings })
        return Promise.resolve(project(target, { recipeId, settings: settings!, batches: 7 }))
      }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Gossamer Scrap'))!.trigger('click')
    await flushPromises()
    await w.get('[data-test="forge-setting-promotions"]').setValue('YES') // raw shared preference while the type is still unknown
    await w.get('[data-test="forge-setting-daily"]').setValue('BUY')
    await vi.advanceTimersByTimeAsync(TARGET_DEBOUNCE_MS + 1)
    expect(projectCalls).toHaveLength(2)
    expect(projectCalls.at(-1)!.settings).toMatchObject({ promotions: true, daily: 'BUY' })
    await vi.advanceTimersByTimeAsync(5100) // authoritative metadata: the row is now a Material
    await flushPromises()
    expect(calls).toBe(2)
    expect(projectCalls).toHaveLength(3) // exactly one bounded replan
    expect(projectCalls.at(-1)).toEqual({ recipeId: -4, target: 1, settings: { priceMode: 'INSTANT_BUY', useOwn: false, valueOwn: false, daily: 'CRAFT', promotions: false, reduce: 'NO' } })
    expect(w.find('[data-test="forge-estimated-batches"]').exists()).toBe(true) // Material detail with its result, not an empty page
    expect(w.get('[data-test="forge-estimated-batches"]').text()).toBe('≈7')
    expect(w.find('[data-test="forge-setting-promotions"]').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(projectCalls).toHaveLength(3) // no endless planning
    w.unmount()
  })

  describe('cold Material header metadata', () => {
    const coldMaterial = (r: ForgeRecipeRow) => r.recipeId === -4 ? { ...r, name: null } : r
    const coldRow = (w: ReturnType<typeof mount>) => w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Item 19745'))!

    it('re-arms bounded public completion for an unresolved Material heading and renders the name once it arrives, without extra project reads', async () => {
      let calls = 0
      const api: MysticForgeApi = {
        async loadCatalogue() {
          calls++
          const warm = calls > 1
          return { ...catalogue, completing: !warm, recipes: warm ? catalogue.recipes : catalogue.recipes.map(coldMaterial) }
        },
        loadProject(recipeId, target, settings) {
          projectCalls.push({ recipeId, target, settings })
          return Promise.resolve(project(target, { recipeId, settings: settings!, batches: 7 }))
        }
      }
      const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
      await flushPromises()
      await coldRow(w).trigger('click')
      await flushPromises()
      expect(w.get('[data-test="forge-item-summary"] h2').text()).toBe('Item 19745') // placeholder while metadata is missing
      await vi.advanceTimersByTimeAsync(5100)
      await flushPromises()
      expect(calls).toBe(2)
      expect(w.get('[data-test="forge-item-summary"] h2').text()).toBe('Gossamer Scrap')
      expect(projectCalls).toHaveLength(1) // metadata arrival never re-reads the project
      await vi.advanceTimersByTimeAsync(30_000)
      expect(calls).toBe(2) // complete: no further polling
      w.unmount()
    })

    it('stops at the bound with a visible Retry in the open detail, and Retry recovers the heading', async () => {
      let calls = 0
      let recover = false
      const api: MysticForgeApi = {
        async loadCatalogue() {
          calls++
          return { ...catalogue, completing: !recover, recipes: recover ? catalogue.recipes : catalogue.recipes.map(coldMaterial) }
        },
        loadProject(recipeId, target, settings) {
          projectCalls.push({ recipeId, target, settings })
          return Promise.resolve(project(target, { recipeId, settings: settings! }))
        }
      }
      const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
      await flushPromises()
      await coldRow(w).trigger('click')
      await flushPromises()
      for (let i = 0; i < 10; i++) await vi.advanceTimersByTimeAsync(5100)
      const bounded = calls
      expect(bounded).toBeLessThanOrEqual(7) // bounded, never endless
      expect(w.get('[data-test="forge-item-summary"] h2').text()).toBe('Item 19745')
      expect(w.find('[data-test="forge-detail-catalogue-retry"]').exists()).toBe(true)
      recover = true
      await w.get('[data-test="forge-detail-catalogue-retry-button"]').trigger('click')
      await flushPromises()
      expect(calls).toBe(bounded + 1)
      expect(w.get('[data-test="forge-item-summary"] h2').text()).toBe('Gossamer Scrap')
      expect(projectCalls).toHaveLength(1)
      w.unmount()
    })
  })

  describe('named Material with a missing icon', () => {
    const noIcon = (r: ForgeRecipeRow) => r.recipeId === -4 ? { ...r, iconUrl: null } : r
    const withIcon = (r: ForgeRecipeRow) => r.recipeId === -4 ? { ...r, iconUrl: '/api/items/19745/icon' } : r
    const gossamer = (w: ReturnType<typeof mount>) => w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Gossamer Scrap'))!

    it('keeps the bounded completion running for a named row without an icon and shows the icon once, without extra plans', async () => {
      let calls = 0
      const api: MysticForgeApi = {
        async loadCatalogue() {
          calls++
          const warm = calls > 1
          return { ...catalogue, completing: !warm, recipes: catalogue.recipes.map(warm ? withIcon : noIcon) }
        },
        loadProject(recipeId, target, settings) {
          projectCalls.push({ recipeId, target, settings })
          return Promise.resolve(project(target, { recipeId, settings: settings!, batches: 7 }))
        }
      }
      const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
      await flushPromises()
      await gossamer(w).trigger('click')
      await flushPromises()
      expect(w.find('[data-test="forge-item-summary"] img').exists()).toBe(false)
      await vi.advanceTimersByTimeAsync(5100)
      await flushPromises()
      expect(calls).toBe(2)
      expect(w.get('[data-test="forge-item-summary"] img').attributes('src')).toContain('/api/items/19745/icon')
      expect(projectCalls).toHaveLength(1)
      await vi.advanceTimersByTimeAsync(30_000)
      expect(calls).toBe(2)
      w.unmount()
    })

    it('stops at the bound with Retry that recovers the icon, and deactivation leaves no timer behind', async () => {
      let calls = 0
      let recover = false
      const api: MysticForgeApi = {
        async loadCatalogue() {
          calls++
          return { ...catalogue, completing: !recover, recipes: catalogue.recipes.map(recover ? withIcon : noIcon) }
        },
        loadProject(recipeId, target, settings) {
          projectCalls.push({ recipeId, target, settings })
          return Promise.resolve(project(target, { recipeId, settings: settings! }))
        }
      }
      const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
      await flushPromises()
      await gossamer(w).trigger('click')
      await flushPromises()
      for (let i = 0; i < 10; i++) await vi.advanceTimersByTimeAsync(5100)
      const bounded = calls
      expect(bounded).toBeLessThanOrEqual(7)
      expect(w.find('[data-test="forge-detail-catalogue-retry"]').exists()).toBe(true)
      recover = true
      await w.get('[data-test="forge-detail-catalogue-retry-button"]').trigger('click')
      await flushPromises()
      expect(calls).toBe(bounded + 1)
      expect(w.get('[data-test="forge-item-summary"] img').attributes('src')).toContain('/api/items/19745/icon')
      w.unmount()
      await vi.advanceTimersByTimeAsync(30_000)
      expect(calls).toBe(bounded + 1)
    })
  })

  it('metadata completing while a plan for the OLD settings is in flight converges once to the neutralized settings', async () => {
    let calls = 0
    let hold: ((p: ForgeProject) => void) | null = null
    let held = 0
    const cold = (r: ForgeRecipeRow) => r.recipeId === -4 ? { ...r, itemType: null, itemSubtype: null, category: 'OTHER' as const } : r
    const api: MysticForgeApi = {
      async loadCatalogue() {
        calls++
        const warm = calls > 1
        return { ...catalogue, completing: !warm, recipes: warm ? catalogue.recipes : catalogue.recipes.map(cold) }
      },
      loadProject(recipeId, target, settings) {
        projectCalls.push({ recipeId, target, settings })
        if (settings!.promotions && held === 0) { held++; return new Promise<ForgeProject>((resolve) => { hold = resolve }) }
        return Promise.resolve(project(target, { recipeId, settings: settings!, batches: 7 }))
      }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Gossamer Scrap'))!.trigger('click')
    await flushPromises()
    await w.get('[data-test="forge-setting-promotions"]').setValue('YES')
    await vi.advanceTimersByTimeAsync(TARGET_DEBOUNCE_MS + 1) // this request for the raw settings stays in flight
    expect(projectCalls.at(-1)!.settings).toMatchObject({ promotions: true })
    const before = projectCalls.length
    await vi.advanceTimersByTimeAsync(5100) // metadata completes WHILE it is in flight
    await flushPromises()
    expect(projectCalls).toHaveLength(before + 1)
    expect(projectCalls.at(-1)!.settings).toMatchObject({ daily: 'CRAFT', promotions: false, reduce: 'NO' })
    hold!(project(1, { recipeId: -4, settings: projectCalls[before - 1]!.settings as ForgeProject["settings"], batches: 99 })) // the stale answer arrives late
    await flushPromises()
    expect(w.get('[data-test="forge-estimated-batches"]').text()).toBe('≈7') // only the current-settings answer paints
    await vi.advanceTimersByTimeAsync(30_000)
    expect(projectCalls).toHaveLength(before + 1) // one convergence, no duplicate or endless requests
    w.unmount()
  })

  function failingColdApi(failFrom: number, failTo: number, counter: { calls: number }): MysticForgeApi {
    const cold = (r: ForgeRecipeRow) => r.recipeId === -1 ? { ...r, itemType: null, itemSubtype: null, category: 'OTHER' as const } : r
    return {
      async loadCatalogue() {
        counter.calls++
        if (counter.calls >= failFrom && counter.calls <= failTo) throw new Error('catalogue unavailable')
        const warm = counter.calls > failTo
        return { ...catalogue, completing: !warm, recipes: warm ? catalogue.recipes : catalogue.recipes.map(cold) }
      },
      loadProject(recipeId, target, settings) {
        projectCalls.push({ recipeId, target, settings })
        return Promise.resolve(project(target, settings ? { settings } : {}))
      }
    }
  }

  it('a rejected reread during an open cold OTHER detail re-arms bounded completion and the selectors appear without Back', async () => {
    const counter = { calls: 0 }
    const w = mount(MysticForgeScreen, { props: { api: failingColdApi(2, 2, counter) }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Sunrise Longbow'))!.trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(5100) // call 2 rejects
    await flushPromises()
    expect(w.find('[data-test="forge-detail-catalogue-retry"]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(5100) // automatic retry succeeds
    await flushPromises()
    expect(counter.calls).toBe(3)
    for (const id of ['price', 'use-own', 'value-own', 'daily', 'promotions', 'reduce']) {
      expect(w.find(`[data-test="forge-setting-${id}"]`).exists()).toBe(true)
    }
    expect(w.find('[data-test="forge-detail-catalogue-retry"]').exists()).toBe(false)
    expect(projectCalls).toHaveLength(1)
    w.unmount()
  })

  it('repeated reread failures stop at the bound and offer Retry inside the open detail, which recovers there', async () => {
    const counter = { calls: 0 }
    const w = mount(MysticForgeScreen, { props: { api: failingColdApi(2, 8, counter) }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]').find(r => r.text().includes('Sunrise Longbow'))!.trigger('click')
    await flushPromises()
    for (let i = 0; i < 10; i++) await vi.advanceTimersByTimeAsync(5100)
    expect(counter.calls).toBe(6) // the bound of 6 schedules is shared with the re-arm on opening: no endless polling
    expect(w.find('[data-test="forge-settings"]').exists()).toBe(true)
    expect(w.find('[data-test="forge-detail-catalogue-retry"]').exists()).toBe(true) // visible Retry inside the open detail
    expect(w.find('[data-test="forge-catalogue-stalled"]').exists()).toBe(true)
    await w.get('[data-test="forge-detail-catalogue-retry-button"]').trigger('click')
    await flushPromises()
    expect(counter.calls).toBe(7) // manual Retry resets the bound
    await vi.advanceTimersByTimeAsync(5100)
    await flushPromises()
    expect(counter.calls).toBe(8) // still failing, automatic bounded retry
    await vi.advanceTimersByTimeAsync(5100)
    await flushPromises()
    expect(counter.calls).toBe(9)
    expect(w.find('[data-test="forge-setting-price"]').exists()).toBe(true)
    expect(projectCalls).toHaveLength(1)
    w.unmount()
  })

  it('resumes a completing catalogue whose timer was cancelled by opening a detail once the user goes Back', async () => {
    const counter = { calls: 0 }
    const w = mount(MysticForgeScreen, { props: { api: completingApi(counter, 3) }, attachTo: document.body })
    await flushPromises()
    expect(counter.calls).toBe(1)
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(20_000)
    expect(counter.calls).toBe(1) // the detail cancelled the reread timer
    await w.get('[data-test="forge-back"]').trigger('click')
    await vi.advanceTimersByTimeAsync(11_000)
    await flushPromises()
    expect(counter.calls).toBe(3) // resumed, completed, stopped
    w.unmount()
  })
})

describe('lifecycle races, labels and reference-only Clover', () => {
  it('hides a quantity-1 result the moment the target text changes, even before the debounce or when invalid', async () => {
    const w = await mounted()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(true)
    await w.get('[data-test="forge-target"]').setValue('2')
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(false)
    expect(projectCalls).toHaveLength(1)
    await w.get('[data-test="forge-target"]').setValue('')
    vi.advanceTimersByTime(TARGET_DEBOUNCE_MS + 1)
    await flushPromises()
    expect(projectCalls).toHaveLength(1)
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(false)
    w.unmount()
  })
  it('a delayed answer for quantity 1 never shows under quantity 2', async () => {
    const w = await mounted(true)
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    await w.get('[data-test="forge-target"]').setValue('2')
    pendingProject[0]!(project(1))
    await flushPromises()
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(false)
    vi.advanceTimersByTime(TARGET_DEBOUNCE_MS + 1)
    await flushPromises()
    pendingProject[1]!(project(2))
    await flushPromises()
    expect(w.get('[data-test="forge-output"]').text()).toContain('2')
    w.unmount()
  })
  it('deactivation discards in-flight work and activation resumes; disposal is permanent', async () => {
    const w = await mounted(true)
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    pendingProject[0]!(project(1))
    await flushPromises()
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(true)
    w.unmount()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(projectCalls).toHaveLength(1)
  })
  it('labels per-item quote separately from total multi-batch proceeds and uses the backend margin', async () => {
    const w = await mounted()
    await w.findAll('[data-test="forge-row"]').find((r) => r.text().includes('Gossamer'))!.trigger('click')
    await flushPromises()
    const text = w.get('[data-test="forge-summary"]').text()
    expect(text).toContain('Estimated sale proceeds')
    expect(text).toContain('36s 84c') // whole-plan expected gross from the backend, not a per-item quote
    expect(text).toContain('Trading Post fees')
    expect(w.get('[data-test="forge-margin"]').text()).toBe('-18s 31c') // backend margin, no frontend arithmetic
    w.unmount()
  })
  it('shows backend Research Note facts, the estimated value popup, and no invented numbers for an unknown wallet', async () => {
    const variant = (id: number, basis: boolean) => ({ recipeId: id, outputName: 'Peer Reviewed Research', notesPerBatch: 150, batchCostCopper: 40,
      per100NotesCopper: 27, basis, ingredients: [{ itemId: 5003, name: 'Page', count: 4, unitPriceCopper: 10, totalCopper: 40, iconUrl: null }] })
    const notes = { needed: 210, walletKnown: true, walletUsed: 60, shortfall: 150, containersNeeded: 1, containersCrafted: 1, producedNotes: 150,
      surplus: 0, producerRecipeId: 15120, estimateCopper: 16, estimateIssue: null, basisRecipeId: 15120, variants: [variant(15120, true), variant(15131, false)],
      ownedContainers: 0, ownedNotes: 0, acquireContainers: 0, uncoveredNotes: 0, producerLocked: false }
    let current: ForgeProject['notes'] = notes
    const api: MysticForgeApi = { async loadCatalogue() { return catalogue }, async loadProject(_id, target) { return project(target, { notes: current }) } }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    expect(w.get('[data-test="forge-notes-needed"]').text()).toBe('Needed 210; wallet used 60, missing 150 before production.')
    expect(w.get('[data-test="forge-notes-production"]').text()).toContain('craft 1 Peer Reviewed Research (150 Notes each) = 150 Notes')
    expect(w.get('[data-test="forge-notes-estimate"]').text()).toContain('16c')
    expect(w.findAll('[data-test="note-estimate-trigger"]')).toHaveLength(1)
    w.unmount()
    current = { ...notes, walletKnown: false, walletUsed: null, shortfall: null, containersNeeded: 0, containersCrafted: 0, producedNotes: 0, estimateCopper: null, estimateIssue: 'wallet unknown' }
    const w2 = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w2.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    expect(w2.get('[data-test="forge-notes-needed"]').text()).toContain('wallet unknown')
    expect(w2.find('[data-test="forge-notes-production"]').exists()).toBe(false)
    w2.unmount()
  })
  it('vendor steps are labelled Buy with the merchant, never Craft or Mystic Forge', async () => {
    const api: MysticForgeApi = {
      async loadCatalogue() { return catalogue },
      async loadProject(_id, target) {
        return project(target, { craftingSteps: [
          step(20796, 'Mystic Crystal', { recipeId: -1_000_000_001, recipeSource: 'VENDOR', crafts: 5, produced: 5, toAcquire: 5 }),
          step(101, 'Root', { recipeId: -1, crafts: 1, produced: 1, root: true })] })
      }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    const steps = w.findAll('[data-test="forge-crafting-step"]')
    const vendor = steps[0]!.text()
    expect(vendor).toContain('Buy5')
    expect(vendor).toContain('Miyani')
    expect(vendor).not.toContain('Mystic Forge')
    expect(vendor).not.toContain('Craft')
    expect(steps[1]!.text()).toContain('Craft1Root')
    w.unmount()
  })
  it.each([1, 10])('reference-only Clover (%i-output variant) shows one-batch inputs, requested batches and no root claim', async (inputs) => {
    const api: MysticForgeApi = {
      async loadCatalogue() { return catalogue },
      async loadProject(_id, target) {
        return project(target, { outcome: 'REFERENCE_RANDOM', targetKind: 'BATCHES', guaranteedOutput: null, economics: null, batches: target,
          note: 'Random outcome: reference inputs only; no output, profit or completion is claimed.', tree: null, craftingSteps: [],
          baseIngredients: [{ item: item(19925, 'Obsidian Shard'), quantity: inputs }] })
      }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]').find((r) => r.text().includes('Clover'))!.trigger('click')
    await flushPromises()
    await w.get('[data-test="forge-target"]').setValue('3')
    vi.advanceTimersByTime(TARGET_DEBOUNCE_MS + 1)
    await flushPromises()
    expect(w.find('[data-test="forge-output"]').exists()).toBe(false)
    expect(w.find('[data-test="forge-margin"]').exists()).toBe(false)
    expect(w.find('[data-test="forge-tree"]').exists()).toBe(false)
    expect(w.get('[data-test="forge-estimated-batches"]').text()).toBe('≈3')
    const base = w.get('[data-test="forge-base-ingredient"]').text() // one batch, independent of the requested 3
    expect(base).toContain(String(inputs))
    expect(base).toContain('Obsidian Shard')
    w.unmount()
  })

  it('separates owned containers from planned crafting and never claims output for an acquire-only container', async () => {
    const notes = { needed: 300, walletKnown: true, walletUsed: 0, shortfall: 300, containersNeeded: 2, containersCrafted: 0, producedNotes: 0,
      surplus: 0, producerRecipeId: null, estimateCopper: null, estimateIssue: null, basisRecipeId: null, variants: [],
      ownedContainers: 1, ownedNotes: 150, acquireContainers: 1, uncoveredNotes: 150, producerLocked: false }
    const api: MysticForgeApi = { async loadCatalogue() { return catalogue }, async loadProject(_id, target) { return project(target, { notes }) } }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    expect(w.get('[data-test="forge-notes-owned"]').text()).toContain('1 Peer Reviewed Research to open = 150 Notes')
    expect(w.find('[data-test="forge-notes-production"]').exists()).toBe(false)
    expect(w.get('[data-test="forge-notes-acquire"]').text()).toContain('150 Notes remain uncovered')
    expect(w.find('[data-test="forge-notes-surplus"]').exists()).toBe(false)
    w.unmount()
  })

  it('shows the actual produced quantity and kept surplus of a crafted batch in the backend instruction', async () => {
    const api: MysticForgeApi = { async loadCatalogue() { return catalogue }, async loadProject(_id, target) {
      return project(target, { craftingSteps: [
        step(20796, "Philosopher's Stone", { recipeId: 4321, recipeSource: 'CRAFT', crafts: 2, produced: 10, newSurplus: 5, assignedCharacter: 'Tester' }),
        step(101, 'Root', { recipeId: -1, crafts: 1, produced: 1, root: true })] })
    } }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    const text = w.findAll('[data-test="forge-crafting-step"]')[0]!.text()
    expect(text).toContain('Craft10')
    expect(text).toContain('5 extra batch units available for later steps')
    expect(text).toContain('Tester')
    w.unmount()
  })

  it('keeps a REAL arithmetic-incomplete result with no tree and does not re-poll it as pending', async () => {
    let calls = 0
    const api: MysticForgeApi = { async loadCatalogue() { return catalogue }, async loadProject(_id, target) {
      calls++
      return project(target, { tree: null, complete: false, incompleteReason: 'quantity exceeds the supported range', goldCostPartial: true, unpricedItems: [],
        freshness: { requestId: 'r', calculationId: 'c', evaluatedAt: '', provisional: false, resultAvailable: true, accountSources: [],
          tradingPost: {} as never, outstandingWork: [], failures: [] } })
    } }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(calls).toBe(1)
    expect(w.find('[data-test="forge-pending"]').exists()).toBe(false)
    expect(w.get('[data-test="forge-incomplete"]').text()).toContain('quantity exceeds the supported range')
    expect(w.get('[data-test="forge-known-gold"]').text()).toContain('the plan is incomplete')
    expect(w.get('[data-test="forge-known-gold"]').text()).not.toContain('unpriced')
    w.unmount()
  })
})
