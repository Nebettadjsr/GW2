import { afterEach, describe, expect, it, vi } from 'vitest'

const postJson = vi.fn(async () => ({}))
vi.mock('@/api/http', async (importOriginal) => ({ ...(await importOriginal<object>()), postJson: (...a: unknown[]) => postJson(...(a as [])) }))

afterEach(() => { postJson.mockClear(); window.localStorage.clear() })

describe('Mystic Forge project request', () => {
  it('sends only the selected recipe and target, never browser reservation/protection preferences', async () => {
    // Owner decision: a Forge project always counts ALL owned stock; the browser's Bank/Materials reservation
    // preferences exist for ordinary Profit and must not leak into the request.
    window.localStorage.setItem('gw2.reservations.v1.some-account', JSON.stringify({ 19721: 250 }))
    const { mysticForgeApi } = await import('@/api/mysticForgeApi')
    await mysticForgeApi.loadProject(-3, 5)
    expect(postJson).toHaveBeenCalledTimes(1)
    expect(postJson).toHaveBeenCalledWith('/mystic-forge/project', { recipeId: -3, target: 5 })
  })
})
