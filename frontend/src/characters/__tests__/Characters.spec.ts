import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { connection } from '@/api/accountSession'
import type { CharactersApi } from '@/api/charactersApi'
import type { CharacterEquipmentTabsResponse, CharacterOverview, CharacterReadResponse } from '@/api/types'
import CharactersScreen from '../CharactersScreen.vue'
import { useCharacters } from '../useCharacters'

const ana: CharacterOverview = { name: 'Ana B', profession: 'Guardian', race: 'Human', gender: 'Female', level: 80,
  createdAt: '2020-01-01T00:00:00Z', craftingDisciplines: [{ discipline: 'Weaponsmith', rating: 500, active: true }] }
const bo: CharacterOverview = { ...ana, name: 'Bo', profession: 'Thief' }
const roster: CharacterReadResponse = { state: 'AVAILABLE', characterCount: 2, characters: [ana, bo],
  freshness: { completedAt: '2026-01-01T00:00:00Z', refresh: 'CURRENT' } }
const detailOf = (c: CharacterOverview): CharacterReadResponse => ({ state: 'AVAILABLE', character: c })
const item = (id: number, name: string, rarity: string) => ({ itemId: id, resolved: true, name, rarity, type: 'Weapon', icon: '/api/icons/x.png' })
const tabsResponse: CharacterEquipmentTabsResponse = { state: 'AVAILABLE', character: 'Ana B',
  equipment: { generation: 1, completedAt: '2026-01-01T00:00:00Z' },
  tabs: [
    { tab: 1, name: 'PvE', active: false, equipment: [] },
    { tab: 2, name: 'WvW', active: true, equipment: [
      { slot: 'HelmAquatic', item: item(1, 'Cool Helm', 'Ascended'), upgrades: [item(2, 'Rune', 'Exotic')],
        infusions: [{ itemId: 3, resolved: false }], statAttributes: { Power: 100 } },
      { item: { itemId: 9, resolved: false }, upgrades: [], infusions: [] }
    ] },
    { tab: 3, name: 'Fractal', active: false, equipment: [{ slot: 'Boots', item: item(5, 'Boots', 'Rare'), upgrades: [], infusions: [] }] }
  ] }

function deferred<T>() { let resolve!: (v: T) => void; const promise = new Promise<T>((r) => { resolve = r }); return { promise, resolve } }

function fakeApi(over: Partial<CharactersApi> = {}): CharactersApi & { calls: string[] } {
  const calls: string[] = []
  return {
    calls,
    loadRoster: async () => { calls.push('roster'); return roster },
    loadCharacter: async (n) => { calls.push(`detail:${n}`); return detailOf(n === 'Bo' ? bo : ana) },
    loadEquipmentTabs: async (n) => { calls.push(`equip:${n}`); return tabsResponse },
    startEquipmentTabsRefresh: async () => { calls.push('refresh'); return { taskId: 't', operation: 'X', statusUrl: '/api/sync/tasks/t' } },
    ...over
  }
}
const tasks = { readTaskStatus: async () => ({ taskId: 't', operation: 'X', state: 'SUCCEEDED' }) as never }
const noSleep = async () => {}

afterEach(() => { connection.generation = 0 })

