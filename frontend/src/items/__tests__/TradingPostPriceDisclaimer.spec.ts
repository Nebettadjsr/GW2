import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TradingPostPriceDisclaimer from '../TradingPostPriceDisclaimer.vue'

const dialogPrototype = HTMLDialogElement.prototype
const originalShowModal = Object.getOwnPropertyDescriptor(dialogPrototype, 'showModal')
const originalClose = Object.getOwnPropertyDescriptor(dialogPrototype, 'close')

beforeEach(() => {
  // jsdom does not implement the native dialog methods. Preserve their open/close events here;
  // the browser supplies modal focus containment and Escape behavior.
  Object.defineProperty(dialogPrototype, 'showModal', {
    configurable: true,
    value: vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '')
      this.querySelector('button')?.focus()
    })
  })
  Object.defineProperty(dialogPrototype, 'close', {
    configurable: true,
    value: vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute('open')
      this.dispatchEvent(new Event('close'))
    })
  })
})

afterEach(() => {
  if (originalShowModal) Object.defineProperty(dialogPrototype, 'showModal', originalShowModal)
  else Reflect.deleteProperty(dialogPrototype, 'showModal')
  if (originalClose) Object.defineProperty(dialogPrototype, 'close', originalClose)
  else Reflect.deleteProperty(dialogPrototype, 'close')
})

describe('TradingPostPriceDisclaimer', () => {
  it('opens an accessible native dialog with the unit-price and quantity warning', async () => {
    const wrapper = mount(TradingPostPriceDisclaimer, { attachTo: document.body })
    const trigger = wrapper.find('[data-test="tp-price-disclaimer-trigger"]')
    const dialog = wrapper.find('[data-test="tp-price-disclaimer-dialog"]')

    expect(trigger.attributes('aria-haspopup')).toBe('dialog')
    expect(trigger.attributes('aria-label')).toBe('Trading Post price limits')
    expect(trigger.attributes('aria-controls')).toBe(dialog.attributes('id'))
    expect((dialog.element as HTMLDialogElement).open).toBe(false)

    await trigger.trigger('click')

    expect((dialog.element as HTMLDialogElement).open).toBe(true)
    expect(document.getElementById(dialog.attributes('aria-labelledby')!)?.textContent)
      .toContain('Trading Post price and quantity limits')
    expect(document.getElementById(dialog.attributes('aria-describedby')!)?.textContent)
      .toContain('not guaranteed prices for the full quantity')
    expect(dialog.text()).toContain('available quantities in-game')
    wrapper.unmount()
  })

  it('closes with its button and restores focus to the trigger', async () => {
    const wrapper = mount(TradingPostPriceDisclaimer, { attachTo: document.body })
    const trigger = wrapper.find('[data-test="tp-price-disclaimer-trigger"]')
    const dialog = wrapper.find('[data-test="tp-price-disclaimer-dialog"]')
    await trigger.trigger('click')
    expect(document.activeElement).toBe(wrapper.find('[data-test="tp-price-disclaimer-close"]').element)

    await wrapper.find('[data-test="tp-price-disclaimer-close"]').trigger('click')

    expect((dialog.element as HTMLDialogElement).open).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })

  it('restores focus when the browser closes the native dialog', async () => {
    const wrapper = mount(TradingPostPriceDisclaimer, { attachTo: document.body })
    const trigger = wrapper.find('[data-test="tp-price-disclaimer-trigger"]')
    await trigger.trigger('click')

    ;(wrapper.find('dialog').element as HTMLDialogElement).close()

    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })
})
