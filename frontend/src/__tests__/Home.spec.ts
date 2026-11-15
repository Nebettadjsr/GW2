import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { connection, installAccountSession, resetAccountSession, STORAGE_KEY } from '@/api/accountSession'
import { installAccountTransport } from '@/api/http'

// Exercise the real shell and route listeners, with every account request visible at fetch.
beforeAll(async () => { await import('../App.vue') }, 30_000)

let wrapper: VueWrapper | null = null
let robots: HTMLMetaElement
const fetchSpy = vi.fn()

beforeEach(() => {
  resetAccountSession()
  installAccountSession()
  window.sessionStorage.clear()
  window.history.replaceState(null, '', '/')
  fetchSpy.mockReset()
  fetchSpy.mockImplementation(async () => new Response('{}', { status: 200 }))
  vi.stubGlobal('fetch', fetchSpy)
  robots = document.createElement('meta')
  robots.name = 'robots'
  robots.content = 'noindex, nofollow'
  document.head.append(robots)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  robots.remove()
  installAccountTransport(null)
  resetAccountSession()
  window.sessionStorage.clear()
  vi.unstubAllGlobals()
})

async function open(path: string): Promise<VueWrapper> {
  window.history.replaceState(null, '', path)
  const App = (await import('../App.vue')).default
  wrapper = mount(App, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('public Home landing', () => {
  it.each(['/', '/welcome'])('opens %s as the canonical Home without a key or API request', async path => {
    const app = await open(path)

    expect(window.location.pathname + window.location.hash).toBe('/welcome')
    expect(app.find('[data-test="home-screen"]').exists()).toBe(true)
    expect(app.get('[data-test="nav-home"]').attributes('aria-current')).toBe('page')
    expect(document.title).toBe('GW2 Crafting Tool · Gold & Beginner Tips · Unofficial fan project')
    expect(robots.content).toBe('index, follow')
    expect(app.find('[data-test="connection-needed"]').exists()).toBe(false)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('keeps the guide and legal pages quiet with a retained key and preserves route focus and indexing', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, 'RETAINED-QA-KEY')
    const app = await open('/welcome')

    for (const tab of ['features', 'guide', 'tips', 'welcome']) {
      await app.get(`#home-tab-${tab}`).trigger('click')
      expect(app.get(`#home-tab-${tab}`).attributes('aria-selected')).toBe('true')
      expect(app.get(`#home-panel-${tab}`).isVisible()).toBe(true)
    }
    expect(app.get('[data-test="nav-bank"]').attributes('href')).toBe('/#/bank')

    for (const page of ['legal', 'privacy', 'storage']) {
      await app.get(`[data-test="footer-link-${page}"]`).trigger('click')
      await flushPromises()
      expect(window.location.pathname + window.location.hash).toBe(`/#/${page}`)
      expect(app.find(`[data-test="info-${page}"]`).exists()).toBe(true)
      expect(robots.content).toBe('noindex, nofollow')
      expect(document.activeElement).toBe(app.get('[data-test="page-heading"]').element)

      await app.get('[data-test="nav-home"]').trigger('click')
      await flushPromises()
      expect(window.location.pathname + window.location.hash).toBe('/welcome')
      expect(robots.content).toBe('index, follow')
      expect(document.activeElement).toBe(app.get('[data-test="page-heading"]').element)
    }

    expect(connection.phase).toBe('disconnected')
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe('RETAINED-QA-KEY')
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('keeps app bookmarks non-indexable and follows browser history back to Home', async () => {
    const app = await open('/#/bank')
    expect(app.find('[data-test="connection-needed"]').exists()).toBe(true)
    expect(app.get('[data-test="nav-bank"]').attributes('aria-current')).toBe('page')
    expect(window.location.pathname + window.location.hash).toBe('/#/bank')
    expect(robots.content).toBe('noindex, nofollow')

    window.history.replaceState(null, '', '/welcome')
    window.dispatchEvent(new PopStateEvent('popstate'))
    await flushPromises()
    expect(app.find('[data-test="home-screen"]').exists()).toBe(true)
    expect(robots.content).toBe('index, follow')

    // The skip link changes only the focus target; it must not open an application route.
    window.location.hash = '#main-content'
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    await flushPromises()
    expect(app.get('[data-test="nav-home"]').attributes('aria-current')).toBe('page')
    expect(robots.content).toBe('index, follow')
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