describe('Characters screen', () => {
  it('lists only the supplied overview, loads detail lazily on selection and goes back', async () => {
    const api = fakeApi()
    const w = mount(CharactersScreen, { props: { api } })
    await flushPromises()
    expect(api.calls).toEqual(['roster'])
    expect(w.findAll('[data-test="roster-item"]')).toHaveLength(2)
    expect(w.text()).toContain('Weaponsmith 500')
    await w.findAll('[data-test="roster-item"]')[0]!.trigger('click')
    await flushPromises()
    expect(api.calls).toEqual(['roster', 'detail:Ana B', 'equip:Ana B'])
    expect(w.find('[data-test="character-name"]').text()).toBe('Ana B')
    await w.find('[data-test="character-back"]').trigger('click')
    expect(w.find('[data-test="roster"]').exists()).toBe(true)
  })

  it('shows rarity as text, upgrades, infusions, stats and honest unresolved/unspecified entries', async () => {
    const w = mount(CharactersScreen, { props: { api: fakeApi() } })
    await flushPromises()
    await w.find('[data-test="roster-item"]').trigger('click')
    await flushPromises()
    const text = w.find('[data-test="equipment-tabpanel"]').text()
    expect(text).toContain('Cool Helm'); expect(text).toContain('Ascended'); expect(text).toContain('Power +100')
    expect(text).toContain('Upgrade: Rune'); expect(text).toContain('Infusion: Item #3 (details unavailable)')
    expect(text).toContain('Unspecified slot'); expect(text).toContain('Helm Aquatic')
    expect(w.find('[data-test="equipment-rarity"]').classes()).toContain('rarity--ascended')
  })

  it('selects the actual active tab first, keeps it identified when inspecting another, supports keyboard, is read-only', async () => {
    const api = fakeApi()
    const w = mount(CharactersScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.find('[data-test="roster-item"]').trigger('click')
    await flushPromises()
    const tabs = () => w.findAll('[data-test="equipment-tab"]')
    expect(tabs().map((t) => t.text().replace(' In use', ''))).toEqual(['PvE', 'WvW', 'Fractal'])
    expect(tabs()[1]!.attributes('aria-selected')).toBe('true')
    await tabs()[1]!.trigger('keydown', { key: 'ArrowRight' })
    await flushPromises()
    expect(tabs()[2]!.attributes('aria-selected')).toBe('true')
    expect(document.activeElement).toBe(tabs()[2]!.element)
    expect(w.find('[data-test="equipment-inspecting"]').exists()).toBe(true)
    expect(tabs()[1]!.find('[data-test="equipment-tab-active"]').exists()).toBe(true)
    await tabs()[2]!.trigger('keydown', { key: 'Home' })
    expect(tabs()[0]!.attributes('aria-selected')).toBe('true')
    expect(w.find('[data-test="equipment-empty"]').text()).toContain('does not show which slots')
    expect(api.calls.filter((c) => c === 'refresh')).toHaveLength(0)
    w.unmount()
  })

  it('keeps the roster when equipment is unavailable and shows state, reason and a recovery action', async () => {
    const api = fakeApi({ loadEquipmentTabs: async () => ({ state: 'GRANT_REQUIRED', reason: 'MISSING_GRANT', message: 'Needs builds permission' }) })
    const w = mount(CharactersScreen, { props: { api } })
    await flushPromises()
    await w.find('[data-test="roster-item"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="equipment-state"]').text()).toContain('MISSING_GRANT')
    expect(w.find('[data-test="equipment-tablist"]').exists()).toBe(false)
    expect(w.find('[data-test="character-facts"]').exists()).toBe(true)
    await w.find('[data-test="character-back"]').trigger('click')
    expect(w.findAll('[data-test="roster-item"]')).toHaveLength(2)
  })

  it('does not render an empty roster for a failed roster read and retains a last-good roster', async () => {
    let fail = false
    const api = fakeApi({ loadRoster: async () => { if (fail) throw new Error('down'); return roster } })
    const w = mount(CharactersScreen, { props: { api } })
    await flushPromises()
    fail = true
    await w.vm.$nextTick()
    const first = mount(CharactersScreen, { props: { api: fakeApi({ loadRoster: async () => { throw new Error('down') } }) } })
    await flushPromises()
    expect(first.find('[data-test="roster-error"]').text()).toContain('does not mean you have none')
    expect(first.find('[data-test="roster-empty"]').exists()).toBe(false)
  })
})

