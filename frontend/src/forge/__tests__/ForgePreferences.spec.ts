import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MysticForgeApi } from '@/api/mysticForgeApi'
import { connection } from '@/api/accountSession'
import type { ForgeCatalogue, ForgeItem, ForgeProject, ForgeRecipeRow, ForgeSettings } from '@/api/types'
import MysticForgeScreen from '../MysticForgeScreen.vue'
import { DEFAULT_FORGE_SETTINGS, readForgePreferences, saveForgePreferences } from '../forgePreferences'
import { TARGET_DEBOUNCE_MS } from '../useMysticForge'

// Literal, independent fixtures: the stored key names and values below are the documented contract, not read from production code.
const KEY = (account: string) => `gw2.mysticForge.settings.v1.${account}`
const SIX: ForgeSettings = { priceMode: 'BUY_ORDER', useOwn: true, valueOwn: true, daily: 'BUY', promotions: true, reduce: 'TRADABLE' }

describe('forge preference storage', () => {
  beforeEach(() => window.localStorage.clear())
  afterEach(() => vi.restoreAllMocks())

  it('defaults to instant buy, own/value/promotions/reduce No and daily Craft without an account or record', () => {
    expect(readForgePreferences(null)).toEqual({ settings: { priceMode: 'INSTANT_BUY', useOwn: false, valueOwn: false, daily: 'CRAFT', promotions: false, reduce: 'NO' }, unavailable: false })
    expect(readForgePreferences('acc-1')).toEqual({ settings: DEFAULT_FORGE_SETTINGS, unavailable: false })
  })

  it('round-trips all six choices per verified account and never leaks one account into another', () => {
    expect(saveForgePreferences('acc-1', SIX)).toBe(true)
    expect(readForgePreferences('acc-1').settings).toEqual(SIX)
    expect(readForgePreferences('acc-2').settings).toEqual(DEFAULT_FORGE_SETTINGS)
    expect(saveForgePreferences('acc-2', { ...SIX, priceMode: 'INSTANT_BUY', reduce: 'UNTRADABLE' })).toBe(true)
    expect(readForgePreferences('acc-1').settings).toEqual(SIX)
    expect(readForgePreferences('acc-2').settings.reduce).toBe('UNTRADABLE')
    expect(saveForgePreferences(null, SIX)).toBe(true) // no verified account: nothing is stored
    expect(window.localStorage.length).toBe(2)
  })

  it('stores only the six choices: no access key, token, context or account secret', () => {
    saveForgePreferences('acc-1', SIX)
    const stored = JSON.parse(window.localStorage.getItem(KEY('acc-1'))!)
    expect(Object.keys(stored).sort()).toEqual(['daily', 'priceMode', 'promotions', 'reduce', 'useOwn', 'valueOwn'])
    expect(window.localStorage.getItem(KEY('acc-1'))).not.toMatch(/key|token|context|secret/i)
  })

  it.each([
    ['malformed JSON', '{not json'], ['a JSON string', '"BUY_ORDER"'], ['an array', '[1,2]'], ['null', 'null'], ['a number', '7']
  ])('safely defaults for %s', (_label, raw) => {
    window.localStorage.setItem(KEY('acc-1'), raw)
    expect(readForgePreferences('acc-1')).toEqual({ settings: DEFAULT_FORGE_SETTINGS, unavailable: false })
  })

  it('validates every field independently: unknown values default, valid siblings and unknown extra fields are handled safely', () => {
    window.localStorage.setItem(KEY('acc-1'), JSON.stringify({ priceMode: 'MARKET', useOwn: 'yes', valueOwn: true, daily: 'SKIP',
      promotions: 1, reduce: 'EVERYTHING', accessKey: 'not-a-real-key', extra: { nested: true } }))
    const { settings } = readForgePreferences('acc-1')
    expect(settings).toEqual({ ...DEFAULT_FORGE_SETTINGS, valueOwn: true })
    expect(Object.keys(settings).sort()).toEqual(['daily', 'priceMode', 'promotions', 'reduce', 'useOwn', 'valueOwn'])
  })

  it('reports truthfully when browser storage cannot be read or written', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError') })
    expect(readForgePreferences('acc-1')).toEqual({ settings: DEFAULT_FORGE_SETTINGS, unavailable: true })
    vi.restoreAllMocks()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('full', 'QuotaExceededError') })
    expect(saveForgePreferences('acc-1', SIX)).toBe(false)
  })
})

