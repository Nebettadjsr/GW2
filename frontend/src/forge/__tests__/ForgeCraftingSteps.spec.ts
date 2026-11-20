import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ForgeCraftingSteps from '../ForgeCraftingSteps.vue'
import type { ForgeNode } from '@/api/types'

function node(itemId: number, recipeId: number, step: number | null, children: ForgeNode[] = []): ForgeNode {
  return {
    item: { itemId, name: `Item ${itemId}`, iconUrl: null, wikiUrl: null },
    needed: 1, fromOwned: 0, fromSurplus: 0, toCraft: 1, toAcquire: 0, recipeId, recipeSource: 'ORDINARY', crafts: 1,
    minRating: 0, disciplines: null, cutoff: null, children, recipeKnown: true, performable: true,
    assignedCharacter: null, hint: null, produced: 1, newSurplus: 0, step,
  }
}

describe('ForgeCraftingSteps', () => {
  it('numbers steps in the backend dependency order, not tree position', () => {
    // The consuming intermediate is the first tree child, but the backend orders logs -> Notes -> consumer -> root.
    const tree = node(9000003, -21, 4, [
      node(9000002, 98765, 3),
      node(19722, -32, 1),
      node(104934, 15131, 2),
    ])
    const w = mount(ForgeCraftingSteps, { props: { tree } })
    const text = w.findAll('[data-test="forge-crafting-step"]').map((li) => li.text())
    expect(text).toHaveLength(4)
    expect(text[0]).toContain('Item 19722')
    expect(text[1]).toContain('Item 104934')
    expect(text[2]).toContain('Item 9000002')
    expect(text[3]).toContain('Item 9000003')
  })
})
