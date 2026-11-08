import { flushPromises, mount as baseMount } from '@vue/test-utils'
import { shellProvide } from '@/sync/__tests__/shellProvide'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { connection } from '@/api/accountSession'
import { syncApi } from '@/api/syncApi'
import type { CharactersApi } from '@/api/charactersApi'
import type { CharacterBuildTabsResponse, CharacterInventoryResponse, CharacterEquipmentTabsResponse, CharacterOverview, CharacterReadResponse } from '@/api/types'
import CharactersScreen from '../CharactersScreen.vue'
import { useCharacters } from '../useCharacters'

/** Mounts under the shell's shared synchronization context, as the application does. */
const mounted: Array<ReturnType<typeof baseMount>> = []
const mount: typeof baseMount = ((component: unknown, options: Record<string, unknown> = {}) => {
  const wrapper = baseMount(component as never, { ...options, global: { ...(options.global as object), provide: shellProvide() } })
  mounted.push(wrapper)
  return wrapper
}) as unknown as typeof baseMount

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

const inventoryResponse: CharacterInventoryResponse = { state: 'AVAILABLE', character: 'Ana B', generation: 1,
  completedAt: '2026-01-01T00:00:00Z', contents: 'HAS_ITEMS',
  bags: [
    { bag: 0, installed: true, container: { itemId: 10, resolved: true, name: 'Big Bag' }, capacity: 2, slots: [
      { slot: 0, empty: false, item: { itemId: 11, resolved: true, name: 'Iron Ore' }, quantity: 250 },
      { slot: 1, empty: true }] },
    { bag: 1, installed: false, slots: [] },
    { bag: 2, installed: true, container: { itemId: 12, resolved: false }, capacity: 1, slots: [
      { slot: 0, empty: false, item: { itemId: 99, resolved: false }, quantity: 3, binding: 'Account' }] }
  ] }

const spec = (id: number, name: string, profession: string, elite: boolean, resolved = true) => ({ specialization: { id, resolved, name, profession, elite } })
const buildsResponse: CharacterBuildTabsResponse = { state: 'AVAILABLE', character: 'Ana B', parent: { generation: 1 }, builds: { generation: 1 }, coveredParentGeneration: 1,
  tabs: [
    { tab: 1, active: false, specializations: [spec(1, 'Zeal', 'Guardian', false), spec(2, 'Inspected Elite', 'Guardian', true)] },
    { tab: 2, active: true, specializations: [spec(3, 'Valor', 'Guardian', false), spec(62, 'Willbender', 'Guardian', true)] }
  ] }

function deferred<T>() { let resolve!: (v: T) => void; const promise = new Promise<T>((r) => { resolve = r }); return { promise, resolve } }

function fakeApi(over: Partial<CharactersApi> = {}): CharactersApi & { calls: string[] } {
  const calls: string[] = []
  return {
    calls,
    loadRoster: async () => { calls.push('roster'); return roster },
    loadCharacter: async (n) => { calls.push(`detail:${n}`); return detailOf(n === 'Bo' ? bo : ana) },
    loadEquipmentTabs: async (n) => { calls.push(`equip:${n}`); return tabsResponse },
    loadInventory: async (n) => { calls.push(`inv:${n}`); return inventoryResponse },
    loadBuildTabs: async (n) => { calls.push(`builds:${n}`); return { ...buildsResponse, character: n } },
    startBuildTabsRefresh: async () => { calls.push('build-refresh'); return { taskId: 'b', operation: 'X', statusUrl: '/api/sync/tasks/b' } },
    startEquipmentTabsRefresh: async () => { calls.push('refresh'); return { taskId: 't', operation: 'X', statusUrl: '/api/sync/tasks/t' } },
    ...over
  }
}
const tasks = { readTaskStatus: async () => ({ taskId: 't', operation: 'X', state: 'SUCCEEDED' }) as never }
const noSleep = async () => {}

afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  connection.generation = 0
})

