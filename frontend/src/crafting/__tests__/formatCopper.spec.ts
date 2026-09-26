import { describe, expect, it } from 'vitest'
import { formatCopper, formatCount, formatSignedCopper, moneyTone, NO_VALUE } from '../formatCopper'

describe('formatCopper', () => {
  it('splitsCopperIntoGoldSilverCopper', () => {
    expect(formatCopper(123_456)).toBe('12g 34s 56c')
    expect(formatCopper(4_507)).toBe('45s 7c')
    expect(formatCopper(37)).toBe('37c')
    expect(formatCopper(0)).toBe('0c')
  })

  it('keepsNegativeAmountsVisiblyNegative', () => {
    expect(formatCopper(-4_507)).toBe('-45s 7c')
  })

  it('showsMissingValueMarkerInsteadOfZero', () => {
    expect(formatCopper(null)).toBe(NO_VALUE)
    expect(formatCopper(undefined)).toBe(NO_VALUE)
    expect(formatCopper(null)).not.toContain('0')
  })
})

describe('formatCount', () => {
  it('showsMissingValueMarkerInsteadOfZero', () => {
    expect(formatCount(0)).toBe('0')
    expect(formatCount(null)).toBe(NO_VALUE)
  })
})

describe('formatSignedCopper', () => {
  it('writesTheSignSoColorIsNeverTheOnlyDistinction', () => {
    expect(formatSignedCopper(4_507)).toBe('+45s 7c')
    expect(formatSignedCopper(-4_507)).toBe('-45s 7c')
  })

  it('keepsASuppliedZeroApartFromAMissingValue', () => {
    expect(formatSignedCopper(0)).toBe('0c')
    expect(formatSignedCopper(null)).toBe(NO_VALUE)
    expect(formatSignedCopper(undefined)).toBe(NO_VALUE)
  })
})

describe('moneyTone', () => {
  it('tonesOnlyAnActualGainOrLoss', () => {
    expect(moneyTone(1)).toBe('gain')
    expect(moneyTone(-1)).toBe('loss')
    expect(moneyTone(0)).toBe('none')
    expect(moneyTone(null)).toBe('none')
  })
})
