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
    row({ recipeId: -4, outputItemId: 19745, name: 'Gossamer Scrap', itemType: 'CraftingMaterial', itemSubtype: null, category: 'MATERIALS', outcome: 'EXPECTED', outputCount: 10, expectedMean: '18.92' }),
    row({ recipeId: -5, outputItemId: 104, name: 'Mystic Clover', itemType: 'CraftingMaterial', itemSubtype: null, category: 'MATERIALS', outcome: 'REFERENCE_RANDOM', outputCount: 0 })
  ]
}
const item = (id: number, name: string) => ({ itemId: id, name, iconUrl: null, wikiUrl: `https://wiki.guildwars2.com/wiki/${name}` })
const node = (o: Partial<ForgeNode>): ForgeNode => ({
  item: item(1, 'X'), needed: 1, fromOwned: 0, fromSurplus: 0, toCraft: 0, toAcquire: 1, recipeId: null, recipeSource: null,
  crafts: 0, minRating: 0, disciplines: null, cutoff: null, children: [], recipeKnown: null, performable: true,
  assignedCharacter: null, hint: null, ...o
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
    freshness: null, ...o
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
