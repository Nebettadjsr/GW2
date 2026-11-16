import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import {
  MAX_RESERVATION_QUANTITY, PREFERENCE_KEY_PREFIX, createMaterialProtectionState,
  type MaterialProtectionState, type PreferenceStorage
} from '../materialProtectionState'

const A = 'aaaaaaaa-0000-0000-0000-000000000235'
const B = 'bbbbbbbb-0000-0000-0000-000000000235'

class Storage implements PreferenceStorage {
  data = new Map<string, string>()
  failReads = false
  failWrites = false
  getItem(key: string): string | null {
    if (this.failReads) throw new Error('Read denied')
    return this.data.get(key) ?? null
  }
  setItem(key: string, value: string): void {
    if (this.failWrites) throw new DOMException('Full', 'QuotaExceededError')
    this.data.set(key, value)
  }
  removeItem(key: string): void { this.data.delete(key) }
}

let account: { id: string | null }
let storage: Storage
const instances: MaterialProtectionState[] = []
function open(): MaterialProtectionState {
  const instance = createMaterialProtectionState({ storage, accountId: () => account.id })
  instances.push(instance)
  return instance
}
const record = (id = A) => JSON.parse(storage.data.get(PREFERENCE_KEY_PREFIX + id)!)

beforeEach(() => {
  account = reactive({ id: A as string | null })
  storage = new Storage()
})
afterEach(() => {
  instances.splice(0).forEach(instance => instance.dispose())
  vi.restoreAllMocks()
})