const item = (id: number, name: string): ForgeItem => ({ itemId: id, name, iconUrl: null, wikiUrl: null })
const row = (o: Partial<ForgeRecipeRow>): ForgeRecipeRow => ({
  recipeId: -1, outputItemId: 100, name: 'Item', iconUrl: null, itemType: 'Weapon', itemSubtype: 'Sword', category: 'WEAPONS', outcome: 'FIXED',
  outputCount: 1, expectedMean: null, expectedMax: null, buyUnitCopper: 100, sellUnitCopper: 200, sourcePage: null, sourceRevision: null,
  wikiUrl: null, ingredientKinds: 2, ...o })
const makeProject = (recipeId: number, target: number, settings?: ForgeSettings): ForgeProject => ({
  recipeId, output: item(1, 'X'), outcome: 'FIXED', targetKind: 'OUTPUT_QUANTITY', requestedTarget: target, batches: target, guaranteedOutput: target,
  complete: true, incompleteReason: null, walletKnown: true, knownGoldCopper: 100, goldCostPartial: false, unpricedItems: [],
  tree: null, recipeTree: null, materialProgress: null, baseIngredients: [], craftingSteps: [], ownedItems: [], shopping: [], currencies: [],
  note: null, seed: null, economics: null, notes: null, freshness: null, settings: settings ?? DEFAULT_FORGE_SETTINGS, ownValue: null, clover: null,
  rootOwned: 0, comparison: null })

let requests: { recipeId: number; target: number; settings: ForgeSettings }[]
const apiFor = (recipes: ForgeRecipeRow[]): MysticForgeApi => ({
  async loadCatalogue(): Promise<ForgeCatalogue> { return { status: 'OK', source: 'wiki', license: 'CC', recipeCount: recipes.length, referenceCount: 0, recipes } },
  loadProject(recipeId, target, settings) { requests.push({ recipeId, target, settings: settings! }); return Promise.resolve(makeProject(recipeId, target, settings)) }
})
async function mountWith(recipes: ForgeRecipeRow[]) {
  const w = mount(MysticForgeScreen, { props: { api: apiFor(recipes) }, attachTo: document.body })
  await flushPromises()
  return w
}
const tile = (w: ReturnType<typeof mount>, name: string) => w.findAll('[data-test="forge-row"]').find(r => r.text().includes(name))!
async function settle() { await vi.advanceTimersByTimeAsync(TARGET_DEBOUNCE_MS + 1); await flushPromises() }

const BOW = row({ recipeId: -1, outputItemId: 101, name: 'Sunrise Longbow', itemSubtype: 'LongBow' })
const SHORT = row({ recipeId: -2, outputItemId: 102, name: 'Shadow Shortbow', itemSubtype: 'ShortBow' })
const SCRAP = row({ recipeId: -4, outputItemId: 19745, name: 'Gossamer Scrap', itemType: 'CraftingMaterial', itemSubtype: null, category: 'MATERIALS',
  outcome: 'EXPECTED', outputCount: 10, expectedMean: '18.92', expectedMax: 40, materialTier: 2 })

