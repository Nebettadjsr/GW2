import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { CraftingRow } from '@/api/types'
import CraftingProfitTable from '../CraftingProfitTable.vue'
import SelectedResultDetail from '../SelectedResultDetail.vue'
import { FORGE_PREFERENCE_KEY_PREFIX, createForgePreference } from '../forgePreference'
import { isForgeRow, sourceLabel, yieldSummary } from '../recipeLabel'
import { DEFAULT_SETTINGS, profitableRow } from './fixtures'

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial))
  return {
    data,
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => { data.set(key, value) },
    removeItem: (key: string) => { data.delete(key) }
  }
}

describe('forge preference per verified account', () => {
  it('defaultsOffAndRestoresTheSameAccountAfterReloadWithoutLeakingToAnother', () => {
    const storage = memoryStorage()
    let account: string | null = 'guid-a'
    const first = createForgePreference({ storage, accountId: () => account })
    expect(first.current()).toBe(false)
    expect(first.set(true)).toBe(true)
    expect(first.current()).toBe(true)

    account = 'guid-b'
    expect(first.current()).toBe(false)
    account = null
    expect(first.current()).toBe(false)
    expect(first.set(true)).toBe(false)

    // A new page (new instance, same storage) restores account A only.
    const reloaded = createForgePreference({ storage, accountId: () => 'guid-a' })
    expect(reloaded.current()).toBe(true)
    expect(createForgePreference({ storage, accountId: () => 'guid-b' }).current()).toBe(false)
    expect([...storage.data.keys()]).toEqual([FORGE_PREFERENCE_KEY_PREFIX + 'guid-a'])
  })

  it('treatsInvalidOrUnavailableStorageAsOffAndKeepsTheCommittedValueInMemory', () => {
    for (const raw of ['not json', '[]', '{"version":2,"allowMysticForge":true}', '{"version":1,"allowMysticForge":"yes"}']) {
      const storage = memoryStorage({ [FORGE_PREFERENCE_KEY_PREFIX + 'guid-a']: raw })
      expect(createForgePreference({ storage, accountId: () => 'guid-a' }).current()).toBe(false)
    }
    const failing = { getItem: () => { throw new Error('blocked') }, setItem: () => { throw new Error('quota') } }
    const pref = createForgePreference({ storage: failing, accountId: () => 'guid-a' })
    expect(pref.current()).toBe(false)
    pref.set(true)
    expect(pref.current()).toBe(true)
    expect(createForgePreference({ storage: null, accountId: () => 'guid-a' }).current()).toBe(false)
  })
})

const forgeRow: CraftingRow = {
  ...profitableRow,
  recipeId: -3105801,
  disciplines: '',
  outputName: 'Abyssal Scepter',
  expectedYield: {
    source: 'MYSTIC_FORGE', estimated: true, expectedOutputPerCraft: '86.34', minOutput: 50, maxOutput: 250,
    expectedSellableUnitsTotal: '172.68', guaranteedSellableUnitsTotal: 100, reusedSeedUnits: 0
  }
}

describe('forge row presentation (backend values only)', () => {
  it('showsMysticForgeInsteadOfBlankDisciplineAndNegativeRecipeIdWithATinyEstimateHint', () => {
    const wrapper = mount(CraftingProfitTable, {
      props: { rows: [forgeRow, profitableRow], selectedRecipeId: null, sortKey: 'totalProfitCopper', sortDirection: 'desc' } as never
    })
    const rows = wrapper.findAll('[data-test="profit-row"]')
    expect(rows[0]?.find('[data-test="forge-source"]').text()).toBe('Mystic Forge')
    expect(rows[0]?.text()).not.toContain('-3105801')
    expect(rows[0]?.find('[data-test="estimated-hint"]').exists()).toBe(true)
    expect(rows[1]?.find('[data-test="forge-source"]').exists()).toBe(false)
    expect(rows[1]?.find('[data-test="estimated-hint"]').exists()).toBe(false)
    expect(rows[1]?.text()).toContain(`recipe ${profitableRow.recipeId}`)
  })

  it('distinguishesExpectedFromGuaranteedQuantityInTheDetail', () => {
    const wrapper = mount(SelectedResultDetail, {
      props: { row: forgeRow, hiddenReason: null, settings: DEFAULT_SETTINGS, placeholder: 'x',
        resolutionPhase: 'idle', resolutionDetail: null, resolutionFailure: null, resolutionRecipeId: null } as never
    })
    expect(wrapper.find('[data-test="detail-identity"]').text()).toBe('Mystic Forge')
    const yieldText = wrapper.find('[data-test="detail-yield"]').text()
    expect(yieldText).toContain('expected 86.34 per craft (min 50, max 250)')
    expect(yieldText).toContain('expected 172.68 total, guaranteed 100')
    expect(yieldText).toContain('Only the guaranteed minimum counts as stock')
  })

  it('leavesOrdinaryRowsUnchanged', () => {
    expect(isForgeRow(profitableRow)).toBe(false)
    expect(sourceLabel(profitableRow)).toBe(profitableRow.disciplines)
    expect(yieldSummary(profitableRow)).toBeNull()
  })
})

describe('forge preference account-data deletion', () => {
  it('forgetAccountRemovesOnlyThatAccountsStoredKeyAndShadowWhileOthersSurvive', () => {
    const storage = memoryStorage()
    let account = 'guid-a'
    const pref = createForgePreference({ storage, accountId: () => account })
    pref.set(true)
    account = 'guid-b'
    pref.set(true)

    pref.forgetAccount('guid-a')

    expect([...storage.data.keys()]).toEqual([FORGE_PREFERENCE_KEY_PREFIX + 'guid-b'])
    expect(pref.current()).toBe(true)
    account = 'guid-a'
    expect(pref.current()).toBe(false)
    // A reload cannot resurrect it either.
    expect(createForgePreference({ storage, accountId: () => 'guid-a' }).current()).toBe(false)
  })

  it('forgetAccountDropsTheShadowEvenWhenStorageCannotRemove', () => {
    const storage = memoryStorage()
    storage.removeItem = () => { throw new Error('blocked') }
    const pref = createForgePreference({ storage, accountId: () => 'guid-a' })
    pref.set(true)
    expect(() => pref.forgetAccount('guid-a')).not.toThrow()
  })
})
