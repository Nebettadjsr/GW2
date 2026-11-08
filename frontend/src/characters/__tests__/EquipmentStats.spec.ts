import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import EquipmentStats from '../EquipmentStats.vue'
import type { EquipmentStats as Stats } from '@/api/types'

const values = { Power: 1111, Toughness: 1222, Armor: 1333, Vitality: 1444, Health: 15555, Precision: 1666,
  CriticalChance: 12.5, Ferocity: 1777, CriticalDamage: 150, ConditionDamage: 1888, HealingPower: 1999,
  Expertise: 321, ConditionDuration: 21.4, Concentration: 654, BoonDuration: 43.2, AgonyResistance: 77 }

describe('EquipmentStats duration rows', () => {
  it('orders rows as in game and pairs each duration attribute with its matching icon', () => {
    const set = { available: true, values }
    const w = mount(EquipmentStats, { props: { stats: { weaponSets: { A: set }, profiles: { PVE: { A: set } } } as unknown as Stats } })
    const rows = w.findAll('.equipment-stats__row').map((r) =>
      r.findAll('.equipment-stats__value').map((v) => ({
        text: v.text().replace(/\s+/g, ' '),
        icon: v.find('img').exists() ? v.find('img').attributes('src') ?? '' : null
      })))
    const labels = rows.map((r) => r.map((c) => c.text.split(':')[0]))
    expect(labels.slice(5)).toEqual([['Condition damage', 'Healing power'], ['Expertise', 'Condition duration'],
      ['Concentration', 'Boon duration'], ['Agony resistance']])
    expect(rows[6]!.map((c) => c.text)).toEqual(['Expertise: 321', 'Condition duration: 21.40%'])
    expect(rows[7]!.map((c) => c.text)).toEqual(['Concentration: 654', 'Boon duration: 43.20%'])
    const [exp, cd] = rows[6]!.map((c) => c.icon)
    const [conc, bd] = rows[7]!.map((c) => c.icon)
    expect(exp).toMatch(/ConditionDuration/)
    expect(cd).toBe(exp)
    expect(conc).toMatch(/BoonDuration/)
    expect(bd).toBe(conc)
    expect(exp).not.toBe(conc)
  })
})
