import { describe, expect, it } from 'vitest'
import { formatStackQuantity } from '../formatStackQuantity'

describe('formatStackQuantity', () => {
  it('keeps quantities below one stack as individual items', () => {
    expect(formatStackQuantity(100)).toBe('100')
  })

  it('formats exactly one stack', () => {
    expect(formatStackQuantity(250)).toBe('1 × 250')
  })

  it('formats multiple complete stacks', () => {
    expect(formatStackQuantity(1000)).toBe('4 × 250')
  })

  it('formats complete stacks followed by the remainder', () => {
    expect(formatStackQuantity(265)).toBe('1 × 250 + 15')
    expect(formatStackQuantity(765)).toBe('3 × 250 + 15')
  })
})
