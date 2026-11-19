import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { NoteEstimate } from '@/api/types'
import ResearchNoteEstimatePopup from '../ResearchNoteEstimatePopup.vue'
import SelectedResultDetail from '../SelectedResultDetail.vue'
import CraftingProfitTable from '../CraftingProfitTable.vue'
import { profitableRow } from './fixtures'

const proto = HTMLDialogElement.prototype
const originalShowModal = Object.getOwnPropertyDescriptor(proto, 'showModal')
const originalClose = Object.getOwnPropertyDescriptor(proto, 'close')

beforeEach(() => {
  Object.defineProperty(proto, 'showModal', {
    configurable: true,
    value: vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '')
    })
  })
  Object.defineProperty(proto, 'close', {
    configurable: true,
    value: vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute('open')
      this.dispatchEvent(new Event('close'))
    })
  })
})

afterEach(() => {
  if (originalShowModal) Object.defineProperty(proto, 'showModal', originalShowModal)
  else Reflect.deleteProperty(proto, 'showModal')
  if (originalClose) Object.defineProperty(proto, 'close', originalClose)
  else Reflect.deleteProperty(proto, 'close')
})

const estimate: NoteEstimate = {
  perCraftNotes: 30,
  totalNotes: 90,
  perCraftCopper: 80,
  totalCopper: 240,
  basisRecipeId: 15120,
  variants: [
    {
      recipeId: 15120, outputName: 'Peer Reviewed Research', notesPerBatch: 150, batchCostCopper: 400,
      perNoteCopperCeil: 3, basis: true,
      ingredients: [{ itemId: 1001, name: 'Vial of Powerful Blood', count: 4, unitPriceCopper: 100, totalCopper: 400 }]
    },
    {
      recipeId: 15131, outputName: 'Peer Reviewed Research', notesPerBatch: 150, batchCostCopper: null,
      perNoteCopperCeil: null, basis: false,
      ingredients: [{ itemId: 2001, name: 'Pile of Crystalline Dust', count: 4, unitPriceCopper: null, totalCopper: null }]
    }
  ]
}

describe('ResearchNoteEstimatePopup', () => {
  it('waits for selected-detail recipe data before allowing the comparison to open', async () => {
    const wrapper = mount(ResearchNoteEstimatePopup, { props: { estimate: { ...estimate, variants: [] } } })
    const trigger = wrapper.find('[data-test="note-estimate-trigger"]')
    expect((trigger.element as HTMLButtonElement).disabled).toBe(true)
    await trigger.trigger('click')
    expect(proto.showModal).not.toHaveBeenCalled()
    await wrapper.setProps({ estimate })
    expect((trigger.element as HTMLButtonElement).disabled).toBe(false)
    await trigger.trigger('click')
    expect(wrapper.findAll('section[data-test^="note-estimate-variant-"]')).toHaveLength(2)
    expect((wrapper.find('[data-test="note-estimate-dialog"]').element as HTMLDialogElement).open).toBe(true)
    wrapper.unmount()
  })

  it('opens on click, shows both recipes with the cheaper one marked, and restores focus on close', async () => {
    const wrapper = mount(ResearchNoteEstimatePopup, { props: { estimate }, attachTo: document.body })
    const trigger = wrapper.find('[data-test="note-estimate-trigger"]')
    const dialog = wrapper.find('[data-test="note-estimate-dialog"]').element as HTMLDialogElement
    expect(trigger.attributes('aria-haspopup')).toBe('dialog')
    expect(dialog.open).toBe(false)

    await trigger.trigger('click')
    expect(dialog.open).toBe(true)
    expect(wrapper.find('[data-test="note-estimate-variant-15120"]').text()).toContain('4 ×')
    expect(wrapper.find('[data-test="note-estimate-variant-15120"]').text()).toContain('Cost per 150-Note batch')
    expect(wrapper.findAll('[data-test="note-estimate-basis"]')).toHaveLength(1)
    expect(wrapper.find('[data-test="note-estimate-variant-15120"] [data-test="note-estimate-basis"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="note-estimate-variant-15131"] [data-test="note-estimate-unavailable"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="note-estimate-variant-15131"] [data-test="note-estimate-missing"]').exists()).toBe(true)
    expect(wrapper.find('a[href="https://wiki.guildwars2.com/wiki/Peer%20Reviewed%20Research"]').exists()).toBe(true)

    ;(trigger.element as HTMLButtonElement).blur()
    await wrapper.find('[data-test="note-estimate-close"]').trigger('click')
    expect(dialog.open).toBe(false)
    expect(document.activeElement).toBe(trigger.element)

    await trigger.trigger('click')
    expect(dialog.open).toBe(true)
    // Escape closes a native modal dialog; the browser fires close, which restores focus.
    dialog.removeAttribute('open')
    dialog.dispatchEvent(new Event('close'))
    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })

  it('is a real button, so Enter and Space open it with the keyboard', () => {
    const wrapper = mount(ResearchNoteEstimatePopup, { props: { estimate } })
    const trigger = wrapper.find('[data-test="note-estimate-trigger"]')
    expect(trigger.element.tagName).toBe('BUTTON')
    expect(trigger.attributes('type')).toBe('button')
  })
})

