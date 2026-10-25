import { describe, expect, it } from 'vitest'
import type { CraftingApi } from '@/api/craftingApi'
import type { CraftingDiscoveryRequest, CraftingDiscoveryResponse } from '@/api/types'
import { useCraftingDiscovery } from '../useCraftingDiscovery'
import { echoedDiscoveryResponse, selectorOptions } from './fixtures'

/** HTTP 202 / PENDING carries no rows: it must never read as a completed, empty calculation. */
describe('Discovery pending answer', () => {
  it('reports missing inputs instead of a finished empty list', async () => {
    const api = {
      loadSelectorOptions: async () => selectorOptions,
      calculateDiscovery: async (request: CraftingDiscoveryRequest) => ({
        ...echoedDiscoveryResponse(request),
        rows: null,
        freshness: { status: 'PENDING', sources: ['recipes'] }
      } as CraftingDiscoveryResponse)
    } as unknown as CraftingApi

    const discovery = useCraftingDiscovery(api)
    await discovery.open()

    expect(discovery.hasResult.value).toBe(false)
    expect(discovery.rows.value).toEqual([])
    expect(discovery.requestError.value).toMatch(/still being synchronized/)
  })
})