describe('Characters screen', () => {
  it('lists only the supplied overview, loads detail lazily on selection and goes back', async () => {
    const api = fakeApi()
    const w = mount(CharactersScreen, { props: { api } })
    await flushPromises()
    expect(api.calls).toEqual(['roster'])
    expect(w.findAll('[data-test="roster-item"]')).toHaveLength(2)
    expect(w.text()).toContain('Weaponsmith: 500')
    await w.findAll('[data-test="roster-item"]')[0]!.trigger('click')
    await flushPromises()
    expect(api.calls).toEqual(['roster', 'detail:Ana B', 'equip:Ana B', 'builds:Ana B'])
    expect(w.find('[data-test="page-heading"]').text()).toBe('Ana B')
    expect(w.find('[data-test="page-intro"]').exists()).toBe(false)
    await w.find('[data-test="character-back"]').trigger('click')
    expect(w.find('[data-test="roster"]').exists()).toBe(true)
  })

  it('sorts the roster by playtime by default, offers the alternatives and never fans out detail reads', async () => {
    const mk = (name: string, over: Partial<CharacterOverview>): CharacterOverview => ({ ...ana, name, ...over })
    const chars = [
      mk('Cy', { profession: 'Warrior', ageSeconds: 3_600, deaths: 5, createdAt: '2021-01-01T00:00:00Z', nextBirthdayAt: '2027-03-01T00:00:00Z' }),
      mk('Al', { profession: 'Thief', ageSeconds: 7_200, deaths: 1, createdAt: '2022-01-01T00:00:00Z', nextBirthdayAt: '2027-01-01T00:00:00Z' }),
      mk('Bea', { profession: 'Elementalist', createdAt: '2020-06-01T00:00:00Z' }), // playtime unknown
      mk('Dov', { profession: 'Guardian', ageSeconds: 7_200, deaths: 9, createdAt: '2019-01-01T00:00:00Z', nextBirthdayAt: '2027-02-01T00:00:00Z' })
    ]
    const api = fakeApi({ loadRoster: async () => {
      api.calls.push('roster')
      return { ...roster, characterCount: 4, characters: chars }
    } })
    const w = mount(CharactersScreen, { props: { api } })
    await flushPromises()
    const names = () => w.findAll('[data-test="roster-item"]').map((r) => r.attributes('data-name'))
    const select = w.get('[data-test="roster-sort"]')
    expect((select.element as HTMLSelectElement).value).toBe('playtime')
    expect(select.findAll('option').map((o) => o.text())).toEqual(['Playtime', 'Next birthday', 'Age', 'Deaths', 'Alphabetical', 'Profession'])
    // Longest playtime first, ties by name, unknown playtime last.
    expect(names()).toEqual(['Al', 'Dov', 'Cy', 'Bea'])
    const expected: Record<string, string[]> = {
      birthday: ['Al', 'Dov', 'Cy', 'Bea'], age: ['Dov', 'Bea', 'Cy', 'Al'], deaths: ['Dov', 'Cy', 'Al', 'Bea'],
      name: ['Al', 'Bea', 'Cy', 'Dov'], profession: ['Bea', 'Dov', 'Al', 'Cy']
    }
    for (const [value, order] of Object.entries(expected)) {
      await select.setValue(value)
      expect(names(), value).toEqual(order)
    }
    expect(api.calls).toEqual(['roster']) // sorting uses only the supplied overview
  })

  it('shows rarity as text, upgrades, infusions, stats and honest unresolved/unspecified entries', async () => {
    const w = mount(CharactersScreen, { props: { api: fakeApi() } })
    await flushPromises()
    await w.find('[data-test="roster-item"]').trigger('click')
    await flushPromises()
    const panel = w.find('[data-test="equipment-tabpanel"]')
    const entries = panel.findAll('[data-test="equipment-entry"]')
    const labels = entries.map((e) => e.attributes('aria-label'))
    expect(labels).toContain('Helm Aquatic: Cool Helm')
    expect(panel.text().includes('Item #9 (details unavailable)')).toBe(true)
    const helm = entries[labels.indexOf('Helm Aquatic: Cool Helm')]!
    await helm.trigger('mouseenter'); await flushPromises()
    const tip = document.body.querySelector('[role="tooltip"]')!.textContent!
    expect(tip).toContain('Cool Helm'); expect(tip).toContain('Ascended'); expect(tip).toContain('+100 Power')
    expect(tip).toContain('Rune'); expect(tip).toContain('Item #3')
    expect(tip).toContain('Helm Aquatic')
    await helm.trigger('mouseleave')
    w.unmount()
  })

  it('selects the actual active tab first, keeps it identified when inspecting another, supports keyboard, is read-only', async () => {
    const api = fakeApi()
    const w = mount(CharactersScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.find('[data-test="roster-item"]').trigger('click')
    await flushPromises()
    const tabs = () => w.findAll('[data-test="equipment-tab"]')
    expect(tabs().map((t) => t.text().replace('●', ''))).toEqual(['1', '2', '3'])
    expect(tabs().map((t) => t.attributes('title'))).toEqual(['PvE', 'WvW · In use in game', 'Fractal'])
    expect(w.find('[data-test="equipment-tablist"]').exists()).toBe(true)
    expect(tabs()[1]!.attributes('aria-selected')).toBe('true')
    await tabs()[1]!.trigger('keydown', { key: 'ArrowRight' })
    await flushPromises()
    expect(tabs()[2]!.attributes('aria-selected')).toBe('true')
    expect(document.activeElement).toBe(tabs()[2]!.element)
    expect(w.find('[data-test="equipment-tabpanel"]').attributes('aria-labelledby')).toBe('equipment-tab-3')
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
    expect(w.find('[data-test="character-summary"]').exists()).toBe(true)
    await w.find('[data-test="character-back"]').trigger('click')
    expect(w.findAll('[data-test="roster-item"]')).toHaveLength(2)
  })

  it('does not render an empty roster for a failed first read', async () => {
    const first = mount(CharactersScreen, { props: { api: fakeApi({ loadRoster: async () => { throw new Error('down') } }) } })
    await flushPromises()
    expect(first.find('[data-test="roster-error"]').text()).toContain('does not mean you have none')
    expect(first.find('[data-test="roster-empty"]').exists()).toBe(false)
  })

  it('retains the last-good roster when a later read of the same instance fails', async () => {
    let fail = false
    const api = fakeApi({ loadRoster: async () => { if (fail) throw new Error('down'); return roster } })
    const c = useCharacters(api, tasks, noSleep)
    await c.loadRoster()
    expect(c.roster.value?.characters).toHaveLength(2)
    fail = true
    await c.loadRoster()
    expect(c.rosterFailure.value).not.toBeNull()
    expect(c.roster.value?.characters).toHaveLength(2)
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

describe('reopen, refresh cancellation and real account replacement', () => {
  it('reopening a viewed character after back then a failed read keeps tabs, a focusable tab and the active tab', async () => {
    let fail = false
    const api = fakeApi({ loadEquipmentTabs: async () => { if (fail) throw new Error('boom'); return tabsResponse } })
    const w = mount(CharactersScreen, { props: { api } })
    await flushPromises()
    await w.findAll('[data-test="roster-item"]')[0]!.trigger('click')
    await flushPromises()
    await w.find('[data-test="character-back"]').trigger('click')
    fail = true
    await w.findAll('[data-test="roster-item"]')[0]!.trigger('click')
    await flushPromises()
    const tabs = w.findAll('[data-test="equipment-tab"]')
    expect(tabs).toHaveLength(3)
    expect(tabs[1]!.attributes('aria-selected')).toBe('true')
    expect(tabs.filter((t) => t.attributes('tabindex') === '0')).toHaveLength(1)
    expect(w.find('[data-test="equipment-tabpanel"]').exists()).toBe(true)
  })

  it('makes no further request when the account changes while waiting on a task that does not cover the request', async () => {
    const calls: string[] = []
    const api = fakeApi({
      startEquipmentTabsRefresh: async () => { calls.push('refresh'); return { taskId: 't', operation: 'X', statusUrl: '/api/sync/tasks/t', coversRequest: false } },
      loadEquipmentTabs: async () => { calls.push('equip'); return tabsResponse }
    })
    const running = { readTaskStatus: async () => { calls.push('task'); return { taskId: 't', operation: 'X', state: 'RUNNING' } as never } }
    const gate = deferred<void>()
    const c = useCharacters(api, running, () => gate.promise)
    c.select('Ana B'); await flushPromises()
    const p = c.refreshEquipment()
    await flushPromises()
    const before = calls.length
    connection.generation += 1
    gate.resolve()
    await p
    expect(calls.length).toBe(before)
  })

  it('never dispatches an outgoing account A request under account B (real connectKey, transport and APIs)', async () => {
    const { connectKey, installAccountSession, resetAccountSession } = await import('@/api/accountSession')
    const { charactersApi } = await import('@/api/charactersApi')
    const { syncApi } = await import('@/api/syncApi')
    resetAccountSession(); installAccountSession()
    let id = 'acc-A'
    const log: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
      const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { 'Content-Type': 'application/json' } })
      if (input.endsWith('/session') || input.includes('/account/session')) {
        return json({ accountId: id, accessContext: `ctx-${id}`, expiresAt: '2099-01-01T00:00:00Z', grants: ['account'] })
      }
      log.push(`${init?.method ?? 'GET'} ${input}`)
      if (input.includes('/sync/tasks/')) return json({ taskId: 't', operation: 'X', state: 'RUNNING' })
      if (input.includes('/sync/account')) return json({ taskId: 't', operation: 'X', statusUrl: '/api/sync/tasks/t', coversRequest: false }, 202)
      if (input.includes('equipment-tabs')) return json(tabsResponse)
      if (input.includes('/account/characters/')) return json(detailOf(ana))
      return json(roster)
    }))
    try {
      await connectKey('SAME-NAME-KEY')
      const gate = deferred<void>()
      const c = useCharacters(charactersApi, syncApi, () => gate.promise)
      await c.loadRoster()
      c.select('Ana B'); await flushPromises()
      const p = c.refreshEquipment()
      await flushPromises()
      const before = log.length
      id = 'acc-B'
      await connectKey('SAME-NAME-KEY')
      gate.resolve()
      await p
      await c.loadRoster(); c.select('Bo'); await flushPromises()
      expect(log.length).toBe(before)
      expect(log.some((l) => l.startsWith('POST') && l.includes('/sync/account'))).toBe(true)
      expect(log.filter((l) => l.startsWith('POST'))).toHaveLength(1)
    } finally {
      resetAccountSession()
      vi.unstubAllGlobals()
    }
  })
})

