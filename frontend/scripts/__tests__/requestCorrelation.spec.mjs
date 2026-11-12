import { describe, expect, it } from 'vitest'
import { evaluate } from '../requestCorrelation.mjs'

const req = (seq, method, path, extra = {}) => ({ seq, method, path, post: null, status: null, done: false, failed: false, json: null, ...extra })
const detail = [{ method: 'POST', path: '/api/crafting/profit/resolution', accept: (e) => (e.json?.recipeId === JSON.parse(e.post).recipeId ? null : 'resolved a different recipe') }]

describe('request correlation', () => {
  it('does not complete on a response that began before the timed action', () => {
    const entries = [req(0, 'POST', '/api/crafting/profit/resolution', { status: 200, done: true })]
    expect(evaluate(entries, 1, detail).state).toBe('waiting')
  })
  it('does not complete on a broad prefix or unrelated path', () => {
    const entries = [req(3, 'GET', '/api/crafting/selector-options', { status: 200, done: true }), req(4, 'POST', '/api/crafting/profit', { status: 200, done: true })]
    expect(evaluate(entries, 2, detail).state).toBe('waiting')
  })
  it('stays waiting while a started request is slow (loading or retained content on screen)', () => {
    expect(evaluate([req(2, 'POST', '/api/crafting/profit/resolution')], 2, detail).state).toBe('waiting')
  })
  it('fails on a failing or non-200 response', () => {
    expect(evaluate([req(2, 'POST', '/api/crafting/profit/resolution', { done: true, status: 500 })], 2, detail).state).toBe('failed')
    expect(evaluate([req(2, 'POST', '/api/crafting/profit/resolution', { done: true, failed: true })], 2, detail).state).toBe('failed')
  })
  it('requires the exact method', () => {
    expect(evaluate([req(2, 'GET', '/api/account/bank', { done: true, status: 200 })], 2, [{ method: 'POST', path: '/api/account/bank' }]).state).toBe('waiting')
  })
  it('checks the selected identity of the response against the request', () => {
    const wrong = req(2, 'POST', '/api/crafting/profit/resolution', { done: true, status: 200, post: '{"recipeId":5}', json: { recipeId: 6 } })
    expect(evaluate([wrong], 2, detail).state).toBe('failed')
    const right = req(2, 'POST', '/api/crafting/profit/resolution', { done: true, status: 200, post: '{"recipeId":5}', json: { recipeId: 5 } })
    expect(evaluate([right], 2, detail).state).toBe('complete')
  })
  it('requires every expected request of a multi-request journey', () => {
    const exp = [{ method: 'GET', pattern: /^\/api\/account\/characters\/[^/]+$/ }, { method: 'GET', pattern: /\/equipment-tabs$/ }]
    const one = [req(1, 'GET', '/api/account/characters/Aria', { done: true, status: 200 })]
    expect(evaluate(one, 1, exp).state).toBe('waiting')
    one.push(req(2, 'GET', '/api/account/characters/Aria/equipment-tabs', { done: true, status: 200 }))
    expect(evaluate(one, 1, exp).state).toBe('complete')
  })
})
