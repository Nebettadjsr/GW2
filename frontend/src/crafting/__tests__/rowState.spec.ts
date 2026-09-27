import { describe, expect, it } from 'vitest'
import { describeRowState, rowDiagnostic } from '../rowState'
import {
  cycleDetectedRow,
  lessProfitableRow,
  movedReasonRows,
  noResultRow,
  noneCraftableRow,
  priceUnavailableRow,
  profitableRow,
  unknownStateRow
} from './fixtures'

describe('describeRowState', () => {
  it('wordsAKnownBlockedReasonWithoutShowingItsCode', () => {
    const state = describeRowState(priceUnavailableRow)

    expect(state.label).toBe('Price missing')
    expect(state.label).not.toContain('PRICE_UNAVAILABLE')
    expect(state.explanation).toContain('a required price is not available')
    expect(state.tone).toBe('caution')
    // The code itself stays available for the detail region's secondary disclosure.
    expect(state.code).toBe('PRICE_UNAVAILABLE')
  })

  it('saysFurtherCraftingWhenTheBackendStillCountedCrafts', () => {
    const blockedAfterThreeCrafts = { ...priceUnavailableRow, craftableCount: 3 }

    expect(describeRowState(blockedAfterThreeCrafts).explanation).toContain('Further crafting is blocked')
    expect(describeRowState(blockedAfterThreeCrafts).explanation).toContain('3 crafts already counted stay valid')
    expect(describeRowState(priceUnavailableRow).explanation).toMatch(/^Crafting is blocked/)
  })

  it('showsAnUnrecognizedStateAsItselfAndNeverAsSuccess', () => {
    const state = describeRowState(unknownStateRow)

    expect(state.label).toBe('SOME_STATE_ADDED_LATER')
    expect(state.tone).not.toBe('success')
    expect(state.explanation).toContain('does not recognize')
  })

  it('keepsNotBlockedNoResultAndNoneCraftableApart', () => {
    expect(describeRowState(profitableRow)).toMatchObject({ label: 'Not blocked', tone: 'success' })
    expect(describeRowState(lessProfitableRow).tone).toBe('success')
    expect(describeRowState(noResultRow)).toMatchObject({ label: 'No result', tone: 'unknown', code: null })
    expect(describeRowState(noneCraftableRow)).toMatchObject({ label: 'None craftable', tone: 'idle' })
  })

  it('marksTheLabelsThatOnlyRepeatTheirOwnExplanation', () => {
    // DOMAIN_SPEC 2.1.1's three examples, plus every other reason this client has wording for: the
    // sentence states the cause, so the short label adds nothing to it.
    for (const row of [...movedReasonRows, priceUnavailableRow, cycleDetectedRow, profitableRow, noneCraftableRow]) {
      const state = describeRowState(row)
      expect(state.labelAddsMeaning, `${state.label} must not be repeated as a label`).toBe(false)
      // Removing the label is a display decision; the label itself is still produced for the search
      // index and for Discovery's own detail.
      expect(state.label).not.toBe('')
    }

    // Where the sentence is all there is, the label stays: none of these may read as success.
    for (const row of [noResultRow, { ...profitableRow, blockedReason: null }, unknownStateRow]) {
      const state = describeRowState(row)
      expect(state.labelAddsMeaning, `${state.label} must keep its label`).toBe(true)
      expect(state.tone).toBe('unknown')
    }
  })

  it('doesNotTreatAnUnreportedStateAsNotBlocked', () => {
    const state = describeRowState({ ...profitableRow, blockedReason: null })

    expect(state.label).toBe('State not reported')
    expect(state.tone).toBe('unknown')
  })
})

describe('rowDiagnostic', () => {
  it('givesEveryMovedReasonNoRowLabelAtAll', () => {
    for (const row of movedReasonRows) {
      expect(rowDiagnostic(row), `${row.blockedReason} must not be labelled beside the row`).toBeNull()
      // The reason itself is untouched, and the detail still states it in full.
      expect(describeRowState(row).code).toBe(row.blockedReason)
      expect(describeRowState(row).explanation).not.toBe('')
    }
  })

  it('leavesAnUnblockedRowAndAZeroCountRowUnmarked', () => {
    expect(rowDiagnostic(profitableRow)).toBeNull()
    expect(rowDiagnostic(lessProfitableRow)).toBeNull()
    expect(rowDiagnostic(noneCraftableRow)).toBeNull()
  })

  it('keepsTheTemporaryCycleDiagnosticOnTheRow', () => {
    expect(rowDiagnostic(cycleDetectedRow)).toEqual({ label: 'Recipe loop', tone: 'caution' })
  })

  it('keepsAMissingPriceAndAnAbsentResultDistinguishableWithoutAStateColumn', () => {
    expect(rowDiagnostic(priceUnavailableRow)).toEqual({ label: 'Price missing', tone: 'caution' })
    expect(rowDiagnostic(noResultRow)).toEqual({ label: 'No result', tone: 'unknown' })
    expect(rowDiagnostic({ ...profitableRow, blockedReason: null })).toEqual({
      label: 'State not reported',
      tone: 'unknown'
    })
  })

  it('marksAnUnrecognizedCodeWithoutPrintingItAndWithoutReadingItAsSuccess', () => {
    const diagnostic = rowDiagnostic(unknownStateRow)

    expect(diagnostic).not.toBeNull()
    expect(diagnostic?.tone).not.toBe('success')
    // Raw codes are secondary technical information; the detail's disclosure is where they belong.
    expect(diagnostic?.label).not.toContain('SOME_STATE_ADDED_LATER')
    expect(describeRowState(unknownStateRow).code).toBe('SOME_STATE_ADDED_LATER')
  })
})