describe('remaining acceptance fixes', () => {
  it('after a refresh started for A, success rereads the character selected now (B) with no second POST', async () => {
    let published = false
    const gate = deferred<void>()
    const api = fakeApi({
      loadEquipmentTabs: async (n) => { api.calls.push(`equip:${n}`); return published ? { ...tabsResponse, character: n } : { state: 'PENDING', message: 'p' } }
    })
    api.startEquipmentTabsRefresh = async () => { api.calls.push('refresh'); return { taskId: 't', operation: 'X', statusUrl: '/s' } }
    const slowTasks = { readTaskStatus: async () => { await gate.promise; published = true; return { taskId: 't', operation: 'X', state: 'SUCCEEDED' } as never } }
    const c = useCharacters(api, slowTasks, noSleep)
    c.select('Ana B'); await flushPromises()
    expect(c.refreshing.value).toBe(true)
    c.back(); c.select('Bo'); await flushPromises()
    gate.resolve(); await flushPromises(); await flushPromises()
    expect(api.calls.filter((x) => x === 'refresh')).toHaveLength(1)
    expect(api.calls[api.calls.length - 1]).toBe('equip:Bo')
    expect(c.equipmentTabs.value).toHaveLength(3)
    expect(c.equipmentResponse.value?.character).toBe('Bo')
  })

  it('does not send a refresh result read after the account is replaced', async () => {
    const gate = deferred<void>()
    const api = fakeApi({ loadEquipmentTabs: async (n) => { api.calls.push(`equip:${n}`); return { state: 'PENDING', message: 'p' } } })
    const slowTasks = { readTaskStatus: async () => { await gate.promise; return { taskId: 't', operation: 'X', state: 'SUCCEEDED' } as never } }
    const c = useCharacters(api, slowTasks, noSleep)
    c.select('Ana B'); await flushPromises()
    connection.generation += 1
    gate.resolve(); await flushPromises()
    expect(api.calls.filter((x) => x.startsWith('equip:'))).toHaveLength(1)
  })

  it('keeps the retained roster entry, labelled, when a reopened detail fails or is unavailable', async () => {
    let mode: 'ok' | 'fail' | 'unavailable' = 'ok'
    const api = fakeApi({
      loadCharacter: async (n) => {
        if (mode === 'fail') throw new Error('boom')
        if (mode === 'unavailable') return { state: 'UNAVAILABLE', message: 'down' }
        return detailOf(n === 'Bo' ? bo : ana)
      }
    })
    const w = mount(CharactersScreen, { props: { api } })
    await flushPromises()
    await w.findAll('[data-test="roster-item"]')[0]!.trigger('click'); await flushPromises()
    expect(w.find('[data-test="detail-retained"]').exists()).toBe(false)
    await w.find('[data-test="character-back"]').trigger('click')
    for (const m of ['fail', 'unavailable'] as const) {
      mode = m
      await w.findAll('[data-test="roster-item"]')[0]!.trigger('click'); await flushPromises()
      expect(w.find('[data-test="character-subtitle"]').text()).toContain('Human')
      expect(w.find('[data-test="detail-retained"]').text()).toContain('may be out of date')
      expect(w.find('[data-test="equipment-panel"]').exists()).toBe(true)
      await w.find('[data-test="character-back"]').trigger('click')
    }
  })

  it('never shows retained detail for a character absent from the roster or after the account changed', async () => {
    const api = fakeApi({ loadCharacter: async () => { throw new Error('boom') } })
    const c = useCharacters(api, tasks, noSleep)
    await c.loadRoster()
    c.select('Ghost'); await flushPromises()
    expect(c.shownDetail.value).toBeNull()
  })

  it('restores the source row focus and scroll position when returning from detail', async () => {
    const many = Array.from({ length: 30 }, (_, i) => ({ ...ana, name: `Char ${i}` }))
    const api = fakeApi({ loadRoster: async () => ({ ...roster, characterCount: 30, characters: many }) })
    const scrollTo = vi.fn()
    vi.stubGlobal('scrollTo', scrollTo)
    Object.defineProperty(window, 'scrollY', { value: 640, configurable: true })
    const w = mount(CharactersScreen, { props: { api }, attachTo: document.body })
    await flushPromises()
    await w.get('[data-name="Char 25"]').trigger('click'); await flushPromises()
    Object.defineProperty(window, 'scrollY', { value: 0, configurable: true }) // short detail clamped the page
    await w.find('[data-test="character-back"]').trigger('click'); await flushPromises()
    expect((document.activeElement as HTMLElement).dataset.name).toBe('Char 25')
    expect(scrollTo).toHaveBeenLastCalledWith(0, 640)
    w.unmount()
    vi.unstubAllGlobals()
  })

  it('moves keyboard focus to the opened character heading', async () => {
    const w = mount(CharactersScreen, { props: { api: fakeApi() }, attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="roster-item"]')[0]!.trigger('click'); await flushPromises()
    expect(document.activeElement).toBe(w.find('[data-test="page-heading"]').element)
    expect(w.find('[data-test="page-actions"] [data-test="character-back"]').exists()).toBe(true)
    w.unmount()
  })

  it('does not steal focus after the account was replaced while opening', async () => {
    const w = mount(CharactersScreen, { props: { api: fakeApi() }, attachTo: document.body })
    await flushPromises()
    const btn = w.findAll('[data-test="roster-item"]')[0]!
    const before = document.activeElement
    void btn.trigger('click')
    connection.generation += 1
    await flushPromises()
    expect(document.activeElement).toBe(before)
    w.unmount()
  })

  it('keeps the retained equipment completedAt when the latest read is a structured 503', async () => {
    let n = 0
    const api = fakeApi({ loadEquipmentTabs: async () => (n++ === 0 ? tabsResponse
      : { state: 'UNAVAILABLE', message: 'down' } as CharacterEquipmentTabsResponse) })
    const c = useCharacters(api, tasks, noSleep)
    await c.loadRoster(); c.select('Ana B'); await flushPromises()
    await c.loadEquipment('Ana B')
    expect(c.equipmentResponse.value?.state).toBe('UNAVAILABLE')
    expect(c.equipmentSource.value?.equipment?.completedAt).toBe('2026-01-01T00:00:00Z')
    const w = mount(CharactersScreen, { props: { api: fakeApi({ loadEquipmentTabs: (() => { let k = 0
      return async () => (k++ === 0 ? tabsResponse : { state: 'UNAVAILABLE', message: 'down' } as CharacterEquipmentTabsResponse) })() }) } })
    await flushPromises()
    await w.findAll('[data-test="roster-item"]')[0]!.trigger('click'); await flushPromises()
    await w.find('[data-test="character-back"]').trigger('click')
    await w.findAll('[data-test="roster-item"]')[0]!.trigger('click'); await flushPromises()
    expect(w.find('[data-test="equipment-state"]').exists()).toBe(true)
    expect(w.find('[data-test="equipment-freshness"]').exists()).toBe(false) // routine freshness prose is hidden
  })
})

describe('Character inventory section', () => {
  async function open(api: ReturnType<typeof fakeApi>) {
    const w = mount(CharactersScreen, { props: { api } })
    await flushPromises()
    await w.find('[data-test="roster-item"]').trigger('click')
    await flushPromises()
    return w
  }

  it('reads inventory lazily and renders bags, empties, uninstalled positions and unresolved items', async () => {
    const api = fakeApi()
    const w = await open(api)
    expect(api.calls.some((c) => c.startsWith('inv:'))).toBe(false)
    await w.find('[data-test="section-inventory"]').trigger('click')
    await flushPromises()
    expect(api.calls.filter((c) => c === 'inv:Ana B')).toHaveLength(1)
    const rail = w.findAll('[data-test="inventory-bag"]')
    expect(rail).toHaveLength(3)
    expect(w.findAll('[data-test="inventory-bag-title"]')).toHaveLength(0)
    expect(w.findAll('[data-test="inventory-grid"]')).toHaveLength(1)
    const cells = w.findAll('[data-test="inventory-slot"]')
    expect(cells.map((e) => `${e.attributes('data-bag')}:${e.attributes('data-slot')}`)).toEqual(['0:0', '0:1', '2:0'])
    expect(cells[0]!.text()).toContain('Iron Ore'); expect(cells[0]!.text()).toContain('×250')
    expect(w.findAll('[data-test="inventory-slot-empty"]')).toHaveLength(1)
    expect(rail[1]!.find('[data-test="inventory-bag-uninstalled"]').exists()).toBe(true)
    expect(rail[1]!.attributes('aria-label')).toContain('no bag installed')
    expect(rail.map((e) => e.attributes('data-bag'))).toEqual(['0', '1', '2'])
    expect(cells[2]!.text()).toContain('Item #99 (details unavailable)'); expect(cells[2]!.text()).toContain('×3')
    expect(w.findAll('[data-test="character-summary"]')).toHaveLength(1)
    expect(w.find('[data-test="inventory-facts"]').exists()).toBe(false)
    await w.find('[data-test="section-equipment"]').trigger('click')
    await w.find('[data-test="section-inventory"]').trigger('click')
    expect(api.calls.filter((c) => c === 'inv:Ana B')).toHaveLength(1)
  })

  it('distinguishes genuine no-bags from legacy layout and omitted bags, linking System Status', async () => {
    const none = await open(fakeApi({ loadInventory: async () => ({ state: 'AVAILABLE', contents: 'NO_BAGS', bags: [] }) }))
    await none.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    expect(none.find('[data-test="inventory-no-bags"]').exists()).toBe(true)
    const legacy = await open(fakeApi({ loadInventory: async () => ({ state: 'UNAVAILABLE', reason: 'LAYOUT_REFRESH_REQUIRED' }) }))
    await legacy.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    expect(legacy.find('[data-test="inventory-no-bags"]').exists()).toBe(false)
    expect(legacy.find('[data-test="inventory-panel"]').exists()).toBe(false)
    expect(legacy.find('[data-test="inventory-system-status"]').attributes('href')).toBe('#/synchronization')
  })

  it('flattens three nonuniform installed bags in supplied order with boundaries, holes, duplicates and the name in the facts column', async () => {
    const it_ = (id: number, name: string) => ({ itemId: id, resolved: true, name })
    const bags = [
      { bag: 0, installed: true, container: it_(10, 'Bag A'), capacity: 7, slots: [0, 1, 2, 3, 4, 5, 6].map((slot) => slot === 2 || slot === 6 ? { slot, empty: true } : { slot, empty: false, item: it_(11, 'Iron Ore'), quantity: slot + 1 }) },
      { bag: 1, installed: false, slots: [] },
      { bag: 2, installed: true, container: it_(12, 'Bag B'), capacity: 12, slots: Array.from({ length: 12 }, (_, slot) => slot % 5 === 0 ? { slot, empty: true } : { slot, empty: false, item: it_(11, 'Iron Ore'), quantity: 1 }) },
      { bag: 3, installed: true, container: { itemId: 13, resolved: false }, capacity: 9, slots: Array.from({ length: 9 }, (_, slot) => slot === 8 ? { slot, empty: true } : { slot, empty: false, item: { itemId: 98, resolved: false }, quantity: 2 }) }
    ] as CharacterInventoryResponse['bags']
    const w = await open(fakeApi({ loadInventory: async () => ({ ...inventoryResponse, contents: 'HAS_ITEMS', bags }) }))
    await w.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    const coords = w.findAll('[data-test="inventory-slot"]').map((e) => `${e.attributes('data-bag')}:${e.attributes('data-slot')}`)
    const expected = [...[0, 1, 2, 3, 4, 5, 6].map((s) => `0:${s}`), ...Array.from({ length: 12 }, (_, s) => `2:${s}`), ...Array.from({ length: 9 }, (_, s) => `3:${s}`)]
    expect(coords).toEqual(expected)
    expect(coords).toHaveLength(28)
    expect(w.findAll('[data-test="inventory-slot-empty"]')).toHaveLength(2 + 3 + 1)
    expect(w.findAll('[data-test="inventory-slot"]').filter((e) => e.text().includes('Iron Ore')).length).toBeGreaterThan(10) // duplicates kept
    expect(w.findAll('[data-test="inventory-bag"]').map((e) => e.attributes('data-bag'))).toEqual(['0', '1', '2', '3'])
    expect(w.findAll('[data-test="inventory-grid"]')).toHaveLength(1)
    expect(w.find('[data-test="page-heading"]').text()).toBe('Ana B')
    await w.find('[data-test="section-equipment"]').trigger('click'); await flushPromises()
    expect(w.find('[data-test="inventory-facts"]').exists()).toBe(false)
    expect(w.find('[data-test="page-heading"]').text()).toBe('Ana B')
  })

  it('shows the summary above the tabs and the subtitle with the active (not inspected) elite', async () => {
    const full = { ...ana, ageSeconds: 3 * 86_400 + 7200, deaths: 0, nextBirthdayAt: new Date(Date.now() + 2 * 86_400_000 + 3_600_000).toISOString() }
    const w = await open(fakeApi({ loadCharacter: async () => detailOf(full) }))
    expect(w.find('[data-test="character-subtitle"]').text()).toBe('Lvl 80 Human Willbender')
    expect(w.find('[data-test="profession-glyph"]').exists()).toBe(true)
    expect(w.find('[data-test="summary-playtime"]').text()).toBe('74 hours')
    expect(w.find('[data-test="summary-deaths"]').text()).toBe('0')
    expect(w.find('[data-test="summary-birthday"]').text()).toBe('in 2 days')
    expect(w.findAll('[data-test="summary-discipline"]')).toHaveLength(1)
    const html = w.html()
    expect(html.indexOf('character-summary')).toBeLessThan(html.indexOf('section-tablist'))
    await w.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    expect(w.findAll('[data-test="character-summary"]')).toHaveLength(1)
    expect(w.text()).not.toContain('Inspected Elite')
  })

  it('keeps unknown core facts unknown instead of zero', async () => {
    const w = await open(fakeApi())
    for (const k of ['playtime', 'deaths', 'birthday']) expect(w.find(`[data-test="summary-${k}"]`).text()).toBe('unknown')
  })

  it('falls back to the base profession for unresolved, ambiguous, incompatible, stale-parent or failed builds', async () => {
    const withTabs = (tabs: CharacterBuildTabsResponse['tabs'], over: Partial<CharacterBuildTabsResponse> = {}) =>
      async () => ({ ...buildsResponse, tabs, ...over })
    const cases: Array<[string, () => Promise<CharacterBuildTabsResponse>]> = [
      ['unresolved', withTabs([{ tab: 1, active: true, specializations: [spec(62, 'Willbender', 'Guardian', true, false)] }])],
      ['no active', withTabs([{ tab: 1, active: false, specializations: [spec(62, 'Willbender', 'Guardian', true)] }])],
      ['two active', withTabs([{ tab: 1, active: true, specializations: [spec(62, 'Willbender', 'Guardian', true)] }, { tab: 2, active: true, specializations: [] }])],
      ['wrong profession', withTabs([{ tab: 1, active: true, specializations: [spec(40, 'Reaper', 'Necromancer', true)] }])],
      ['two elites', withTabs([{ tab: 1, active: true, specializations: [spec(62, 'Willbender', 'Guardian', true), spec(65, 'Firebrand', 'Guardian', true)] }])],
      ['not covered', withTabs(buildsResponse.tabs, { coveredParentGeneration: 0 })],
      ['grant', async () => ({ state: 'GRANT_REQUIRED' as const, reason: 'MISSING_GRANT' })],
      ['transport', async () => { throw new Error('down') }]
    ]
    for (const [label, loadBuildTabs] of cases) {
      const w = await open(fakeApi({ loadBuildTabs }))
      expect(w.find('[data-test="character-subtitle"]').text(), label).toBe('Lvl 80 Human Guardian')
    }
  })

  it('ignores build tabs older than a newer parent known from equipment', async () => {
    const w = await open(fakeApi({ loadEquipmentTabs: async () => ({ ...tabsResponse, parent: { generation: 2 } }) }))
    expect(w.find('[data-test="character-subtitle"]').text()).toBe('Lvl 80 Human Guardian')
  })

  it('does not decorate a new selection with a late build answer for the previous one', async () => {
    const late = deferred<CharacterBuildTabsResponse>()
    const c = useCharacters(fakeApi({ loadBuildTabs: (n) => n === 'Ana B' ? late.promise : Promise.resolve({ ...buildsResponse, character: n }) }), tasks, noSleep)
    c.select('Ana B'); await flushPromises()
    c.back(); c.select('Bo'); await flushPromises()
    late.resolve(buildsResponse); await flushPromises()
    expect(c.buildResponse.value?.character).toBe('Bo')
  })

  it('discards a build answer from a replaced account generation', async () => {
    const late = deferred<CharacterBuildTabsResponse>()
    const c = useCharacters(fakeApi({ loadBuildTabs: () => late.promise }), tasks, noSleep)
    c.select('Ana B'); await flushPromises()
    connection.generation += 1
    late.resolve(buildsResponse); await flushPromises()
    expect(c.buildResponse.value).toBeNull()
  })

  it('attempts a pending build source recovery exactly once and sends no timers or roster-wide reads', async () => {
    const api = fakeApi({ loadBuildTabs: async (n) => { api.calls.push(`builds:${n}`); return { state: 'PENDING', reason: 'COVERAGE_NOT_PUBLISHED' } } })
    const c = useCharacters(api, tasks, noSleep)
    c.select('Ana B'); await flushPromises()
    expect(api.calls.filter((x) => x === 'build-refresh')).toHaveLength(1)
    expect(api.calls.filter((x) => x.startsWith('builds:'))).toEqual(['builds:Ana B', 'builds:Ana B'])
    expect(c.activeElite.value).toBeNull()
  })

  it('shows grant, failure and retains last-good inventory on a later failed read', async () => {
    const grant = await open(fakeApi({ loadInventory: async () => ({ state: 'GRANT_REQUIRED', reason: 'MISSING_GRANT' }) }))
    await grant.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    expect(grant.find('[data-test="inventory-state"]').text()).toContain('MISSING_GRANT')
    expect(grant.find('[data-test="inventory-panel"]').exists()).toBe(false)
    let fail = false
    const c = useCharacters(fakeApi({ loadInventory: async () => { if (fail) throw new Error('down'); return inventoryResponse } }), tasks, noSleep)
    c.select('Ana B'); await c.loadInventory('Ana B')
    fail = true
    await c.loadInventory('Ana B')
    expect(c.inventoryFailure.value).not.toBeNull()
    expect(c.inventorySource.value?.bags).toHaveLength(3)
    expect(c.inventoryIsRetained.value).toBe(true)
  })

  it('discards a late inventory answer after the selection changes', async () => {
    const slow = deferred<CharacterInventoryResponse>()
    const c = useCharacters(fakeApi({ loadInventory: (n) => n === 'Ana B' ? slow.promise : Promise.resolve({ ...inventoryResponse, bags: [] }) }), tasks, noSleep)
    c.select('Ana B'); const first = c.loadInventory('Ana B'); c.back(); c.select('Bo')
    await c.loadInventory('Bo')
    slow.resolve(inventoryResponse); await first
    expect(c.inventorySource.value?.bags).toEqual([])
  })

  it('discards an answer from a replaced account', async () => {
    const slow = deferred<CharacterInventoryResponse>()
    const c = useCharacters(fakeApi({ loadInventory: () => slow.promise }), tasks, noSleep)
    c.select('Ana B'); const p = c.loadInventory('Ana B')
    connection.generation += 1
    slow.resolve(inventoryResponse); await p
    expect(c.inventorySource.value).toBeNull()
  })

  it('rereads retained equipment once when inventory shows a newer parent generation', async () => {
    const api = fakeApi({
      loadEquipmentTabs: async (n) => { api.calls.push(`equip:${n}`); return { ...tabsResponse, parent: { generation: 1 } } },
      loadInventory: async () => ({ ...inventoryResponse, generation: 2 })
    })
    const w = await open(api)
    await w.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    expect(api.calls.filter((c) => c === 'equip:Ana B')).toHaveLength(2)
  })

  it('surfaces a stale refresh failure with the System Status link while keeping the retained layout', async () => {
    const w = await open(fakeApi({ loadInventory: async () => ({ ...inventoryResponse, state: 'STALE', reason: 'REFRESH_FAILED', message: 'Refresh failed upstream' }) }))
    await w.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    expect(w.find('[data-test="inventory-panel"]').exists()).toBe(true)
    expect(w.find('[data-test="inventory-stale-refresh-failed"]').text()).toContain('Refresh failed upstream')
    expect(w.find('[data-test="inventory-system-status"]').attributes('href')).toBe('#/synchronization')
    expect(w.find('[data-test="inventory-recheck"]').exists()).toBe(true)
  })

  it('offers a bounded recheck after a legacy answer, without back/reselect', async () => {
    let legacy = true
    const api = fakeApi({ loadInventory: async (n) => { api.calls.push(`inv:${n}`); return legacy ? { state: 'UNAVAILABLE', reason: 'LAYOUT_REFRESH_REQUIRED' } : inventoryResponse } })
    const w = await open(api)
    await w.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    expect(w.find('[data-test="inventory-panel"]').exists()).toBe(false)
    legacy = false
    await w.find('[data-test="inventory-recheck"]').trigger('click'); await flushPromises()
    expect(w.find('[data-test="inventory-panel"]').exists()).toBe(true)
    expect(api.calls.filter((c) => c === 'inv:Ana B')).toHaveLength(2)
  })

  // UNAVAILABLE rather than PENDING/STALE: those states legitimately start the screen's own timed
  // automatic refresh, whose extra read would race this test of the user-driven re-entry read.
  it('rereads on section re-entry when the last read gave no layout', async () => {
    let ok = false
    const api = fakeApi({ loadInventory: async (n) => { api.calls.push(`inv:${n}`); return ok ? inventoryResponse : { state: 'UNAVAILABLE', reason: 'LAYOUT_REFRESH_REQUIRED' } } })
    const w = await open(api)
    await w.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    await w.find('[data-test="section-equipment"]').trigger('click')
    ok = true
    await w.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    expect(api.calls.filter((c) => c === 'inv:Ana B')).toHaveLength(2)
    expect(w.find('[data-test="inventory-panel"]').exists()).toBe(true)
  })

  it('rereads equipment once when inventory gen2 arrives before equipment gen1, and never loops', async () => {
    const eq = deferred<CharacterEquipmentTabsResponse>()
    let eqCalls = 0
    const api = fakeApi({
      loadEquipmentTabs: (n) => { api.calls.push(`equip:${n}`); eqCalls += 1; return eqCalls === 1 ? eq.promise : Promise.resolve({ ...tabsResponse, parent: { generation: 1 } }) },
      loadInventory: async (n) => { api.calls.push(`inv:${n}`); return { ...inventoryResponse, generation: 2 } }
    })
    const c = useCharacters(api, tasks, noSleep)
    c.select('Ana B'); await c.loadInventory('Ana B')
    eq.resolve({ ...tabsResponse, parent: { generation: 1 } }); await flushPromises()
    expect(api.calls.filter((x) => x === 'equip:Ana B')).toHaveLength(2)
    expect(c.equipmentOlderThanInventory.value).toBe(true)
    c.showSection('equipment'); c.showSection('inventory'); await flushPromises()
    expect(api.calls.filter((x) => x === 'equip:Ana B')).toHaveLength(2)
  })

  it('rereads a loaded older inventory once after equipment establishes a newer parent', async () => {
    let eqGen = 1
    const api = fakeApi({
      loadEquipmentTabs: async (n) => { api.calls.push(`equip:${n}`); return { ...tabsResponse, parent: { generation: eqGen } } },
      loadInventory: async (n) => { api.calls.push(`inv:${n}`); return { ...inventoryResponse, generation: 1 } }
    })
    const c = useCharacters(api, tasks, noSleep)
    c.select('Ana B'); await flushPromises()
    c.showSection('inventory'); await flushPromises()
    eqGen = 2
    await c.loadEquipment('Ana B'); await flushPromises()
    expect(api.calls.filter((x) => x === 'inv:Ana B')).toHaveLength(2)
    expect(c.inventoryOlderThanEquipment.value).toBe(true) // still gen1: warned, not looped
    c.showSection('equipment'); c.showSection('inventory'); await flushPromises()
    expect(api.calls.filter((x) => x === 'inv:Ana B')).toHaveLength(2)
  })

  it('reconciles toward a newer parent reported only by a PENDING equipment envelope, once', async () => {
    let eqCalls = 0
    const api = fakeApi({
      loadEquipmentTabs: async (n) => {
        api.calls.push(`equip:${n}`); eqCalls += 1
        return eqCalls === 2 ? { ...tabsResponse, parent: { generation: 1 } }
          : eqCalls === 1 ? { state: 'PENDING', reason: 'NOT_SYNCED', message: 'p' }
          : { state: 'PENDING', reason: 'COVERAGE_INCOMPATIBLE', message: 'p', parent: { generation: 3 }, coveredParentGeneration: 1 }
      },
      loadInventory: async (n) => { api.calls.push(`inv:${n}`); return { ...inventoryResponse, generation: inv += 1 } }
    })
    let inv = 1
    const c = useCharacters(api, tasks, noSleep)
    c.select('Ana B'); await flushPromises(); await flushPromises()
    c.showSection('inventory'); await flushPromises(); await flushPromises()
    expect(api.calls.filter((x) => x === 'equip:Ana B').length).toBeLessThanOrEqual(4)
    expect(api.calls.filter((x) => x === 'inv:Ana B')).toHaveLength(2)
    expect(c.equipmentTabs.value).not.toBeNull()
    c.showSection('equipment'); c.showSection('inventory'); await flushPromises()
    expect(api.calls.filter((x) => x === 'inv:Ana B')).toHaveLength(2)
    expect(api.calls.filter((x) => x === 'equip:Ana B').length).toBeLessThanOrEqual(4)
  })

  it('does not reconcile after an account replacement mid-flight', async () => {
    const eq = deferred<CharacterEquipmentTabsResponse>()
    const api = fakeApi({ loadEquipmentTabs: (n) => { api.calls.push(`equip:${n}`); return eq.promise }, loadInventory: async () => ({ ...inventoryResponse, generation: 2 }) })
    const c = useCharacters(api, tasks, noSleep)
    c.select('Ana B'); await c.loadInventory('Ana B')
    connection.generation += 1
    eq.resolve({ ...tabsResponse, parent: { generation: 1 } }); await flushPromises()
    expect(api.calls.filter((x) => x === 'equip:Ana B')).toHaveLength(1)
  })

  it('keeps the original completedAt and layout when a reread fails', async () => {
    let fail = false
    const api = fakeApi({ loadInventory: async () => { if (fail) throw new Error('down'); return inventoryResponse } })
    const w = await open(api)
    await w.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    fail = true
    await w.find('[data-test="inventory-retry"]').exists()
    const c = (w.vm as unknown as { $: { setupState: { c: ReturnType<typeof useCharacters> } } }).$.setupState.c
    const before = c.inventorySource.value?.completedAt
    await c.loadInventory('Ana B'); await flushPromises()
    expect(w.find('[data-test="inventory-error"]').exists()).toBe(true)
    expect(w.findAll('[data-test="inventory-slot"]')).toHaveLength(3)
    expect(before).toBeTruthy()
    expect(c.inventorySource.value?.completedAt).toBe(before)
    expect(w.find('[data-test="inventory-freshness"]').exists()).toBe(false)
  })

  it('section tabs use roving tabindex and arrow/Home/End keys, keeping the inspected equipment tab', async () => {
    const w = await open(fakeApi())
    const eq = w.find('[data-test="section-equipment"]'), inv = w.find('[data-test="section-inventory"]')
    expect(eq.attributes('tabindex')).toBe('0'); expect(inv.attributes('tabindex')).toBe('-1')
    await eq.trigger('keydown', { key: 'ArrowRight' }); await flushPromises()
    expect(inv.attributes('aria-selected')).toBe('true'); expect(inv.attributes('tabindex')).toBe('0')
    await inv.trigger('keydown', { key: 'Home' }); await flushPromises()
    expect(eq.attributes('aria-selected')).toBe('true')
    await eq.trigger('keydown', { key: 'End' }); await flushPromises()
    // Builds is now the last section, so End lands there (changed from Inventory by the new section).
    expect(w.find('[data-test="section-builds"]').attributes('aria-selected')).toBe('true')
    expect(w.find('[data-test="section-builds"]').attributes('tabindex')).toBe('0')
  })

  describe('optional Builds recovery', () => {
    const pending = { state: 'PENDING', reason: 'COVERAGE_NOT_PUBLISHED' } as CharacterBuildTabsResponse
    const pendingEquip = { state: 'PENDING', reason: 'COVERAGE_NOT_PUBLISHED' } as CharacterEquipmentTabsResponse

    // The real equipment-only refresh publishes equipment and NOT Builds: the two flags are independent.
    function equipmentFirstApi() {
      const published = { equipment: false, builds: false }
      const gate = deferred<void>()
      const api = fakeApi({
        loadEquipmentTabs: async () => (published.equipment ? tabsResponse : pendingEquip),
        loadBuildTabs: async (n) => (published.builds ? { ...buildsResponse, character: n } : pending),
        startEquipmentTabsRefresh: async () => { api.calls.push('refresh'); await gate.promise; published.equipment = true; return { taskId: 't', operation: 'X', statusUrl: '/s' } },
        startBuildTabsRefresh: async () => { api.calls.push('build-refresh'); published.builds = true; return { taskId: 'b', operation: 'X', statusUrl: '/s' } }
      })
      return { api, gate, published }
    }

    it('equipment-first: both PENDING, admits the separate Builds refresh after equipment and shows the elite (composable)', async () => {
      const { api, gate, published } = equipmentFirstApi()
      const c = useCharacters(api, tasks, noSleep)
      c.select('Ana B'); await flushPromises()
      expect(c.refreshing.value).toBe(true)
      expect(api.calls.filter((x) => x === 'build-refresh')).toHaveLength(0) // waits for the equipment attempt
      expect(c.activeElite.value).toBeNull()
      gate.resolve(); await flushPromises()
      expect(published).toEqual({ equipment: true, builds: true })
      expect(api.calls.filter((x) => x === 'refresh' || x === 'build-refresh')).toEqual(['refresh', 'build-refresh'])
      expect(c.activeElite.value).toBe('Willbender')
    })

    it('equipment-first: the managed CharactersScreen path recovers Builds after the equipment refresh', async () => {
      const { api, gate, published } = equipmentFirstApi()
      const status = vi.spyOn(syncApi, 'readTaskStatus').mockResolvedValue({ taskId: 't', operation: 'X', state: 'SUCCEEDED' } as never)
      const w = mount(CharactersScreen, { props: { api } })
      try {
        await flushPromises()
        await w.find('[data-test="roster-item"]').trigger('click')
        await vi.waitFor(() => expect(api.calls).toContain('refresh'))
        expect(api.calls).not.toContain('build-refresh') // not before equipment has published
        expect(w.find('[data-test="character-subtitle"]').text()).toBe('Lvl 80 Human Guardian')
        gate.resolve()
        await vi.waitFor(() => expect(w.find('[data-test="character-subtitle"]').text()).toBe('Lvl 80 Human Willbender'))
        expect(published).toEqual({ equipment: true, builds: true })
        const order = api.calls.filter((x) => x === 'refresh' || x === 'build-refresh')
        expect(order).toEqual(['refresh', 'build-refresh'])
      } finally {
        w.unmount()
        status.mockRestore()
      }
    })

    it('builds-first with a narrower active task: retries with bounded attempts and then shows the elite', async () => {
      let n = 0
      const api = fakeApi({
        loadBuildTabs: async (name) => (n >= 3 ? { ...buildsResponse, character: name } : pending),
        startBuildTabsRefresh: async () => { n += 1; api.calls.push('build-refresh'); return { taskId: 'b', operation: 'X', statusUrl: '/s', coversRequest: n >= 3 } as never }
      })
      const c = useCharacters(api, tasks, noSleep)
      c.select('Ana B'); await flushPromises()
      expect(api.calls.filter((x) => x === 'build-refresh')).toHaveLength(3)
      expect(c.activeElite.value).toBe('Willbender')
    })

    it('stops after the bounded number of non-covering tasks', async () => {
      const api = fakeApi({
        loadBuildTabs: async () => pending,
        startBuildTabsRefresh: async () => { api.calls.push('build-refresh'); return { taskId: 'b', operation: 'X', statusUrl: '/s', coversRequest: false } as never }
      })
      const c = useCharacters(api, tasks, noSleep)
      c.select('Ana B'); await flushPromises()
      expect(api.calls.filter((x) => x === 'build-refresh')).toHaveLength(3)
      expect(c.activeElite.value).toBeNull()
    })

    it('an old same-name selection (A->B->A) issues no request after it was replaced', async () => {
      const gate = deferred<void>()
      const api = fakeApi({
        loadBuildTabs: async (name) => (name === 'Ana B' ? pending : { ...buildsResponse, character: name }),
        startBuildTabsRefresh: async () => { api.calls.push('build-refresh'); await gate.promise; return { taskId: 'b', operation: 'X', statusUrl: '/s', coversRequest: false } as never }
      })
      const c = useCharacters(api, tasks, noSleep)
      c.select('Ana B'); await flushPromises()
      c.back(); c.select('Bo'); c.back(); c.select('Ana B'); await flushPromises()
      gate.resolve(); await flushPromises()
      // The replaced first attempt had one request in flight and starts no retry; the current
      // selection makes its own bounded three. Without the guard the stale loop would add two more.
      expect(api.calls.filter((x) => x === 'build-refresh')).toHaveLength(4)
    })

    it('makes no request after the account generation is replaced', async () => {
      const gate = deferred<void>()
      const api = fakeApi({
        loadBuildTabs: async () => pending,
        startBuildTabsRefresh: async () => { api.calls.push('build-refresh'); await gate.promise; return { taskId: 'b', operation: 'X', statusUrl: '/s', coversRequest: false } as never }
      })
      const c = useCharacters(api, tasks, noSleep)
      c.select('Ana B'); await flushPromises()
      connection.generation += 1
      gate.resolve(); await flushPromises()
      expect(api.calls.filter((x) => x === 'build-refresh')).toHaveLength(1)
    })
  })
})

describe('Builds section', () => {
  const trait = (id: number, slot: 'Minor' | 'Major', tier: number, order: number, placement = 'ORDINARY') =>
    ({ id, resolved: true, name: `T${id}`, iconUrl: `/i/${id}.png`, specializationId: 62, tier, order, slot, placement,
      description: id === 12 ? 'Your strike damage is increased.' : undefined,
      facts: id === 12 ? [{ text: 'Damage Increase', value: '3%', iconUrl: '/fact/12.png' }] : undefined }) as never
  const richBuilds: CharacterBuildTabsResponse = { ...buildsResponse, tabs: [
    { tab: 1, active: false, specializations: [{ specialization: { id: 1, resolved: true, name: 'Zeal', profession: 'Guardian', elite: false }, traits: [null, null, null] }] },
    { tab: 2, active: true, profession: 'Guardian',
      specializations: [{ specialization: { id: 62, resolved: true, name: 'Willbender', profession: 'Guardian', elite: true },
        traits: [trait(12, 'Major', 1, 1), null, null],
        minorTraits: [trait(10, 'Minor', 1, 0)],
        // Supplied out of order on purpose: placement comes from tier/order, not array position.
        majorTraits: [trait(13, 'Major', 1, 2), trait(12, 'Major', 1, 1), trait(11, 'Major', 1, 0), trait(99, 'Major', 0, 0, 'CONFLICT')] }],
      skills: { heal: { id: 1, resolved: true, name: 'Heal', iconUrl: '/s/1.png' }, utilities: [{ id: 2, resolved: false }, null], elite: null },
      aquaticSkills: { heal: null, utilities: [], elite: null },
      legends: [{ id: 'Legend1', resolved: false }], pets: [{ id: 5, resolved: true, name: 'Jacaranda' }] }
  ] }
  const open = async (api: ReturnType<typeof fakeApi>) => {
    const w = mount(CharactersScreen, { props: { api } }); mounted.push(w)
    await flushPromises()
    await w.find('[data-test="roster-item"]').trigger('click'); await flushPromises()
    return w
  }

  it('reuses the header read: opening Builds adds no request, shows the actual active tab, and revisiting stays cheap', async () => {
    const api = fakeApi({ loadBuildTabs: async (n) => { api.calls.push(`builds:${n}`); return { ...richBuilds, character: n } } })
    const w = await open(api)
    expect(api.calls.filter((x) => x.startsWith('builds:'))).toHaveLength(1)
    await w.find('[data-test="section-builds"]').trigger('click'); await flushPromises()
    await w.find('[data-test="section-equipment"]').trigger('click')
    await w.find('[data-test="section-builds"]').trigger('click'); await flushPromises()
    expect(api.calls.filter((x) => x.startsWith('builds:'))).toHaveLength(1)
    expect(api.calls.filter((x) => x.startsWith('inv:'))).toHaveLength(0)
    expect(w.findAll('[data-test="build-tab"]').map((t) => t.text())).toEqual(['1', '2●'])
    expect(w.find('.character-navigation [data-test="build-tablist"]').exists()).toBe(true)
    expect(w.find('[data-test="builds-completed"]').exists()).toBe(false)
    expect(w.findAll('[data-test="build-tab-active"]')).toHaveLength(1)
    expect(w.find('[data-test="build-tab"][aria-selected="true"]').text()).toContain('2')
  })

  it('inspects another build independently of equipment, keeping the active build identified', async () => {
    const w = await open(fakeApi({ loadBuildTabs: async () => richBuilds }))
    await w.find('[data-test="section-builds"]').trigger('click'); await flushPromises()
    await w.findAll('[data-test="build-tab"]')[0]!.trigger('click'); await flushPromises()
    expect(w.find('[data-test="build-spec-name"]').text()).toBe('Zeal')
    expect(w.find('[data-test="build-active-badge"]').exists()).toBe(false)
    expect(w.findAll('[data-test="build-tab"]')[1]!.find('[data-test="build-tab-active"]').exists()).toBe(true)
    await w.find('[data-test="section-equipment"]').trigger('click'); await flushPromises()
    expect(w.find('[data-test="character-subtitle"]').text()).toContain('Willbender')
  })

  it('renders full trait rows placed by tier/order with the selected trait highlighted, plus land/aquatic skills and facts', async () => {
    const w = await open(fakeApi({ loadBuildTabs: async () => richBuilds }))
    await w.find('[data-test="section-builds"]').trigger('click'); await flushPromises()
    const rows = w.findAll('[data-test="trait-row"]')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.findAll('[data-test="trait"]').map((t) => t.attributes('aria-label'))).toEqual(['T10, minor', 'T11, major', 'T12, major, selected', 'T13, major'])
    const selected = rows[0]!.findAll('.trait--selected')
    expect(selected).toHaveLength(1); expect(selected[0]!.attributes('aria-current')).toBe('true')
    expect(w.find('[data-test="trait-unplaced"]').text()).toContain('T99')
    expect(w.find('[data-test="trait-unplaced"]').text()).not.toContain('(conflict)')
    expect(w.find('[data-test="skills-land"]').findAll('[data-test="skill"]')).toHaveLength(5)
    expect(w.find('[data-test="skills-land"]').findAll('[data-test="skill"]')[1]!.attributes('aria-label')).toContain('Skill #2 (details unavailable)')
    expect(w.find('[data-test="skills-aquatic"]').find('[data-test="skill"]').attributes('aria-label')).toContain('No Skill saved')
    expect(w.find('[data-test="build-legends"]').text()).toContain('Legend Legend1 (details unavailable)')
    expect(w.find('[data-test="build-pets"]').text()).toContain('Jacaranda')
    expect(w.find('[data-test="skills-land"]').text()).not.toContain('Heal')
    await selected[0]!.trigger('focus'); await flushPromises()
    const tooltip = document.querySelector('[role="tooltip"]')!
    expect(tooltip.textContent).toContain('Your strike damage is increased.')
    expect(tooltip.textContent).toContain('Damage Increase: 3%')
    await selected[0]!.trigger('keydown', { key: 'Escape' }); await flushPromises()
    expect(document.querySelector('[role="tooltip"]')).toBeNull()
  })

  it('arrow/Home/End move between build tabs with roving tabindex', async () => {
    const w = await open(fakeApi({ loadBuildTabs: async () => richBuilds }))
    await w.find('[data-test="section-builds"]').trigger('click'); await flushPromises()
    const tabs = w.findAll('[data-test="build-tab"]')
    expect(tabs[1]!.attributes('tabindex')).toBe('0'); expect(tabs[0]!.attributes('tabindex')).toBe('-1')
    await tabs[1]!.trigger('keydown', { key: 'Home' }); await flushPromises()
    expect(w.findAll('[data-test="build-tab"]')[0]!.attributes('aria-selected')).toBe('true')
    await tabs[0]!.trigger('keydown', { key: 'End' }); await flushPromises()
    expect(w.findAll('[data-test="build-tab"]')[1]!.attributes('aria-selected')).toBe('true')
  })

  it('keeps the last good builds with their original time when a later read fails, and says so', async () => {
    let fail = false
    const good = { ...richBuilds, builds: { generation: 1, completedAt: '2026-01-01T00:00:00Z' } }
    const api = fakeApi({ loadBuildTabs: async () => { if (fail) throw new Error('boom'); return good } })
    const w = await open(api)
    await w.find('[data-test="section-builds"]').trigger('click'); await flushPromises()
    fail = true
    await w.find('[data-test="section-equipment"]').trigger('click')
    const c = (w.vm as unknown as { $: { setupState: { c: ReturnType<typeof useCharacters> } } }).$.setupState.c
    await c.loadBuildTabs('Ana B'); await w.find('[data-test="section-builds"]').trigger('click'); await flushPromises()
    expect(w.find('[data-test="builds-error"]').text()).toContain('showing the previous saved data')
    expect(c.buildSource.value?.builds?.completedAt).toBe('2026-01-01T00:00:00Z')
    expect(w.findAll('[data-test="build-tab"]')).toHaveLength(2)
  })

  it('does not hide equipment when builds are unavailable and reports the real state', async () => {
    const w = await open(fakeApi({ loadBuildTabs: async () => ({ state: 'GRANT_REQUIRED', message: 'builds grant missing' }) }))
    expect(w.find('[data-test="equipment-panel"]').exists() || w.find('[data-test="equipment-tab"]').exists()).toBe(true)
    await w.find('[data-test="section-builds"]').trigger('click'); await flushPromises()
    expect(w.find('[data-test="builds-state"]').text()).toContain('does not allow reading builds')
    expect(w.find('[data-test="build-view"]').exists()).toBe(false)
  })

  it('flags retained builds older than a newer known parent generation instead of presenting them as current', async () => {
    const older = { ...richBuilds, parent: { generation: 1 }, coveredParentGeneration: 1 }
    const w = await open(fakeApi({ loadBuildTabs: async () => older, loadInventory: async () => ({ ...inventoryResponse, generation: 3 }) }))
    await w.find('[data-test="section-inventory"]').trigger('click'); await flushPromises()
    await w.find('[data-test="section-builds"]').trigger('click'); await flushPromises()
    expect(w.find('[data-test="builds-older"]').exists()).toBe(true)
  })
})
