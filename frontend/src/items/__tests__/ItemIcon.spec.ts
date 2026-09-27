import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ItemIcon from '../ItemIcon.vue'

/**
 * The shared item image component (`TARGET_ARCHITECTURE.md` 12.1, AR-005).
 *
 * jsdom neither fetches nor decodes an image, so these checks pin what the component *renders and
 * decides*: the address it puts in `src`, the attributes that govern how the browser fetches it, and
 * the single fallback every unusable case ends in. Whether a real browser then loads that URL from
 * this application, caches it and reuses it is `npm run smoke:icons` and the live integration run —
 * a unit test cannot establish it and none here claims to.
 */

/** A real backend-shaped URL: application-relative, item id, source key, extension. */
const ICON_URL =
  '/api/items/19697/icon/50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472.png'
const OTHER_ICON_URL =
  '/api/items/12134/icon/9247ad5f6cd4702a0c12724f8a90235191e8831a61be53d54a138b86e3984f8d.jpg'

describe('ItemIcon', () => {
  it('rendersTheSuppliedUrlVerbatimWithTheDeliveryAttributesSection121Requires', () => {
    const wrapper = mount(ItemIcon, { props: { iconUrl: ICON_URL, itemId: 19697 } })

    const image = wrapper.get('[data-test="item-icon-image"]')
    // Verbatim: no query, no timestamp, no version — the URL has to stay stable for the browser's
    // own cache and for the backend's revalidation to mean anything.
    expect(image.attributes('src')).toBe(ICON_URL)
    expect(image.attributes('referrerpolicy')).toBe('no-referrer')
    // Reserved dimensions, so the box is held before anything is decoded.
    expect(image.attributes('width')).toBe('20')
    expect(image.attributes('height')).toBe('20')
    expect(wrapper.attributes('style')).toContain('width: 20px')
    expect(wrapper.attributes('data-icon-state')).toBe('image')
    expect(wrapper.find('[data-test="item-icon-fallback"]').exists()).toBe(false)
  })

  it('announcesNothingOfItsOwnSoTheItemIsNotReadTwice', () => {
    const wrapper = mount(ItemIcon, { props: { iconUrl: ICON_URL, itemId: 19697 } })

    const image = wrapper.get('[data-test="item-icon-image"]')
    expect(image.attributes('alt')).toBe('')
    expect(image.attributes('aria-hidden')).toBe('true')
    // Nothing focusable: the row button and the detail keep the whole keyboard interaction.
    expect(wrapper.find('[tabindex]').exists()).toBe(false)
  })

  it('loadsPromptlyOrLazilyExactlyAsTheConsumerAsked', () => {
    const eager = mount(ItemIcon, {
      props: { iconUrl: ICON_URL, itemId: 19697, loading: 'eager' }
    })
    const lazy = mount(ItemIcon, { props: { iconUrl: ICON_URL, itemId: 19697, loading: 'lazy' } })

    expect(eager.get('[data-test="item-icon-image"]').attributes('loading')).toBe('eager')
    expect(lazy.get('[data-test="item-icon-image"]').attributes('loading')).toBe('lazy')
  })

  it('usesTheBundledFallbackWhenTheBackendSuppliedNoUrl', () => {
    // Null covers both cases the backend produces: no retained metadata, and metadata its canonical
    // source policy rejected. Neither reaches the browser as an address, so neither is requested.
    const wrapper = mount(ItemIcon, { props: { iconUrl: null, itemId: 19697 } })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('[data-test="item-icon-fallback"]').element.tagName.toLowerCase()).toBe('svg')
    expect(wrapper.attributes('data-icon-state')).toBe('no-url')
    // The reserved box is the same one an image would have occupied, so nothing shifts either way.
    expect(wrapper.attributes('style')).toContain('height: 20px')
  })

  it('fallsBackToTheSamePlaceholderOnALoadOrDecodeFailureWithoutRetrying', async () => {
    const wrapper = mount(ItemIcon, { props: { iconUrl: ICON_URL, itemId: 19697 } })

    await wrapper.get('[data-test="item-icon-image"]').trigger('error')

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('[data-test="item-icon-fallback"]').exists()).toBe(true)
    expect(wrapper.attributes('data-icon-state')).toBe('failed')

    // No retry of any kind: the failed image is gone rather than re-pointed at the same address, so
    // a backend answering 503 is asked once and not in a loop.
    await wrapper.vm.$nextTick()
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('keepsTheFailureOfOneImageOutOfTheNextOne', async () => {
    const wrapper = mount(ItemIcon, { props: { iconUrl: ICON_URL, itemId: 19697 } })
    await wrapper.get('[data-test="item-icon-image"]').trigger('error')
    expect(wrapper.attributes('data-icon-state')).toBe('failed')

    // A different item in the same row position is a different image, so it gets its own chance.
    await wrapper.setProps({ iconUrl: OTHER_ICON_URL, itemId: 12134 })

    expect(wrapper.attributes('data-icon-state')).toBe('image')
    expect(wrapper.get('[data-test="item-icon-image"]').attributes('src')).toBe(OTHER_ICON_URL)
  })

  it('retriesTheSameItemWhenItsRetainedSourceGaveItANewUrl', async () => {
    const wrapper = mount(ItemIcon, { props: { iconUrl: ICON_URL, itemId: 19697 } })
    await wrapper.get('[data-test="item-icon-image"]').trigger('error')

    // Same item, new source key: a changed retained source produces a new URL, which is a new image
    // and not the one that failed.
    await wrapper.setProps({ iconUrl: OTHER_ICON_URL })

    expect(wrapper.attributes('data-icon-state')).toBe('image')
  })

  it('dropsBackToTheFallbackWhenAnItemLosesItsMetadata', async () => {
    const wrapper = mount(ItemIcon, { props: { iconUrl: ICON_URL, itemId: 19697 } })

    await wrapper.setProps({ iconUrl: null })

    expect(wrapper.attributes('data-icon-state')).toBe('no-url')
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('reservesTheRequestedBoxForBothStates', () => {
    const image = mount(ItemIcon, { props: { iconUrl: ICON_URL, itemId: 1, size: 32 } })
    const fallback = mount(ItemIcon, { props: { iconUrl: null, itemId: 1, size: 32 } })

    expect(image.get('img').attributes('width')).toBe('32')
    expect(fallback.get('svg').attributes('width')).toBe('32')
    expect(image.attributes('style')).toBe(fallback.attributes('style'))
  })
})
