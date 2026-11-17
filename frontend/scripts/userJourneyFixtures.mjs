import assert from 'node:assert/strict'
import { bankSnapshot, materials, tableResponse, detailResponse, TABLE, DETAIL } from './reservationBrowserFixtures.mjs'

export const KEY_A = 'synthetic-journey-key-A'
export const KEY_B = 'synthetic-journey-key-B'
export const BAD_KEY = 'synthetic-journey-refused-key'
export const ACCOUNT_A = '00000000-0000-4000-8000-000000000243'
export const ACCOUNT_B = '00000000-0000-4000-8000-000000000244'
export const storageKey = account => `gw2.materialProtection.v1.${account}`

/** Strict scripted HTTP boundary. All quantities/economics are backend answers, not derived here. */
export function createJourneyBackend() {
  const failures = []
  const calls = []
  const proofs = []
  let context = null
  let account = null
  let sequence = 0
  let stale = false
  let holdBank = false
  let heldBank = null
  let heldTask = null
  let holdProfit = false
  let heldProfit = null

  function snapshot(owner = account) {
    const value = bankSnapshot(300)
    value.slots[0].itemName = owner === ACCOUNT_B ? 'B Copper Ore' : 'A Copper Ore'
    if (stale) {
      value.slots[0].count = 42
      value.freshness = { status: 'STALE', completedAt: '2020-01-01T00:00:00Z' }
    } else if (sequence > 0) value.slots[0].count = 77
    return value
  }

  function answerApi({ request, response, url, body, sendJson }) {
    const path = url.pathname
    const key = request.headers['x-gw2-api-key']
    const ticket = request.headers['x-account-context']
    calls.push({ path, method: request.method, account, context: ticket ?? null, body })
    try {
      for (const secret of [KEY_A, KEY_B, BAD_KEY]) {
        assert.ok(!url.href.includes(secret) && !body.includes(secret), 'A key entered a URL or body')
      }
      if (path === '/api/session') {
        assert.equal(request.method, 'POST')
        assert.equal(body, '')
        assert.ok([KEY_A, KEY_B, BAD_KEY].includes(key), 'Unexpected proof key')
        if (key === BAD_KEY) return sendJson(401, { error: 'INVALID_CREDENTIAL', message: 'Controlled key refusal.' })
        account = key === KEY_A ? ACCOUNT_A : ACCOUNT_B
        context = `journey-context-${proofs.length + 1}`
        proofs.push({ account, context })
        return sendJson(200, { accountId: account, accessContext: context,
          expiresAt: '2099-01-01T00:00:00Z', grants: ['account', 'inventories', 'characters', 'unlocks'] })
      }
      const sharedItemRead = ['/api/items/metadata', '/api/items/prices'].includes(path)
      if (sharedItemRead) assert.equal(ticket, undefined, `Public item read carried account context on ${path}`)
      else {
        assert.notEqual(context, null, `Protected request preceded proof: ${path}`)
        assert.equal(ticket, context, `Wrong account context on ${path}`)
      }
      const keyed = ['/api/sync/account', '/api/sync/account/bank'].includes(path)
      assert.equal(key, keyed ? (account === ACCOUNT_A ? KEY_A : KEY_B) : undefined, `Wrong key forwarding on ${path}`)
      if (path === '/api/session/logout') {
        assert.equal(request.method, 'POST')
        assert.equal(body, '')
        context = null
        account = null
        response.writeHead(204)
        return response.end()
      }
      if (path === '/api/sync/account/bank' || path === '/api/sync/account') {
        assert.equal(request.method, 'POST')
        assert.deepEqual(JSON.parse(body), {})
        const automatic = path.endsWith('/bank')
        return sendJson(202, { taskId: automatic ? 'bank-refresh' : 'account-refresh',
          operation: automatic ? 'BANK_SYNC' : 'ACCOUNT_SYNC',
          statusUrl: `/api/sync/tasks/${automatic ? 'bank-refresh' : 'account-refresh'}`, coversRequest: true })
      }
      if (path === '/api/sync/tasks/bank-refresh') {
        assert.equal(request.method, 'GET')
        assert.equal(heldTask, null, 'Duplicate automatic task polling while first response held')
        heldTask = () => sendJson(200, { taskId: 'bank-refresh', operation: 'BANK_SYNC', state: 'FAILED',
          failure: { code: 'UPSTREAM_UNAVAILABLE', message: 'Controlled refresh failure.' } })
        return
      }
      if (path === '/api/sync/tasks/account-refresh') {
        assert.equal(request.method, 'GET')
        stale = false
        sequence += 1
        return sendJson(200, { taskId: 'account-refresh', operation: 'ACCOUNT_SYNC', state: 'SUCCEEDED', failure: null })
      }
      if (path === TABLE || path === DETAIL) {
        assert.equal(request.method, 'POST')
        const input = JSON.parse(body)
        if (path === TABLE && holdProfit) {
          holdProfit = false
          const value = tableResponse(input, 'held-profit-answer')
          value.rows[0].outputName = 'Held Profit Answer'
          heldProfit = () => sendJson(200, value)
          return
        }
        return sendJson(200, path === TABLE ? tableResponse(input, 'journey-result') : detailResponse(input))
      }
      assert.equal(request.method, 'GET')
      assert.equal(body, '')
      if (path === '/api/account/bank') {
        const value = snapshot()
        if (holdBank && account === ACCOUNT_A) {
          holdBank = false
          value.slots[0].itemName = 'Late A stack'
          value.slots[0].count = 999
          heldBank = () => sendJson(200, value)
          return
        }
        return sendJson(200, value)
      }
      if (path === '/api/account/materials') return sendJson(200, materials)
      if (path === '/api/items/metadata') {
        assert.equal(url.searchParams.get('ids'), '19721,24277,44602,23041,89409,67027,19986')
        return sendJson(200, { items: [19721, 24277, 44602, 23041, 89409, 67027, 19986].map(itemId => ({
          itemId, name: itemId === 19721 ? 'Glob of Ectoplasm' : itemId === 24277 ? 'Pile of Crystalline Dust' : 'Salvage tool', iconUrl: null
        })) })
      }
      if (path === '/api/items/prices') {
        assert.equal(url.searchParams.get('ids'), '19721,24277')
        return sendJson(200, { prices: [
          { itemId: 19721, buyUnitCopper: 100, sellUnitCopper: 120 },
          { itemId: 24277, buyUnitCopper: 200, sellUnitCopper: 240 }
        ] })
      }
      if (path === '/api/account/luck') return sendJson(200, {
        consumedLuck: 14134, currentLuckMagicFindPercent: 50, cumulativeLuckForCurrentPercent: 13790,
        nextMagicFindPercent: 51, cumulativeLuckForNextPercent: 14550, luckRemainingToNextPercent: 416,
        luckRemainingToCap: 4281316, cumulativeLuckForCap: 4295450, fetchedAt: '2099-01-01T00:00:00Z',
        targets: [{ kind: 'PLUS_5', magicFindPercent: 55, cumulativeLuck: 17930, luckRemaining: 3796 }]
      })
      if (path === '/api/crafting/selector-options') return sendJson(200, {
        defaultScopeKind: 'ALL', disciplines: ['Armorsmith'], characterOptionCount: 0, characterOptions: [] })
      if (path === '/api/system/status') return sendJson(200, { globalHealth: 'OK', priceHealth: 'OK',
        accountLastRefreshedAt: '2099-01-01T00:00:00Z' })
      if (path === '/api/system/status/details') return sendJson(403, { error: 'FORBIDDEN', message: 'Not an owner account.' })
      assert.fail(`Unexpected controlled route ${path}`)
    } catch (error) {
      failures.push(error.message)
      sendJson(400, { error: 'INVALID_JOURNEY_REQUEST', message: error.message })
    }
  }

  return { answerApi, calls, proofs, failures,
    staleBank() { stale = true },
    holdNextBank() { holdBank = true },
    hasHeldBank: () => heldBank !== null,
    hasHeldTask: () => heldTask !== null,
    holdNextProfit() { holdProfit = true },
    hasHeldProfit: () => heldProfit !== null,
    releaseProfit() { heldProfit?.(); heldProfit = null },
    releaseBank() { heldBank?.(); heldBank = null },
    failRefresh() { heldTask?.(); heldTask = null },
    verify() { assert.deepEqual(failures, [], 'Controlled HTTP boundary violations') },
    cleanup() { heldBank?.(); heldTask?.(); heldProfit?.(); heldBank = null; heldTask = null; heldProfit = null }
  }
}