describe('useCharacters guards and recovery', () => {
  it('discards a late equipment answer for a previously selected character', async () => {
    const slow = deferred<CharacterEquipmentTabsResponse>()
    const api = fakeApi({ loadEquipmentTabs: (n) => n === 'Ana B' ? slow.promise : Promise.resolve({ ...tabsResponse, character: 'Bo', tabs: [{ tab: 7, name: 'Bo tab', active: true, equipment: [] }] }) })
    const c = useCharacters(api, tasks, noSleep)
    c.select('Ana B'); c.back(); c.select('Bo')
    await flushPromises()
    slow.resolve(tabsResponse)
    await flushPromises()
    expect(c.equipmentTabs.value?.map((t) => t.tab)).toEqual([7])
    expect(c.inspectedTab.value).toBe(7)
  })

  it('discards answers that arrive after the account generation changed', async () => {
    const slow = deferred<CharacterReadResponse>()
    const c = useCharacters(fakeApi({ loadRoster: () => slow.promise }), tasks, noSleep)
    const p = c.loadRoster()
    connection.generation += 1
    slow.resolve(roster)
    await p
    expect(c.roster.value).toBeNull()
  })

  it('stops a refresh that would continue under a replaced account', async () => {
    let reads = 0
    const api = fakeApi({ loadEquipmentTabs: async () => { reads += 1; return { state: 'STALE', tabs: tabsResponse.tabs } } })
    const gate = deferred<{ taskId: string; operation: string; statusUrl: string }>()
    api.startEquipmentTabsRefresh = () => gate.promise
    const c = useCharacters(api, tasks, noSleep)
    c.select('Ana B'); await flushPromises()
    const p = c.refreshEquipment()
    connection.generation += 1
    gate.resolve({ taskId: 't', operation: 'X', statusUrl: '/api/sync/tasks/t' })
    await p
    expect(reads).toBe(1)
  })

  it('retains last-good equipment with a warning on transient failure and keeps the inspected tab', async () => {
    let fail = false
    const api = fakeApi({ loadEquipmentTabs: async () => { if (fail) throw new Error('boom'); return tabsResponse } })
    const c = useCharacters(api, tasks, noSleep)
    c.select('Ana B'); await flushPromises()
    c.inspect(3)
    fail = true
    await c.loadEquipment('Ana B')
    expect(c.equipmentFailure.value).not.toBeNull()
    expect(c.equipmentIsRetained.value).toBe(true)
    expect(c.shownTab.value?.tab).toBe(3)
  })

  it('does not present PENDING as empty and recovers once automatically through the refresh operation', async () => {
    let published = false
    const api = fakeApi({ loadEquipmentTabs: async () => published ? tabsResponse : { state: 'PENDING', reason: 'NOT_SYNCED', message: 'pending' } })
    const start = api.startEquipmentTabsRefresh
    api.startEquipmentTabsRefresh = async () => { published = true; return start() }
    const c = useCharacters(api, tasks, noSleep)
    c.select('Ana B'); await flushPromises(); await flushPromises()
    expect(api.calls.filter((x) => x === 'refresh')).toHaveLength(1)
    expect(c.equipmentTabs.value).toHaveLength(3)
  })

  it('reports a missing prerequisite without looping', async () => {
    const { ApiRequestError } = await import('@/api/http')
    const api = fakeApi({ loadEquipmentTabs: async () => ({ state: 'PENDING', message: 'p' }) })
    api.startEquipmentTabsRefresh = async () => { api.calls.push('refresh'); throw new ApiRequestError('no parent', 'PREREQUISITE_UNAVAILABLE', 409) }
    const c = useCharacters(api, tasks, noSleep)
    c.select('Ana B'); await flushPromises(); await flushPromises()
    expect(api.calls.filter((x) => x === 'refresh')).toHaveLength(1)
    expect(c.refreshMessage.value).toContain('not been synchronized')
  })
})

describe('getStateJson', () => {
  it('returns the structured state body of a 403 and a 503 instead of losing it', async () => {
    const { getStateJson } = await import('@/api/http')
    const f = vi.spyOn(globalThis, 'fetch')
    f.mockResolvedValueOnce(new Response(JSON.stringify({ state: 'GRANT_REQUIRED', reason: 'R' }), { status: 403 }))
    await expect(getStateJson('/account/characters/x/equipment-tabs')).resolves.toMatchObject({ state: 'GRANT_REQUIRED' })
    f.mockResolvedValueOnce(new Response('oops', { status: 500 }))
    await expect(getStateJson('/account/characters')).rejects.toMatchObject({ code: 'UNEXPECTED_STATUS' })
    f.mockRestore()
  })
})
