import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { connectKey, connection, installAccountSession, resetAccountSession } from '@/api/accountSession'
import { ApiRequestError, getJson, installAccountTransport } from '@/api/http'
import { systemApi, type OwnerSystemDetails } from '@/api/systemApi'
import { provideSyncOperations } from '../provideSyncOperations'
import SyncScreen from '../SyncScreen.vue'
import { FakeSyncApi, deferred } from './syncFixtures'

/** Same-account lease renewal re-discovers owner capability using the real session and transport. */
const DETAILS: OwnerSystemDetails = {
  running: false, lastCheckedAt: null, lastChangedAt: null, lastRecipeSyncAt: null, lastGraphRebuildAt: null,
  lastFailure: null, cachedPriceItems: 1, stalePriceItems: 0, newestPriceFetchedAt: null, priceCacheError: null
}
const FORBIDDEN = new ApiRequestError('restricted', 'OWNER_REQUIRED', 403)
const api = new FakeSyncApi()
const Shell = defineComponent({ setup() { provideSyncOperations(api); return () => h(SyncScreen) } })

let proofs = 0
function proofResponse(expiresAt: string): Response {
  proofs += 1
  return new Response(JSON.stringify({ accessContext: `ctx-${proofs}`, expiresAt, accountId: 'acct-1', grants: ['account'] }), {
    status: 200, headers: { 'Content-Type': 'application/json' }
  })
}
let nextExpiry: string

async function settle(): Promise<void> {
  for (let i = 0; i < 15; i += 1) await Promise.resolve()
  await nextTick()
}
/** Lets the real transport renew the lapsed lease for the same account. */
async function renewLease(): Promise<void> {
  await getJson('/system/status').catch(() => undefined)
  await settle()
}

beforeEach(async () => {
  proofs = 0
  nextExpiry = '2000-01-01T00:00:00Z' // already lapsed, so the next request renews
  resetAccountSession()
  installAccountSession()
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    if (String(input).endsWith('/session')) return proofResponse(nextExpiry)
    return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
  vi.spyOn(systemApi, 'readStatus').mockResolvedValue({ globalHealth: 'OK', priceHealth: 'OK' })
  await connectKey('KEY-A')
  nextExpiry = '2099-01-01T00:00:00Z'
})
afterEach(() => {
  installAccountTransport(null)
  resetAccountSession()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('owner capability across same-account lease renewal', () => {
  it('renewalChangesLeaseVersionButNotAccountGeneration', async () => {
    const generation = connection.generation
    const version = connection.leaseVersion
    await renewLease()
    expect(connection.generation).toBe(generation)
    expect(connection.leaseVersion).toBe(version + 1)
    expect(connection.accountId).toBe('acct-1')
  })

  it('rediscoversAsOwnerAfterRenewalWhenARememberedNonOwnerBecomesOwner', async () => {
    const read = vi.spyOn(systemApi, 'readOwnerDetails').mockRejectedValue(FORBIDDEN)
    const wrapper = mount(Shell)
    await settle()
    expect(wrapper.find('[data-test="owner-admin"]').exists()).toBe(false)
    expect(read).toHaveBeenCalledTimes(1)

    read.mockResolvedValue(DETAILS)
    await renewLease()
    expect(read).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-test="owner-admin"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('withdrawsOwnerUiAndTasksWhenRenewalFindsOwnerPrivilegeGone', async () => {
    const read = vi.spyOn(systemApi, 'readOwnerDetails').mockResolvedValue(DETAILS)
    const wrapper = mount(Shell)
    await settle()
    expect(wrapper.find('[data-test="owner-admin"]').exists()).toBe(true)

    read.mockRejectedValue(FORBIDDEN)
    await renewLease()
    expect(wrapper.find('[data-test="owner-admin"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="sync-trigger-FULL_ITEM_CATALOG"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('discardsAnOwnerAnswerRequestedUnderTheLeaseThatWasRenewed', async () => {
    const old = deferred<OwnerSystemDetails>()
    const read = vi.spyOn(systemApi, 'readOwnerDetails').mockReturnValueOnce(old.promise)
    const wrapper = mount(Shell)
    await settle()

    read.mockRejectedValue(FORBIDDEN)
    await renewLease()
    old.resolve(DETAILS)
    await settle()
    expect(wrapper.find('[data-test="owner-admin"]').exists()).toBe(false)
    expect(read).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})
