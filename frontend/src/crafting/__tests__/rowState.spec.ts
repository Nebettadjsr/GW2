import { describe, expect, it } from 'vitest'
import { describeRowState } from '../rowState'
import {
  lessProfitableRow,
  movedReasonRows,
  noResultRow,
  noneCraftableRow,
  priceUnavailableRow,
  profitableRow,
  unknownStateRow
} from './fixtures'

/** `describeRowState(row).label` exists only so the Discovery search can match state words. */
describe('describeRowState', () => {
  it('wordsAKnownBlockedReasonWithoutItsCode', () => {
    expect(describeRowState(priceUnavailableRow).label).toBe('Price missing')
    expect(describeRowState({ ...priceUnavailableRow, blockedReason: 'WALLET_UNAVAILABLE' }).label).toBe('Wallet unknown')
    expect(describeRowState({ ...priceUnavailableRow, blockedReason: 'INSUFFICIENT_CURRENCY' }).label).toBe('Not enough currency')
    expect(describeRowState({ ...priceUnavailableRow, blockedReason: 'WORK_LIMIT' }).label).toBe('Incomplete')
    for (const row of movedReasonRows) expect(describeRowState(row).label).not.toBe('')
  })

  it('showsAnUnrecognizedCodeAsItself', () => {
    expect(describeRowState(unknownStateRow).label).toBe('SOME_STATE_ADDED_LATER')
  })

  it('keepsNotBlockedNoResultNoneCraftableAndUnreportedApart', () => {
    expect(describeRowState(profitableRow).label).toBe('Not blocked')
    expect(describeRowState(lessProfitableRow).label).toBe('Not blocked')
    expect(describeRowState(noResultRow).label).toBe('No result')
    expect(describeRowState(noneCraftableRow).label).toBe('None craftable')
    expect(describeRowState({ ...profitableRow, blockedReason: null }).label).toBe('State not reported')
  })
})