describe('shared Forge preferences in the screen', () => {
  beforeEach(() => { requests = []; window.localStorage.clear(); connection.accountId = 'acc-1'; vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); connection.accountId = null })

  async function chooseSix(w: ReturnType<typeof mount>) {
    await w.get('[data-test="forge-setting-price"]').setValue('BUY_ORDER')
    await w.get('[data-test="forge-setting-use-own"]').setValue('YES')
    await w.get('[data-test="forge-setting-value-own"]').setValue('YES')
    await w.get('[data-test="forge-setting-daily"]').setValue('BUY')
    await w.get('[data-test="forge-setting-promotions"]').setValue('YES')
    await w.get('[data-test="forge-setting-reduce"]').setValue('TRADABLE')
    await settle()
  }

  it('applies six choices made on one recipe to another recipe, and restores them after a reload', async () => {
    const w = await mountWith([BOW, SHORT])
    await tile(w, 'Sunrise Longbow').trigger('click'); await flushPromises()
    expect(requests.at(-1)!.settings).toEqual(DEFAULT_FORGE_SETTINGS)
    await chooseSix(w)
    expect(requests.at(-1)).toEqual({ recipeId: -1, target: 1, settings: SIX })
    await w.get('[data-test="forge-back"]').trigger('click')
    await tile(w, 'Shadow Shortbow').trigger('click'); await flushPromises()
    expect(requests.at(-1)).toEqual({ recipeId: -2, target: 1, settings: SIX })
    expect(JSON.parse(window.localStorage.getItem(KEY('acc-1'))!)).toEqual(SIX)
    w.unmount()

    requests = [] // a reload: new screen, same storage
    const reloaded = await mountWith([BOW, SHORT])
    await tile(reloaded, 'Sunrise Longbow').trigger('click'); await flushPromises()
    expect(requests).toEqual([{ recipeId: -1, target: 1, settings: SIX }])
    expect((reloaded.get('[data-test="forge-setting-reduce"]').element as HTMLSelectElement).value).toBe('TRADABLE')
    reloaded.unmount()
  })

  it('isolates verified accounts: another account starts from the defaults and never inherits or overwrites', async () => {
    saveForgePreferences('acc-1', SIX)
    connection.accountId = 'acc-2'
    const w = await mountWith([BOW])
    await tile(w, 'Sunrise Longbow').trigger('click'); await flushPromises()
    expect(requests.at(-1)!.settings).toEqual(DEFAULT_FORGE_SETTINGS)
    await w.get('[data-test="forge-setting-price"]').setValue('BUY_ORDER')
    await settle()
    expect(readForgePreferences('acc-1').settings).toEqual(SIX)
    expect(readForgePreferences('acc-2').settings).toEqual({ ...DEFAULT_FORGE_SETTINGS, priceMode: 'BUY_ORDER' })
    w.unmount()
  })

  it('ignores a malformed stored record, keeps editing usable and says nothing is wrong with storage', async () => {
    window.localStorage.setItem(KEY('acc-1'), '{"priceMode":')
    const w = await mountWith([BOW])
    await tile(w, 'Sunrise Longbow').trigger('click'); await flushPromises()
    expect(requests.at(-1)!.settings).toEqual(DEFAULT_FORGE_SETTINGS)
    expect(w.find('[data-test="forge-preferences-unavailable"]').exists()).toBe(false)
    w.unmount()
  })

  it('tells the truth when storage is inaccessible: choices still apply for the visit and a notice is shown', async () => {
    const w = await mountWith([BOW])
    await tile(w, 'Sunrise Longbow').trigger('click'); await flushPromises()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError') })
    await w.get('[data-test="forge-setting-price"]').setValue('BUY_ORDER')
    await settle()
    expect(requests.at(-1)!.settings.priceMode).toBe('BUY_ORDER')
    expect(w.get('[data-test="forge-preferences-unavailable"]').text()).toContain('browser storage is unavailable')
    w.unmount()
  })

  it('neutralizes the hidden Material controls in the request while the raw shared choices stay saved for equipment', async () => {
    const w = await mountWith([BOW, SCRAP])
    await tile(w, 'Sunrise Longbow').trigger('click'); await flushPromises()
    await chooseSix(w)
    await w.get('[data-test="forge-back"]').trigger('click')
    await tile(w, 'Gossamer Scrap').trigger('click'); await flushPromises()
    expect(requests.at(-1)).toEqual({ recipeId: -4, target: 250,
      settings: { priceMode: 'BUY_ORDER', useOwn: true, valueOwn: true, daily: 'CRAFT', promotions: false, reduce: 'NO' } })
    for (const id of ['daily', 'promotions', 'reduce']) expect(w.find(`[data-test="forge-setting-${id}"]`).exists()).toBe(false)
    expect(JSON.parse(window.localStorage.getItem(KEY('acc-1'))!)).toEqual(SIX) // raw values untouched by the Material view
    await w.get('[data-test="forge-setting-price"]').setValue('INSTANT_BUY') // a visible Material choice is still shared
    await settle()
    expect(readForgePreferences('acc-1').settings).toEqual({ ...SIX, priceMode: 'INSTANT_BUY' })
    await w.get('[data-test="forge-back"]').trigger('click')
    await tile(w, 'Sunrise Longbow').trigger('click'); await flushPromises()
    expect(requests.at(-1)!.settings).toEqual({ ...SIX, priceMode: 'INSTANT_BUY' }) // equipment still sees promotions/daily/reduce
    w.unmount()
  })
})

