import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import EquipmentTile from '../EquipmentTile.vue'
import type { EquipmentEntry } from '@/api/types'

const rune = { itemId: 2, resolved: true, name: 'Rune', details: { attributes: { CritDamage: 5 }, effects: ['+5 Ferocity', 'Extra effect'],
  runeBonuses: ['one', 'two', 'three'], description: 'Rune text' } }
const entry = (type: string): EquipmentEntry => ({ slot: 'Helm', item: { itemId: 1, resolved: true, name: 'Helm', rarity: 'Rare', type,
  details: { description: 'Item text', vendorValue: 12345 } }, upgrades: [rune], infusions: [], runeCount: 2 })
const tip = () => document.body.querySelector('[role="tooltip"]')

describe('EquipmentTile tooltip', () => {
  afterEach(() => { vi.useRealTimers(); document.body.innerHTML = '' })

  it('shows upgrade bonuses, inactive rune bonuses, deduplicated effects and coin formatting', async () => {
    const w = mount(EquipmentTile, { props: { slotName: 'Helm', entry: entry('Armor') }, attachTo: document.body })
    await w.trigger('mouseenter')
    const text = tip()!.textContent!
    expect(text).toContain('+5 Ferocity')
    expect(text.match(/\+5 Ferocity/g)).toHaveLength(1)
    expect(text).toContain('Extra effect')
    expect(text).toContain('Rune text')
    expect(text).toContain('Vendor price: 1g 23s 45c')
    const bonuses = [...tip()!.querySelectorAll('.upgrade__inactive')].map(e => e.textContent)
    expect(bonuses).toEqual(['(3) three'])
    w.unmount()
  })

  it('suppresses upgrade descriptions on gathering tools', async () => {
    const w = mount(EquipmentTile, { props: { slotName: 'Helm', entry: entry('Gathering') }, attachTo: document.body })
    await w.trigger('mouseenter')
    expect(tip()!.textContent).not.toContain('Rune text')
    w.unmount()
  })

  it('closes after a delay, cancels on return, and closes on Escape', async () => {
    vi.useFakeTimers()
    const w = mount(EquipmentTile, { props: { slotName: 'Helm', entry: entry('Armor') }, attachTo: document.body })
    await w.trigger('focus')
    await w.trigger('blur')
    await w.trigger('focus')
    vi.advanceTimersByTime(200)
    await w.vm.$nextTick()
    expect(tip()).not.toBeNull()
    await w.trigger('blur')
    vi.advanceTimersByTime(149)
    await w.vm.$nextTick()
    expect(tip()).not.toBeNull()
    vi.advanceTimersByTime(1)
    await w.vm.$nextTick()
    expect(tip()).toBeNull()
    await w.trigger('focus')
    await w.trigger('keydown', { key: 'Escape' })
    expect(tip()).toBeNull()
    w.unmount()
  })
})