describe('saved reservation intent', () => {
  it.each([
    [undefined, [19697], [19697]],
    [['bank'], [19697], []],
    [['materials'], [], [19697]]
  ])('restores legacy ALL selections with source hints %j', (sources, bank, materials) => {
    storage.setItem(PREFERENCE_KEY_PREFIX + A, JSON.stringify({ version: 1, protectedItemIds: [19697], enabled: true, sources }))
    const state = open()
    expect(state.committedFor('bank')).toEqual(bank)
    expect(state.committedFor('materials')).toEqual(materials)
    expect(state.quantitiesFor('bank')).toEqual({})
    expect(state.quantitiesFor('materials')).toEqual({})
    expect(state.state.activation).toBe(true)
  })

  it('persists finite and ALL modes independently per store/account through restart', () => {
    const state = open()
    state.reserveQuantity(19697, 'bank', 250)
    state.togglePending(19697, 'materials')
    state.setActivation(true)
    account.id = B
    expect(state.quantityFor('bank', 19697)).toBe(0)
    expect(state.state.activation).toBe(false)
    state.reserveQuantity(19697, 'materials', 17)
    state.dispose()

    const restored = open()
    expect(restored.quantitiesFor('materials')).toEqual({ 19697: 17 })
    expect(restored.committedFor('materials')).toEqual([])
    account.id = A
    expect(restored.quantityFor('bank', 19697)).toBe(250)
    expect(restored.committedFor('bank')).toEqual([])
    expect(restored.committedFor('materials')).toEqual([19697])
    expect(restored.quantityFor('materials', 19697)).toBe(0)
    expect(restored.state.activation).toBe(true)
    expect(record().quantities.bank).toEqual({ 19697: 250 })
    expect(record(B).quantities.materials).toEqual({ 19697: 17 })
  })

  it('normal clicks promote a finite reservation to ALL then clear without resurrecting the target', () => {
    const state = open()
    state.reserveQuantity(19697, 'bank', 250)
    state.reserveQuantity(19697, 'materials', 30)
    state.togglePending(19697, 'bank')
    expect(state.committedFor('bank')).toEqual([19697])
    expect(state.quantityFor('bank', 19697)).toBe(0)
    state.togglePending(19697, 'bank')
    expect(state.committedFor('bank')).toEqual([])
    expect(state.quantityFor('bank', 19697)).toBe(0)
    expect(state.quantityFor('materials', 19697)).toBe(30)
    expect(record().quantities).toEqual({ bank: {}, materials: { 19697: 30 } })
  })

  it('promotes a mixed group to ALL in one write then clears only that fully ALL group', () => {
    const state = open()
    state.reserveQuantity(1, 'materials', 250)
    state.togglePending(2, 'materials')
    state.reserveQuantity(99, 'materials', 17) // absent from the displayed group
    state.togglePending(1, 'bank')
    const writes = vi.spyOn(storage, 'setItem')

    state.togglePendingGroup([1, 2, 3, 1], 'materials')
    expect(writes).toHaveBeenCalledTimes(1)
    expect(state.committedFor('materials')).toEqual([1, 2, 3])
    expect(state.quantitiesFor('materials')).toEqual({ 99: 17 })
    state.togglePendingGroup([3, 2, 1], 'materials')
    expect(writes).toHaveBeenCalledTimes(2)
    expect(state.committedFor('materials')).toEqual([])
    expect(state.committedFor('bank')).toEqual([1])
    expect(record().quantities.materials).toEqual({ 99: 17 })
  })

  it('accepts targets above the crafting cap and the supported maximum, and zero clears immediately', () => {
    const state = open()
    expect(state.reserveQuantity(19697, 'bank', 5000)).toBe(true)
    expect(state.quantityFor('bank', 19697)).toBe(5000)
    expect(state.reserveQuantity(19697, 'bank', MAX_RESERVATION_QUANTITY)).toBe(true)
    expect(record().quantities.bank).toEqual({ 19697: MAX_RESERVATION_QUANTITY })
    state.setActivation(true)
    expect(state.reserveQuantity(19697, 'bank', 0)).toBe(true)
    expect(state.quantityFor('bank', 19697)).toBe(0)
    expect(record().quantities.bank).toEqual({})
    expect(state.state.activation).toBe(false)
    state.reserveQuantity(19697, 'bank', 250)
    expect(state.state.activation).toBe(false)
  })

  it.each([-1, 1.5, NaN, Infinity, MAX_RESERVATION_QUANTITY + 1, '250', null])(
    'rejects invalid target %s without overwriting or persisting the saved target', quantity => {
      const state = open()
      state.reserveQuantity(19697, 'bank', 250)
      const writes = vi.spyOn(storage, 'setItem')
      expect(state.reserveQuantity(19697, 'bank', quantity)).toBe(false)
      expect(state.quantityFor('bank', 19697)).toBe(250)
      expect(record().quantities.bank).toEqual({ 19697: 250 })
      expect(writes).not.toHaveBeenCalled()
    }
  )

  it('restores the latest failed-write threshold from memory across account changes, then recovers storage', () => {
    const state = open()
    state.reserveQuantity(19697, 'bank', 100)
    storage.failWrites = true
    state.reserveQuantity(19697, 'bank', 250)
    expect(state.state.diagnostic).toBe('quota-exceeded')
    expect(record().quantities.bank).toEqual({ 19697: 100 })
    account.id = B
    state.reserveQuantity(19697, 'materials', 17)
    storage.failReads = true
    account.id = A
    expect(state.quantityFor('bank', 19697)).toBe(250)
    expect(state.quantityFor('materials', 19697)).toBe(0)
    expect(state.state.diagnostic).toBe('read-failed')
    storage.failWrites = false
    storage.failReads = false
    state.reserveQuantity(19697, 'bank', 251)
    expect(state.state.diagnostic).toBeNull()
    expect(record().quantities.bank).toEqual({ 19697: 251 })
    account.id = B
    expect(state.quantityFor('materials', 19697)).toBe(17)
  })

  it('forgets only the deleted GUID including finite shadow state and rejects its delayed response', () => {
    const state = open()
    state.reserveQuantity(19697, 'bank', 250)
    state.setActivation(true)
    const oldA = state.beginRequest()
    account.id = B
    state.reserveQuantity(19697, 'materials', 17)
    state.forgetAccount(A)
    expect(storage.data.has(PREFERENCE_KEY_PREFIX + A)).toBe(false)
    expect(state.quantityFor('materials', 19697)).toBe(17)
    account.id = A
    expect(state.quantitiesFor('bank')).toEqual({})
    expect(state.state.activation).toBe(false)
    expect(state.reconcile(oldA, { materialProtectionEnabled: true, protectedItemIds: [] })).toBe(false)
    account.id = B
    expect(state.quantityFor('materials', 19697)).toBe(17)
    expect(record(B).quantities.materials).toEqual({ 19697: 17 })
  })

  it('invalidates an earlier response when only the finite threshold changes', () => {
    const state = open()
    state.reserveQuantity(19697, 'bank', 250)
    const old = state.beginRequest()
    state.reserveQuantity(19697, 'bank', 251)
    expect(state.reconcile(old, { materialProtectionEnabled: true, protectedItemIds: [] })).toBe(false)
  })
})
