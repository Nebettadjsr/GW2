import { describe, expect, it } from 'vitest'
import { MATERIAL_PRESETS } from '../materialPresets'
import fixture from './fixtures/presetItems.json'

const fx = fixture as unknown as Record<string, Record<string, string>>

describe('material preset definitions', () => {
  it('hasSevenPresets_withExactLabelForExtra', () => {
    expect(MATERIAL_PRESETS.map((p) => p.id)).toEqual(['t1', 't2', 't3', 't4', 't5', 't6', 'extra'])
    expect(MATERIAL_PRESETS[6]!.label).toBe('Clovers, coins, ecto & obsidian')
  })
  it.each(['t1', 't2', 't3', 't4', 't5', 't6', 'extra'])('%s matches the independent API fixture exactly', (id) => {
    const preset = MATERIAL_PRESETS.find((p) => p.id === id)!
    expect([...preset.itemIds].sort((a, b) => a - b)).toEqual(Object.keys(fx[id]!).map(Number).sort((a, b) => a - b))
    expect(new Set(preset.itemIds).size).toBe(preset.itemIds.length)
    expect(preset.itemIds).toHaveLength(id === 'extra' ? 4 : 8)
  })
  it('noPresetIncludesChargedSliverOrFragment_orOverlaps', () => {
    const all = MATERIAL_PRESETS.flatMap((p) => [...p.itemIds])
    expect(all).not.toContain(24301)
    expect(all).not.toContain(24302)
    expect(new Set(all).size).toBe(52)
  })
})
