import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { connectKey, deleteAccountData, installAccountSession, resetAccountSession } from '@/api/accountSession'
import { installAccountTransport } from '@/api/http'
import InfoPage from '@/shell/InfoPage.vue'
import { DEFAULT_FORGE_SETTINGS, forgetForgePreferences, readForgePreferences, saveForgePreferences } from '../forgePreferences'

const KEY = (account: string) => `gw2.mysticForge.settings.v1.${account}`
const A = '11111111-1111-1111-1111-111111111111'
const B = '22222222-2222-2222-2222-222222222222'
const SIX = { priceMode: 'BUY_ORDER', useOwn: true, valueOwn: true, daily: 'BUY', promotions: true, reduce: 'TRADABLE' } as const

describe('forge preference cleanup', () => {
  beforeEach(() => window.localStorage.clear())
  afterEach(() => vi.restoreAllMocks())

  it('removes only that account key and leaves other accounts and unrelated keys', () => {
    saveForgePreferences(A, SIX)
    saveForgePreferences(B, SIX)
    window.localStorage.setItem('gw2.materialProtection.v1.' + A, 'x')
    expect(forgetForgePreferences(A)).toBe(true)
    expect(window.localStorage.getItem(KEY(A))).toBeNull()
    expect(window.localStorage.getItem(KEY(B))).not.toBeNull()
    expect(window.localStorage.getItem('gw2.materialProtection.v1.' + A)).toBe('x')
    expect(readForgePreferences(A).settings).toEqual(DEFAULT_FORGE_SETTINGS)
  })

  it('survives a throwing localStorage and does not report success', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('denied') })
    expect(forgetForgePreferences(A)).toBe(false)
  })
})

describe('registered cleanup on a deletion event', () => {
  beforeEach(() => {
    window.localStorage.clear()
    resetAccountSession()
    installAccountSession()
    vi.stubGlobal('fetch', vi.fn(async (input: string) => input.endsWith('/session')
      ? new Response(JSON.stringify({ accountId: A, accessContext: 'ctx', expiresAt: '2099-01-01T00:00:00Z', grants: ['account'] }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      : new Response(null, { status: 204 })))
  })
  afterEach(() => {
    installAccountTransport(null)
    resetAccountSession()
    vi.unstubAllGlobals()
  })

  it('removes the deleted account Forge key on a confirmed deletion and keeps the other account', async () => {
    saveForgePreferences(A, SIX)
    saveForgePreferences(B, SIX)
    await connectKey('KEY')
    expect(await deleteAccountData()).toBe('deleted')
    expect(window.localStorage.getItem(KEY(A))).toBeNull()
    expect(window.localStorage.getItem(KEY(B))).not.toBeNull()
    expect(readForgePreferences(A).settings).toEqual(DEFAULT_FORGE_SETTINGS)
  })
})

describe('information page storage text', () => {
  it('names both keys and says Delete cached data clears the Forge settings', () => {
    const text = mount(InfoPage, { props: { page: 'storage' } }).text()
    expect(text).toContain('gw2.materialProtection.v1.<account ID>')
    expect(text).toContain('gw2.mysticForge.settings.v1.<account ID>')
    expect(text).toContain('Delete cached data clears this account\'s saved selections and Mystic Forge settings')
    expect(text).toContain('Disconnect keeps them')
  })
})