describe('grouped recipe paths and filters', () => {
  beforeEach(() => { requests = []; window.localStorage.clear(); connection.accountId = 'acc-1'; vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers(); connection.accountId = null })

  const pathsOf = (w: ReturnType<typeof mount>) => w.findAll('[data-test="forge-recipe-path"]').map(b => b.text())

  it('shows one tile for alternatives of the same output, two as buttons, three as a dropdown, and a switch keeps target and settings', async () => {
    const two = [row({ recipeId: -1, outputItemId: 500, name: 'Twin Blade', pathLabel: 'From Cores' }), row({ recipeId: -2, outputItemId: 500, name: 'Twin Blade', pathLabel: 'From Gifts' }),
      row({ recipeId: -3, outputItemId: 501, name: 'Other Blade' })]
    const w = await mountWith(two)
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(2)
    await tile(w, 'Twin Blade').trigger('click'); await flushPromises()
    expect(pathsOf(w)).toEqual(['From Cores', 'From Gifts'])
    await w.get('[data-test="forge-target"]').setValue('5')
    await w.get('[data-test="forge-setting-price"]').setValue('BUY_ORDER')
    await settle()
    await w.findAll('[data-test="forge-recipe-path"]')[1]!.trigger('click')
    await settle()
    expect(requests.at(-1)).toEqual({ recipeId: -2, target: 5, settings: { ...DEFAULT_FORGE_SETTINGS, priceMode: 'BUY_ORDER' } })
    w.unmount()

    const three = [...two.slice(0, 2), row({ recipeId: -4, outputItemId: 500, name: 'Twin Blade', pathLabel: 'From Scraps' })]
    const w3 = await mountWith(three)
    await tile(w3, 'Twin Blade').trigger('click'); await flushPromises()
    expect(w3.find('[data-test="forge-recipe-path"]').exists()).toBe(false)
    const select = w3.get('[data-test="forge-recipe-path-select"]')
    expect(select.findAll('option').map(o => o.text())).toEqual(['From Cores', 'From Gifts', 'From Scraps'])
    w3.unmount()
  })

  it('merges named Other variants (same name and item type, different outputs) into one tile with every variant as a path', async () => {
    const other = (recipeId: number, outputItemId: number, rarity: string) =>
      row({ recipeId, outputItemId, name: 'Mini Dragon', itemType: 'MiniPet', itemSubtype: null, category: 'OTHER', rarity, pathLabel: `Variant ${-recipeId}` })
    const w = await mountWith([other(-1, 700, 'Rare'), other(-2, 701, 'Exotic'), row({ recipeId: -3, outputItemId: 702, name: 'Mini Ghost', itemType: 'MiniPet', itemSubtype: null, category: 'OTHER' })])
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(2)
    await tile(w, 'Mini Dragon').trigger('click'); await flushPromises()
    expect(pathsOf(w)).toEqual(['Rare · Variant 1', 'Exotic · Variant 2']) // distinct rarity labels
    w.unmount()
  })

  it('filters before grouping: a rarity or exact item ID opens the matching variant, not the first alternative', async () => {
    const variants = [row({ recipeId: -1, outputItemId: 800, name: 'Prism Staff', rarity: 'Rare', itemSubtype: 'Staff' }),
      row({ recipeId: -2, outputItemId: 800, name: 'Prism Staff', rarity: 'Exotic', itemSubtype: 'Staff' })]
    const w = await mountWith(variants)
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(1)
    await w.get('[data-test="forge-filter-rarity"]').setValue('Exotic')
    expect(w.findAll('[data-test="forge-row"]')).toHaveLength(1)
    expect(w.get('[data-test="forge-row"]').attributes('data-recipe-id')).toBe('-2')
    await tile(w, 'Prism Staff').trigger('click'); await flushPromises()
    expect(requests.at(-1)!.recipeId).toBe(-2)
    w.unmount()

    const byId = await mountWith([row({ recipeId: -11, outputItemId: 801, name: 'Idol', rarity: 'Rare' }), row({ recipeId: -12, outputItemId: 801, name: 'Idol', rarity: 'Exotic' })])
    await byId.get('[data-test="forge-search"]').setValue('-12')
    expect(byId.findAll('[data-test="forge-row"]')).toHaveLength(1)
    expect(byId.get('[data-test="forge-row"]').attributes('data-recipe-id')).toBe('-12')
    byId.unmount()
  })

  it('keeps Type present and stable: disabled for All, weights for Armor, tiers for Materials, kinds for Other', async () => {
    const w = await mountWith([BOW, row({ recipeId: -5, outputItemId: 900, name: 'Gift of Might', itemType: 'Gizmo', itemSubtype: null, category: 'OTHER' }),
      row({ recipeId: -6, outputItemId: 901, name: 'Plate Coat', itemType: 'Armor', itemSubtype: 'Coat', category: 'ARMOR', weightClass: 'Heavy' }), SCRAP])
    const type = () => w.get('[data-test="forge-filter-subtype"]')
    const options = () => type().findAll('option').map(o => o.text())
    expect(type().attributes('disabled')).toBeDefined()
    await w.get('[data-test="forge-filter-category"]').setValue('ARMOR')
    expect(type().attributes('disabled')).toBeUndefined()
    expect(options()).toEqual(['All weights', 'Light', 'Medium', 'Heavy'])
    await w.get('[data-test="forge-filter-category"]').setValue('MATERIALS')
    expect(options()).toEqual(['All tiers', 'T2', 'T3', 'T4', 'T5', 'T6'])
    await w.get('[data-test="forge-filter-category"]').setValue('OTHER')
    expect(options()).toContain('Gift')
    await w.get('[data-test="forge-filter-category"]').setValue('WEAPONS')
    expect(options()).toEqual(['All types', 'Long Bow'])
    w.unmount()
  })
})
