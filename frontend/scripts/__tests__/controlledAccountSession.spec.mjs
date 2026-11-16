import { describe, expect, it } from 'vitest'
import { createControlledAccountSession } from '../controlledAccountSession.mjs'

function fixture(options = {}) {
  const session = createControlledAccountSession({ calculationRoutes: ['/api/crafting/profit'], searchQueries: ['Iron'], ...options })
  const replies = []
  function request(path, method, headers = {}, body = '') {
    const handled = session.answer({
      request: { method, headers }, url: new URL(path, 'http://127.0.0.1:5176'), body,
      sendJson: (status, body) => replies.push({ status, body })
    })
    return { handled, reply: replies.at(-1) }
  }
  return { session, request }
}

describe('controlled account boundary', () => {
  it('returns explicit cached lookup fixtures for only the allowed ingredient identity', () => {
    const { request } = fixture({ searchQueries: ['19698'], searchResults: { '19698': [11, 17] } })
    request('/api/session', 'POST', { 'x-gw2-api-key': 'synthetic-read-only-smoke-key' })
    expect(request('/api/crafting/ingredient-search?query=19698', 'GET').reply)
      .toEqual({ status: 200, body: { recipeIds: [11, 17] } })
    expect(request('/api/crafting/ingredient-search?query=Item%20%2319698', 'GET').reply.status).toBe(400)
  })

  it('admits only explicitly listed account reads after proof with the current context', () => {
    const { request } = fixture({ accountReadRoutes: ['/api/account/bank', '/api/account/materials'] })
    expect(request('/api/account/bank', 'GET').reply.status).toBe(401)
    request('/api/session', 'POST', { 'x-gw2-api-key': 'synthetic-read-only-smoke-key' })
    expect(request('/api/account/bank', 'GET', { 'x-account-context': 'smoke-context-1' }).handled).toBe(false)
    expect(request('/api/account/materials', 'GET', { 'x-account-context': 'old-context' }).reply.status).toBe(401)
    expect(request('/api/account/bank', 'POST', { 'x-account-context': 'smoke-context-1' }).reply.status).toBe(405)
    expect(request('/api/account/inventory', 'GET', { 'x-account-context': 'smoke-context-1' }).reply.status).toBe(404)
    expect(request('/api/sync/account/bank', 'POST').reply.status).toBe(404)
  })

  it('admits protected fixtures only after the normal retained-key proof with its returned context', () => {
    const { request } = fixture()
    expect(request('/api/crafting/profit', 'POST').reply.status).toBe(401)
    expect(request('/api/session', 'POST', { 'x-gw2-api-key': 'synthetic-read-only-smoke-key' }).reply)
      .toMatchObject({ status: 200, body: { accountId: '00000000-0000-4000-8000-000000000194', accessContext: 'smoke-context-1' } })
    expect(request('/api/crafting/profit', 'POST', { 'x-account-context': 'smoke-context-1' }).handled).toBe(false)
    expect(request('/api/crafting/profit', 'POST', { 'x-account-context': 'old-context' }).reply.status).toBe(401)
  })

  it('refuses a real or unexpected key, key forwarding, wrong methods and routes outside the feature', () => {
    const { request } = fixture()
    expect(request('/api/session', 'POST', { 'x-gw2-api-key': 'unexpected-key' }).reply.status).toBe(401)
    request('/api/session', 'POST', { 'x-gw2-api-key': 'synthetic-read-only-smoke-key' })
    expect(request('/api/crafting/profit', 'POST', { 'x-gw2-api-key': 'synthetic-read-only-smoke-key' }).reply.status).toBe(400)
    expect(request('/api/crafting/profit?key=synthetic-read-only-smoke-key', 'POST').reply.status).toBe(400)
    expect(request('/api/crafting/profit', 'POST', {}, '{"apiKey":"synthetic-read-only-smoke-key"}').reply.status).toBe(400)
    expect(request('/api/crafting/profit', 'GET').reply.status).toBe(405)
    expect(request('/api/sync/account', 'POST').reply.status).toBe(404)
    expect(request('/api/crafting/ingredient-search?query=Iron', 'GET').reply)
      .toEqual({ status: 200, body: { recipeIds: [] } })
    expect(request('/api/crafting/ingredient-search?query=other', 'GET').reply.status).toBe(400)
  })

  it('requires a fresh proof after another document load and prevents external traffic', async () => {
    const { session, request } = fixture()
    let onRequest
    let routeRequest
    const frame = {}
    await session.install({
      addInitScript: async () => undefined,
      on: (_, handler) => { onRequest = handler },
      mainFrame: () => frame,
      route: async (_, handler) => { routeRequest = handler }
    }, 'http://127.0.0.1:5176')
    const document = () => onRequest({ isNavigationRequest: () => true, frame: () => frame })
    document()
    request('/api/session', 'POST', { 'x-gw2-api-key': 'synthetic-read-only-smoke-key' })
    expect(session.verify()).toContain('1 proofs')
    document()
    expect(request('/api/crafting/profit', 'POST', { 'x-account-context': 'smoke-context-1' }).reply.status).toBe(401)
    let aborted = false
    await routeRequest({ request: () => ({ url: () => 'https://api.guildwars2.com/v2/account' }), abort: () => { aborted = true } })
    expect(aborted).toBe(true)
    expect(() => session.verify()).toThrow('Controlled session boundary violations')
  })
})
