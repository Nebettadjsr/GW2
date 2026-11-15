import { shellProvide } from '@/sync/__tests__/shellProvide'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { connection } from '@/api/accountSession'
import type { BankContents, MaterialStorage } from '@/api/types'
import { resetMaterialProtection } from '@/materials/materialProtectionState'
import BankScreen from '../BankScreen.vue'
import InventoryTile from '../InventoryTile.vue'
import MaterialsScreen from '../MaterialsScreen.vue'
import { FakeAccountApi } from './accountFixtures'

const ITEM_ID = 19697
const ITEM_NAME = 'Copper Ore'
const ICON_URL = '/api/items/19697/icon/controlled-copper.png'
const mounted: VueWrapper[] = []

beforeEach(() => {
  localStorage.clear()
  resetMaterialProtection()
  connection.accountId = 'aaaaaaaa-0000-0000-0000-000000000235'
  vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('Unexpected network request'))))
})

afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  connection.accountId = null
  resetMaterialProtection()
  localStorage.clear()
  document.body.replaceChildren()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function selectableTile(iconUrl: string | null = ICON_URL) {
  const selected = ref(false)
  const toggles: number[] = []
  const host = mount(defineComponent({
    setup: () => () => h(InventoryTile, {
      itemId: ITEM_ID, count: 300, iconUrl, title: ITEM_NAME,
      selected: selected.value,
      onToggle: (itemId: number) => {
        toggles.push(itemId)
        selected.value = !selected.value
      }
    })
  }), { attachTo: document.body })
  mounted.push(host)
  return { tile: host.getComponent(InventoryTile), toggles }
}

async function click(element: Element): Promise<void> {
  if (element instanceof HTMLElement) element.click()
  else element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await nextTick()
}

async function pressSpace(checkbox: HTMLInputElement): Promise<void> {
  const down = new KeyboardEvent('keydown', { key: ' ', code: 'Space', bubbles: true, cancelable: true })
  const up = new KeyboardEvent('keyup', { key: ' ', code: 'Space', bubbles: true, cancelable: true })
  checkbox.dispatchEvent(down)
  await nextTick()
  checkbox.dispatchEvent(up)
  // JSDOM does not activate native controls from keys. Model the browser's default click only
  // when the component has not prevented it; the controlled browser check verifies real keys.
  if (!down.defaultPrevented && !up.defaultPrevented) checkbox.click()
  await nextTick()
}

