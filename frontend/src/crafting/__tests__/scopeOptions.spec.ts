import { describe, expect, it } from 'vitest'
import type { SelectorOptions } from '@/api/types'
import { buildScopeOptions, defaultScopeOptionId } from '../scopeOptions'
import { selectorOptions } from './fixtures'

describe('buildScopeOptions', () => {
  it('offersAllThenBackendDisciplinesThenCharacterEntries', () => {
    const options = buildScopeOptions(selectorOptions)

    expect(options.map((option) => option.label)).toEqual([
      'All',
      'Chef',
      'Huntsman',
      'Armorsmith',
      'Armorsmith lvl 500 — Nbt Anch',
      'Chef lvl 400 — Sat Anat'
    ])
  })

  it('mapsEachEntryOntoAScopeTheProfitContractAccepts', () => {
    const options = buildScopeOptions(selectorOptions)

    expect(options.at(0)?.request).toEqual({ kind: 'ALL' })
    expect(options.at(1)?.request).toEqual({ kind: 'DISCIPLINE', discipline: 'Chef' })
    expect(options.at(4)?.request).toEqual({
      kind: 'CHARACTER_DISCIPLINE',
      discipline: 'Armorsmith',
      characterName: 'Nbt Anch',
      rating: 500
    })
  })

  it('inventsNoCharacterWhenNothingIsSynced', () => {
    const emptyDatabase: SelectorOptions = {
      defaultScopeKind: 'ALL',
      disciplines: ['Chef'],
      characterOptionCount: 0,
      characterOptions: []
    }

    const options = buildScopeOptions(emptyDatabase)

    expect(options.map((option) => option.label)).toEqual(['All', 'Chef'])
  })
})

describe('defaultScopeOptionId', () => {
  it('preselectsTheKindTheBackendReported', () => {
    const options = buildScopeOptions(selectorOptions)

    expect(defaultScopeOptionId(selectorOptions, options)).toBe('ALL')
  })
})
