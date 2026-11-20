import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MysticForgeApi } from '@/api/mysticForgeApi'
import type { ForgeCatalogue, ForgeNode, ForgeProject, ForgeRecipeRow } from '@/api/types'
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
    shopping: [{ item: item(6, 'Ancient Wood Log'), needed: 9, fromOwned: 4, toAcquire: 5, unitCopper: 100, knownCopper: 500 }],
    currencies: [{ currencyId: 23, name: 'Spirit Shards', iconUrl: null, wikiUrl: 'https://wiki.guildwars2.com/wiki/Spirit_Shard', needed: 50, owned: null, missing: null }],
    note: null, seed: null,
    economics: { outputUnitSellCopper: 100, expectedGrossProceedsCopper: 3684, feeCopper: 553, expectedNetProceedsCopper: 3131, knownShoppingCopper: 1300,
      goldInputCopper: 1300, currencyInputs: [], unpricedInputs: [], marginOnShoppingCopper: null, marginCopper: -1831, conditional: true, fixedOutput: false },
    notes: null, freshness: null, ...o
  }
}

let projectCalls: { recipeId: number; target: number }[]
let catalogueCalls: number
let pendingProject: ((p: ForgeProject) => void)[]
function fakeApi(manual = false): MysticForgeApi {
  return {
    async loadCatalogue() { catalogueCalls++; return catalogue },
    loadProject(recipeId, target) {
      projectCalls.push({ recipeId, target })
      if (manual) return new Promise((resolve) => { pendingProject.push(resolve) })
      return Promise.resolve(project(target))
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

describe('Mystic Forge overview', () => {
  it('loads the catalogue once and never plans for overview, tab or search', async () => {
    const w = await mounted()
    expect(catalogueCalls).toBe(1)
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(5)
    await w.get('[data-test="forge-tab-ARMOR"]').trigger('click')
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(1)
    await w.get('[data-test="forge-tab-ALL"]').trigger('click')
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
    expect(longbow.get('[data-test="forge-row-prices"]').text()).toBe('Instant sell 1g 23s 45c · Listing sell 2g 34s 56c')
    const coat = rows.find((r) => r.text().includes('Gift Coat'))!
    expect(coat.get('[data-test="forge-row-prices"]').text()).toContain('Not traded / price unavailable')
    const clover = rows.find((r) => r.text().includes('Mystic Clover'))!
    expect(clover.get('[data-test="forge-row-prices"]').text()).toBe('No guaranteed output')
    expect(clover.text()).toContain('reference only')
  })
})

describe('selected project', () => {
  it('opens exactly one recipe with default target 1 and shows full path, currencies and partial cost', async () => {
    const w = await mounted()
    await w.findAll('[data-test="forge-row"]').find((r) => r.text().includes('Sunrise Longbow'))!.trigger('click')
    await flushPromises()
    expect(projectCalls).toEqual([{ recipeId: -1, target: 1 }])
    expect(w.get('[data-test="forge-known-gold"]').text()).toContain('77g 77s 0c')
    expect(w.get('[data-test="forge-known-gold"]').text()).toContain('partial')
    expect(w.get('[data-test="forge-unpriced"]').text()).toContain('Gift of Fortune')
    expect(w.get('[data-test="forge-currency"]').text()).toContain('Spirit Shards — need 50, wallet unknown')
    expect(w.get('[data-test="forge-tree"]').text()).toContain('Locked Plank')
    expect(w.get('[data-test="forge-tree"]').text()).toContain('Recipe not unlocked')
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
    expect(projectCalls).toEqual([{ recipeId: -3, target: 25 }])
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
    expect(w.get('[data-test="forge-yield"]').text()).toContain('expected 18.92')
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
    const text = w.get('[data-test="forge-economics"]').text()
    expect(w.get('[data-test="forge-unit-quote"]').text()).toBe('1s 0c')
    expect(text).toContain('Estimated total proceeds (1 batch)')
    expect(w.get('[data-test="forge-total-proceeds"]').text()).toBe('36s 84c')
    expect(w.get('[data-test="forge-margin"]').text()).toBe('-18s 31c')
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
  it('vendor steps are labelled Vendor / To buy, never Mystic Forge / To craft', async () => {
    const api: MysticForgeApi = {
      async loadCatalogue() { return catalogue },
      async loadProject(_id, target) {
        return project(target, { tree: node({ item: item(104, 'Root'), needed: 1, toCraft: 1, toAcquire: 0, recipeId: -1, children: [
          node({ item: item(20796, 'Mystic Crystal'), needed: 5, toCraft: 5, toAcquire: 0, recipeId: -1_000_000_001, recipeSource: 'VENDOR' })] }) })
      }
    }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    const nodes = w.findAll('[data-test="forge-node"]')
    const vendor = nodes[1]!.text()
    expect(vendor).toContain('Vendor')
    expect(vendor).toContain('To buy 5')
    expect(vendor).not.toContain('Mystic Forge')
    expect(vendor).not.toContain('To craft')
    expect(nodes[0]!.text()).toContain('Mystic Forge')
    w.unmount()
  })
  it.each([1, 10])('reference-only Clover (%i-output variant) shows inputs for batches and no root claim', async (batches) => {
    const api: MysticForgeApi = {
      async loadCatalogue() { return catalogue },
      async loadProject(_id, target) {
        return project(target, { outcome: 'REFERENCE_RANDOM', targetKind: 'BATCHES', guaranteedOutput: null, economics: null, batches: target,
          note: 'Random outcome: reference inputs only; no output, profit or completion is claimed.',
          tree: node({ item: item(19675, 'Mystic Clover'), needed: 0, toCraft: 0, toAcquire: 0, recipeId: -1_100_000_001, children: [
            node({ item: item(19925, 'Obsidian Shard'), needed: batches * target, toAcquire: batches * target })] }) })
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
    expect(w.find('[data-test="forge-economics"]').exists()).toBe(false)
    expect(w.get('[data-test="forge-batches"]').text()).toContain('3 requested batches')
    const nodes = w.findAll('[data-test="forge-node"]')
    expect(nodes[0]!.get('.forge-node__head').find('[data-test="forge-node-needed"]').exists()).toBe(false)
    expect(nodes[0]!.text()).not.toContain('To craft')
    expect(nodes[1]!.get('[data-test="forge-node-needed"]').text()).toBe(`× ${batches * 3}`)
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

  it('shows the actual produced quantity and kept surplus of a batch purchase beside the demand', async () => {
    const api: MysticForgeApi = { async loadCatalogue() { return catalogue }, async loadProject(_id, target) {
      return project(target, { tree: node({ item: item(101, 'Root'), needed: 1, toCraft: 1, toAcquire: 0, recipeId: -1, children: [
        node({ item: item(20796, "Philosopher's Stone"), needed: 5, toCraft: 5, toAcquire: 0, recipeId: -1_000_000_001, recipeSource: 'VENDOR',
          produced: 10, newSurplus: 5 })] }) })
    } }
    const w = mount(MysticForgeScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="forge-row"]')[0]!.trigger('click')
    await flushPromises()
    const facts = w.findAll('[data-test="forge-node"]')[1]!.get('[data-test="forge-node-facts"]').text()
    expect(facts).toContain('To buy 5')
    expect(facts).toContain('Purchase yields 10')
    expect(facts).toContain('5 surplus kept for later use')
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