async function openScreen(screen: typeof BankScreen | typeof MaterialsScreen, api: FakeAccountApi) {
  const wrapper = mount(screen, {
    props: { api }, attachTo: document.body,
    global: { provide: shellProvide() }
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('whole-tile selection', () => {
  it.each([
    ['background', '.inventory-tile', ICON_URL],
    ['image', '[data-test="item-icon-image"]', ICON_URL],
    ['fallback icon', '[data-test="item-icon-fallback"]', null],
    ['count', '[data-test="item-count"]', ICON_URL],
    ['native checkbox', 'input[type="checkbox"]', ICON_URL]
  ] as const)('%s toggles once per click, including bubbled native events', async (_part, selector, iconUrl) => {
    const { tile, toggles } = selectableTile(iconUrl)
    const checkbox = tile.get('input[type="checkbox"]').element as HTMLInputElement

    await click(tile.get(selector).element)
    expect(toggles).toEqual([ITEM_ID])
    expect(checkbox.checked).toBe(true)

    await click(tile.get(selector).element)
    expect(toggles).toEqual([ITEM_ID, ITEM_ID])
    expect(checkbox.checked).toBe(false)
  })

  it('keeps the native checkbox focusable and toggles once for Space activation', async () => {
    const { tile, toggles } = selectableTile()
    const checkbox = tile.get('input[type="checkbox"]').element as HTMLInputElement
    checkbox.focus()
    expect(document.activeElement).toBe(checkbox)
    expect(checkbox.tabIndex).toBe(0)
    expect(checkbox.disabled).toBe(false)

    await pressSpace(checkbox)
    expect(toggles).toEqual([ITEM_ID])
    expect(checkbox.checked).toBe(true)
    expect(document.activeElement).toBe(checkbox)

    await pressSpace(checkbox)
    expect(toggles).toEqual([ITEM_ID, ITEM_ID])
    expect(checkbox.checked).toBe(false)
  })

  it('does not select from right-click, menu keys, or Escape', async () => {
    const { tile, toggles } = selectableTile()
    const checkbox = tile.get('input[type="checkbox"]')
    await tile.trigger('contextmenu', { button: 2 })
    for (const event of [{ key: 'ContextMenu' }, { key: 'F10', shiftKey: true }, { key: 'Escape' }]) {
      await checkbox.trigger('keydown', event)
      await checkbox.trigger('keyup', event)
    }
    expect(toggles).toEqual([])
    expect((checkbox.element as HTMLInputElement).checked).toBe(false)
  })

  it('uses the supplied item name for hover and the selectable control without fetching', async () => {
    const { tile } = selectableTile()
    await tile.trigger('mouseenter')
    expect(tile.attributes('title')).toContain(ITEM_NAME)
    expect(tile.get('input[type="checkbox"]').attributes('aria-label')).toContain(ITEM_NAME)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('leaves a nonselectable tile inert at every visible click target', async () => {
    const tile = mount(InventoryTile, {
      props: { itemId: ITEM_ID, count: 300, iconUrl: ICON_URL, title: ITEM_NAME },
      attachTo: document.body
    })
    mounted.push(tile)
    expect(tile.find('input[type="checkbox"]').exists()).toBe(false)
    for (const selector of ['.inventory-tile', '[data-test="item-icon-image"]', '[data-test="item-count"]']) {
      await click(tile.get(selector).element)
    }
    await tile.trigger('keydown', { key: ' ', code: 'Space' })
    await tile.trigger('keyup', { key: ' ', code: 'Space' })
    expect(tile.emitted('toggle')).toBeUndefined()
  })

  it('preserves character-style display tiles with a one-unit badge and no selection control', async () => {
    const title = 'Bag 2, slot 7, Copper Ore'
    const tile = mount(InventoryTile, {
      props: { itemId: ITEM_ID, count: 1, iconUrl: ICON_URL, title, showOne: true },
      attachTo: document.body
    })
    mounted.push(tile)
    expect(tile.attributes('title')).toBe(title)
    expect(tile.get('[data-test="item-count"]').text()).toBe('1')
    expect(tile.get('img').attributes('src')).toBe(ICON_URL)
    expect(tile.find('input[type="checkbox"]').exists()).toBe(false)
    await click(tile.get('img').element)
    await click(tile.get('[data-test="item-count"]').element)
    expect(tile.emitted('toggle')).toBeUndefined()
  })
})

describe('Bank and Materials item identities', () => {
  const bank: BankContents = {
    slotCount: 4,
    slots: [
      { slot: 0, itemId: ITEM_ID, count: 300, itemName: ITEM_NAME, iconUrl: ICON_URL, rarity: 'Basic' },
      { slot: 1, itemId: null, count: null, itemName: null, iconUrl: null, rarity: null },
      { slot: 2, itemId: 900001, count: 4, itemName: 'Controlled Junk', iconUrl: null, rarity: null, protectionSelectable: false },
      { slot: 3, itemId: 12134, count: 2, itemName: null, iconUrl: null, rarity: null }
    ]
  }
  const materials: MaterialStorage = {
    categoryCount: 1,
    categories: [{
      category: 5, name: 'Controlled Materials', order: 1,
      materials: [
        { position: 0, itemId: ITEM_ID, count: 0, itemName: ITEM_NAME, iconUrl: null, rarity: 'Basic' },
        { position: 1, itemId: 19698, count: 250, itemName: null, iconUrl: null, rarity: null },
        { position: 2, itemId: 12134, count: 3, iconUrl: null, rarity: null }
      ]
    }]
  }

  it('shows cached Bank names and quantities without fetching on hover', async () => {
    const api = new FakeAccountApi()
    api.bankHandler = async () => bank
    const screen = await openScreen(BankScreen, api)
    const named = screen.get('[data-slot="0"]')
    await named.trigger('mouseenter')
    expect(named.attributes('title')).toContain(ITEM_NAME)
    expect(named.attributes('aria-label')).toContain(ITEM_NAME)
    expect(named.get('input[type="checkbox"]').attributes('aria-label')).toContain(ITEM_NAME)
    expect(named.get('[data-test="item-count"]').text()).toBe('300')
    expect(api.bankCalls).toBe(1)
    expect(api.materialCalls).toBe(0)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('keeps Bank holes and ineligible items inert and uses an ID when metadata is missing', async () => {
    const api = new FakeAccountApi()
    api.bankHandler = async () => bank
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    const screen = await openScreen(BankScreen, api)
    expect(screen.findAll('[data-test="bank-slot"]').map(slot => slot.attributes('data-slot')))
      .toEqual(['0', '1', '2', '3'])
    expect(screen.get('[data-slot="3"]').attributes('title')).toContain('12134')
    const empty = screen.get('[data-slot="1"]')
    const ineligible = screen.get('[data-slot="2"]')
    expect(empty.findComponent(InventoryTile).exists()).toBe(false)
    expect(ineligible.find('input[type="checkbox"]').exists()).toBe(false)
    await click(empty.element)
    await click(ineligible.get('.inventory-tile').element)
    expect(ineligible.getComponent(InventoryTile).emitted('toggle')).toBeUndefined()
    expect(writes).not.toHaveBeenCalled()
    expect(api.bankCalls).toBe(1)
    expect(api.materialCalls).toBe(0)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('shows supplied Materials names without fetching on hover', async () => {
    const api = new FakeAccountApi()
    api.materialsHandler = async () => materials
    const screen = await openScreen(MaterialsScreen, api)
    const tiles = screen.findAllComponents(InventoryTile)
    for (const tile of tiles) await tile.trigger('mouseenter')
    expect(tiles).toHaveLength(3)
    expect(tiles[0]!.attributes('title')).toContain(ITEM_NAME)
    expect(tiles[0]!.get('input[type="checkbox"]').attributes('aria-label')).toContain(ITEM_NAME)
    expect(api.materialCalls).toBe(1)
    expect(api.bankCalls).toBe(0)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('uses fallback IDs for null or omitted names and keeps unowned catalog positions selectable', async () => {
    const api = new FakeAccountApi()
    api.materialsHandler = async () => materials
    const screen = await openScreen(MaterialsScreen, api)
    const tiles = screen.findAllComponents(InventoryTile)
    expect(tiles[1]!.attributes('title')).toContain('19698')
    expect(tiles[2]!.attributes('title')).toContain('12134')
    // An unowned catalog position is not an empty Bank slot and remains selectable.
    await click(tiles[0]!.get('input[type="checkbox"]').element)
    expect((tiles[0]!.get('input[type="checkbox"]').element as HTMLInputElement).checked).toBe(true)
    expect(tiles[0]!.emitted('toggle')).toEqual([[ITEM_ID]])
    expect(api.materialCalls).toBe(1)
    expect(api.bankCalls).toBe(0)
    expect(fetch).not.toHaveBeenCalled()
  })
})
