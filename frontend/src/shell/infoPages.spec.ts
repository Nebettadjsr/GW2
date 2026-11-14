import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { INFO_DESTINATIONS, destinationForHash, isInfoDestination } from './destinations'
import { missingOperatorFacts, readOperatorFacts } from './operatorFacts'
import { installAccountSession, resetAccountSession } from '@/api/accountSession'

describe('info destinations', () => {
  it('resolves the three footer paths and distinguishes them from app areas', () => {
    expect(INFO_DESTINATIONS.map(d => d.path)).toEqual(['#/legal', '#/privacy', '#/storage'])
    expect(isInfoDestination(destinationForHash('#/privacy'))).toBe(true)
    expect(isInfoDestination(destinationForHash('#/bank'))).toBe(false)
    expect(destinationForHash('#/unknown').id).toBe('crafting')
  })
})

describe('operator facts', () => {
  it('reports every unset fact and treats blanks as unset', () => {
    const facts = readOperatorFacts({ VITE_OPERATOR_COUNTRY: ' ' , VITE_HOSTING_REGION: 'X' })
    expect(facts.operatorCountry).toBeNull()
    expect(facts.hostingRegion).toBe('X')
    expect(missingOperatorFacts(facts)).toHaveLength(5)
  })
})

describe('application with a public information page', () => {
  const fetchSpy = vi.fn()
  beforeEach(() => {
    fetchSpy.mockReset()
    fetchSpy.mockImplementation(async () => new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetchSpy)
    window.sessionStorage.clear()
    window.sessionStorage.setItem('gw2.apiKey', 'retained-test-key')
    resetAccountSession()
    installAccountSession()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    window.sessionStorage.clear()
    window.location.hash = ''
  })

  it('renders the page, title, footer and incomplete notice with no request', async () => {
    window.location.hash = '#/privacy'
    const App = (await import('../App.vue')).default
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.find('[data-test="page-heading"]').text()).toBe('Privacy')
    expect(document.title).toContain('Privacy')
    expect(wrapper.find('[data-test="preview-incomplete"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-test^="footer-link-"]').map(a => a.text())).toEqual([
      'Legal notice', 'Privacy', 'Cookies & browser storage'
    ])
    expect(wrapper.find('[data-test="footer-link-privacy"]').attributes('aria-current')).toBe('page')
    expect(fetchSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows the footer on a disconnected app destination and navigates by click', async () => {
    window.sessionStorage.clear()
    window.location.hash = '#/bank'
    const App = (await import('../App.vue')).default
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.find('[data-test="site-footer"]').exists()).toBe(true)
    await wrapper.find('[data-test="footer-link-legal"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="page-heading"]').text()).toBe('Legal notice')
    expect(window.location.hash).toBe('#/legal')
    wrapper.unmount()
  })
})
