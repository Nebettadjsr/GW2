import { describe, expect, it } from 'vitest'
import { withoutDigitGrouping } from './renderedNumbers'

/**
 * The Ectoplasm expectations read rendered numbers through `withoutDigitGrouping`, so this is where
 * its locale independence is established: the grouping of a named locale is deterministic whatever
 * the runner's own default is, which the Ectoplasm tests themselves cannot state.
 */
describe('reading a rendered number without its locale grouping', () => {
  const GROUPING_LOCALES = ['en-US', 'de-DE', 'fr-FR', 'es-ES', 'en-GB']

  it('reduces one value to the same digits in every locale that renders it', () => {
    const rendered = GROUPING_LOCALES.map((locale) => (14_134).toLocaleString(locale))

    expect(rendered.map(withoutDigitGrouping)).toEqual(rendered.map(() => '14134'))
    // Not a tautology about identical strings: at least two of those renderings differ, which is the
    // reason an expectation may not be written with the default locale's own separator.
    expect(new Set(rendered).size).toBeGreaterThan(1)
  })

  it('reduces a grouped value inside surrounding text, and leaves a decimal fraction alone', () => {
    for (const locale of GROUPING_LOCALES) {
      const luck = (10_457).toLocaleString(locale)
      const perEcto = (104.57).toLocaleString(locale)

      expect(withoutDigitGrouping(`${luck} Luck`)).toBe('10457 Luck')
      expect(withoutDigitGrouping(perEcto)).toBe(perEcto)
    }
  })

  it('does not turn one number into another', () => {
    expect(withoutDigitGrouping((14_135).toLocaleString('de-DE'))).not.toContain('14134')
    expect(withoutDigitGrouping('185 Dust')).toBe('185 Dust')
  })
})