describe('Research Note estimate in the overview', () => {
  const currencyCosts = {
    walletState: 'KNOWN',
    goldMarginIsConditional: false,
    economicsFromHypotheticalCraft: false,
    costs: [{ currencyId: 61, name: 'Research Note', perCraft: 30, total: 90 }],
    estimate
  }

  it('detail row shows quantity, deduction and only a compact hint; the comparison stays in the popup', () => {
    const wrapper = mount(SelectedResultDetail, {
      props: {
        row: { ...profitableRow, currencyCosts }, hiddenReason: null, settings: null, placeholder: '',
        resolutionPhase: 'idle', resolutionDetail: null, resolutionFailure: null, resolutionRecipeId: null
      }
    })
    const labels = wrapper.findAll('.calculation dt').map((dt) => dt.text())
    const own = labels.findIndex((t) => t.startsWith('Own materials'))
    expect(labels[own + 1]).toBe('Research Note')
    expect(labels[own + 2]).toMatch(/^Bought materials/)
    expect(wrapper.find('[data-test="detail-currency-cost"]').text()).toBe('90')
    expect(wrapper.find('[data-test="detail-note-estimate-cost"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="note-estimate-trigger"]').text()).toBe('Estimated')
    expect(wrapper.find('[data-test="detail-currency-exclusion"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('+ Research')
  })

  it('fresh-calculation detail: compact table row still shows both variants taken from the detail response row', async () => {
    const compact = { ...currencyCosts, estimate: { ...estimate, variants: [] } }
    const detailRow = { ...profitableRow, currencyCosts }
    const wrapper = mount(SelectedResultDetail, {
      props: {
        row: { ...profitableRow, currencyCosts: compact }, hiddenReason: null, settings: null, placeholder: '',
        resolutionPhase: 'ready',
        resolutionDetail: { consistency: 'FRESH_CALCULATION', row: detailRow } as never,
        resolutionFailure: null, resolutionRecipeId: profitableRow.recipeId
      }
    })
    await wrapper.find('[data-test="note-estimate-trigger"]').trigger('click')
    expect(wrapper.findAll('[data-test^="note-estimate-variant-"]').length).toBe(2)
  })

  it('table keeps normal colors with only a small marker', () => {
    const wrapper = mount(CraftingProfitTable, {
      props: {
        rows: [{ ...profitableRow, totalProfitCopper: 5000, currencyCosts: { ...currencyCosts, estimate: { ...estimate, variants: [] } } }] as never,
        sortKey: 'profitCopper', sortDirection: 'desc', selectedRecipeId: null
      }
    })
    expect(wrapper.find('[data-test="currency-estimated"]').text()).toBe('≈')
    expect(wrapper.find('[data-test="currency-conditional"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="total-profit"] .money--gain').exists()).toBe(true)
    expect(wrapper.find('[data-test="note-estimate-trigger"]').exists()).toBe(false)
  })
})
