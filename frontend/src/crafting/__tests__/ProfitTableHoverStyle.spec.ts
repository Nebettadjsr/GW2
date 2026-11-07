import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

// jsdom does not apply scoped component CSS, so the hover/focus contract is checked on the style
// sources; the rendered result is covered by the browser verification recorded on the PR.
const table = readFileSync(resolve(__dirname, '../CraftingProfitTable.vue'), 'utf8')
const shell = readFileSync(resolve(__dirname, '../../styles.css'), 'utf8')

function rule(source: string, selector: string): string {
  const start = source.indexOf(`${selector} {`)
  expect(start, `${selector} rule`).toBeGreaterThanOrEqual(0)
  return source.slice(start, source.indexOf('}', start))
}

describe('Crafting Profit row hover and focus styling', () => {
  it('highlightsTheWholeRowOnPointerHover', () => {
    expect(rule(table, '.profit-table tbody tr:hover')).toContain('background: var(--color-raised)')
  })

  it('givesTheRecipeNameButtonNoHoverBorder', () => {
    expect(rule(table, '.recipe-select:hover:not(:disabled)')).toContain('border-color: transparent')
  })

  it('keepsTheSharedVisibleFocusOutlineAndDoesNotRemoveItForTheRecipeButton', () => {
    expect(rule(shell, ':focus-visible')).toContain('outline: 2px solid')
    expect(table).not.toMatch(/\.recipe-select[^{]*\{[^}]*outline:\s*(none|0)/)
  })
})
