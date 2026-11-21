import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ForgeCraftingSteps from '../ForgeCraftingSteps.vue'
import type { ForgeCraftingStep } from '@/api/types'

const item = (itemId: number) => ({ itemId, name: `Item ${itemId}`, iconUrl: null, wikiUrl: null })
function step(itemId: number, recipeId: number, o: Partial<ForgeCraftingStep> = {}): ForgeCraftingStep {
  return {
    item: item(itemId), recipeId, recipeSource: 'ORDINARY', crafts: 1, produced: 1, toAcquire: 0, newSurplus: 0,
    disciplines: null, hint: null, recipeKnown: true, performable: true, assignedCharacter: null, cutoff: null,
    root: false, ingredients: [], ...o,
  }
}

describe('ForgeCraftingSteps', () => {
  it('renders the backend instructions in the order the backend sends, numbered 1..n, root last', () => {
    // The backend (ForgeCraftingInstructions) orders logs -> Notes -> consumer -> root; the UI must not reorder.
    const steps = [
      step(19722, -32), step(104934, 15131), step(9000002, 98765), step(9000003, -21, { root: true }),
    ]
    const w = mount(ForgeCraftingSteps, { props: { steps } })
    const text = w.findAll('[data-test="forge-crafting-step"]').map((li) => li.text())
    expect(text).toHaveLength(4)
    expect(text[0]).toContain('Item 19722')
    expect(text[1]).toContain('Item 104934')
    expect(text[2]).toContain('Item 9000002')
    expect(text[3]).toContain('Item 9000003')
  })

  it('shows an aggregated backend instruction once, with its summed craft count and inputs, not three tree steps', () => {
    // Mini Aqua Jackal Pup: Red 8+4+2 crafts were merged by the backend into ONE Craft 14 with 42 Yellow + 28 Dye.
    const steps = [step(3003, -3, { crafts: 14, produced: 14, ingredients: [
      { item: item(1001), quantity: 42 }, { item: item(1002), quantity: 28 }] }), step(9000, -4, { root: true })]
    const w = mount(ForgeCraftingSteps, { props: { steps } })
    const lines = w.findAll('[data-test="forge-crafting-step"]')
    expect(lines).toHaveLength(2)
    expect(lines[0]!.text()).toContain('Item 3003')
    expect(lines[0]!.text()).toContain('14')
    expect(lines[0]!.text()).toContain('42')
    expect(lines[0]!.text()).toContain('28')
  })

  it('renders nothing for a pending (empty) instruction list', () => {
    const w = mount(ForgeCraftingSteps, { props: { steps: [] } })
    expect(w.find('[data-test="forge-crafting-steps"]').exists()).toBe(false)
  })
})
