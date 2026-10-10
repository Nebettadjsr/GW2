import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import EctoSalvageScreen from '../EctoSalvageScreen.vue'
import { accountLuck, itemMetadata, itemPrices } from './currentEctoFixtures'
import { withoutDigitGrouping } from './renderedNumbers'

let wrapper: ReturnType<typeof mount> | null = null
let failMetadata = false

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' }
  })
}

beforeEach(() => {
  failMetadata = false
  vi.stubGlobal('fetch', vi.fn((input: string) => {
    const url = new URL(input, 'http://test.local')
    if (url.pathname === '/api/items/metadata') {
      return failMetadata ? Promise.reject(new Error('metadata unavailable')) : Promise.resolve(jsonResponse(itemMetadata))
    }
    if (url.pathname === '/api/items/prices') return Promise.resolve(jsonResponse(itemPrices))
    if (url.pathname === '/api/account/luck') return Promise.resolve(jsonResponse(accountLuck))
    throw new Error(`Unexpected request: ${input}`)
  }))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.unstubAllGlobals()
})

describe('Ecto semantic content hooks', () => {
  it('marks calculated results and account Luck only after their required data loads', async () => {
    wrapper = mount(EctoSalvageScreen)
    expect(wrapper.find('[data-test="ecto-screen"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="ecto-result"]').exists()).toBe(false)

    await flushPromises()

    expect(wrapper.find('[data-test="ecto-result"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="ecto-calculation"]').text()).toContain('185 Dust')
    // 14 134 is the supplied `accountLuck.consumedLuck`, named by its digits so the expectation is
    // that value rather than this machine's thousands separator.
    expect(withoutDigitGrouping(wrapper.find('[data-test="ecto-account-luck"]').text()))
      .toContain(String(14_134))
  })

  it('does not expose a result-ready hook when required item metadata failed', async () => {
    failMetadata = true
    wrapper = mount(EctoSalvageScreen)
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('Item metadata could not be read.')
    expect(wrapper.find('[data-test="ecto-result"]').exists()).toBe(false)
  })
})
