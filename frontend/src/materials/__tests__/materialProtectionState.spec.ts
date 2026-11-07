import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, reactive } from 'vue'
import {
  PREFERENCE_KEY_PREFIX,
  canonicalIds,
  createMaterialProtectionState,
  type MaterialProtectionState,
  type PreferenceStorage
} from '../materialProtectionState'
import { connectKey, connection, disconnect, installAccountSession, resetAccountSession } from '@/api/accountSession'
import { installAccountTransport } from '@/api/http'

class FakeStorage implements PreferenceStorage {
  data = new Map<string, string>()
  getItem(key: string): string | null {
    return this.data.get(key) ?? null
  }
  setItem(key: string, value: string): void {
    this.data.set(key, value)
  }
  removeItem(key: string): void {
    this.data.delete(key)
  }
}

const A = 'aaaaaaaa-0000-0000-0000-000000000001'
const B = 'bbbbbbbb-0000-0000-0000-000000000002'

let source: { id: string | null }
let storage: FakeStorage
let created: MaterialProtectionState[]

function make(s: PreferenceStorage | null = storage): MaterialProtectionState {
  const m = createMaterialProtectionState({ storage: s, accountId: () => source.id })
  created.push(m)
  return m
}

beforeEach(() => {
  source = reactive({ id: null as string | null })
  storage = new FakeStorage()
  created = []
})
afterEach(() => created.forEach((m) => m.dispose()))

describe('canonicalIds', () => {
  it('keeps unique positive integers in numeric order and drops the rest', () => {
    expect(canonicalIds([10, 2, 2, 0, -3, 1.5, '7', null, Number.NaN, 3])).toEqual([2, 3, 10])
    expect(canonicalIds('nope')).toEqual([])
  })
})

describe('partitioning, commit and restart', () => {
  it('stores independent values per account and restores them in a new instance', () => {
    const m = make()
    source.id = A
    m.setPending([5, 3])
    m.commit()
    m.setActivation(true)
    source.id = B
    expect(m.state.committed).toEqual([])
    expect(m.state.activation).toBe(false)
    m.setPending([9])
    m.commit()
    source.id = null
    const reloaded = make()
    expect(reloaded.state.status).toBe('uninitialized')
    source.id = A
    expect(reloaded.state.committed).toEqual([3, 5])
    expect(reloaded.state.activation).toBe(true)
    source.id = B
    expect(reloaded.state.committed).toEqual([9])
    expect(reloaded.state.activation).toBe(false)
  })

  it('keeps pending edits out of storage until commit', () => {
    const m = make()
    source.id = A
    m.setPending([1, 2])
    expect(m.dirty.value).toBe(true)
    expect(storage.data.size).toBe(0)
    m.commit()
    expect(m.dirty.value).toBe(false)
    expect(JSON.parse(storage.data.get(PREFERENCE_KEY_PREFIX + A)!).protectedItemIds).toEqual([1, 2])
    m.togglePending(2)
    m.togglePending(7)
    expect(m.state.pending).toEqual([1, 7])
    m.discardPending()
    expect(m.state.pending).toEqual([1, 2])
  })

  it('clears presentation on disconnect but keeps storage; reconnect restores', () => {
    const m = make()
    source.id = A
    m.setPending([4])
    m.commit()
    source.id = null
    expect(m.state.status).toBe('uninitialized')
    expect(m.state.committed).toEqual([])
    expect(m.state.pending).toEqual([])
    expect(storage.data.has(PREFERENCE_KEY_PREFIX + A)).toBe(true)
    source.id = A
    expect(m.state.committed).toEqual([4])
    expect(m.state.pending).toEqual([4])
  })

  it('ignores operations before an account is restored', () => {
    const m = make()
    m.setPending([1])
    m.commit()
    m.setActivation(true)
    expect(storage.data.size).toBe(0)
    expect(m.beginRequest()).toBeNull()
  })
})

describe('delayed work', () => {
  it('ignores a delayed restore for Account A once Account B is current', async () => {
    let resolveA!: (v: string | null) => void
    const slow: PreferenceStorage = {
      getItem: (key) =>
        key.endsWith(A)
          ? new Promise<string | null>((r) => (resolveA = r))
          : Promise.resolve(JSON.stringify({ version: 1, protectedItemIds: [8], enabled: false })),
      setItem: (k, v) => void storage.setItem(k, v),
      removeItem: () => undefined
    }
    const m = make(slow)
    source.id = A
    expect(m.state.status).toBe('loading')
    source.id = B
    await Promise.resolve()
    await Promise.resolve()
    expect(m.state.committed).toEqual([8])
    resolveA(JSON.stringify({ version: 1, protectedItemIds: [1, 2], enabled: true }))
    await Promise.resolve()
    await Promise.resolve()
    expect(m.state.accountId).toBe(B)
    expect(m.state.committed).toEqual([8])
    expect(m.state.activation).toBe(false)
    m.commit()
    expect(storage.data.has(PREFERENCE_KEY_PREFIX + A)).toBe(false)
  })

  it('applies a canonical response only for the current account and edit state', () => {
    const m = make()
    source.id = A
    const settings = { materialProtectionEnabled: true, protectedItemIds: [3, 1] }
    const token = m.beginRequest()
    expect(m.reconcile(token, settings)).toBe(true)
    expect(m.state.effective).toEqual({ enabled: true, protectedItemIds: [1, 3] })
    expect(m.state.committed).toEqual([])

    const stale = m.beginRequest()
    m.setPending([5])
    expect(m.reconcile(stale, { materialProtectionEnabled: false, protectedItemIds: [] })).toBe(false)
    expect(m.state.effective?.enabled).toBe(true)

    const forA = m.beginRequest()
    source.id = B
    expect(m.reconcile(forA, settings)).toBe(false)
    expect(m.state.effective).toBeNull()
    source.id = A
    expect(m.reconcile(forA, settings)).toBe(false)
  })

  it('treats omitted protection fields as no claim and leaves the committed preference alone', () => {
    const m = make()
    source.id = A
    m.setPending([2])
    m.commit()
    expect(m.reconcile(m.beginRequest(), {})).toBe(false)
    expect(m.reconcile(m.beginRequest(), { materialProtectionEnabled: true })).toBe(false)
    expect(m.state.effective).toBeNull()
    expect(m.state.committed).toEqual([2])
  })
})

describe('storage degradation and stored values', () => {
  const stored = (v: unknown) => storage.data.set(PREFERENCE_KEY_PREFIX + A, typeof v === 'string' ? v : JSON.stringify(v))

  it('sanitizes malformed id lists', () => {
    stored({ version: 1, protectedItemIds: [9, 3, 3, 0, -1, 2.5, '4'], enabled: true })
    const m = make()
    source.id = A
    expect(m.state.committed).toEqual([3, 9])
    expect(m.state.activation).toBe(true)
  })

  it.each([
    ['corrupt JSON', '{nope', 'corrupt'],
    ['an array', '[1]', 'corrupt'],
    ['an unsupported version', { version: 2, protectedItemIds: [1], enabled: true }, 'unsupported-version'],
    ['wrong field types', { version: 1, protectedItemIds: 'x', enabled: 'yes' }, 'corrupt']
  ])('ignores %s and stays usable', (_name, value, diagnostic) => {
    stored(value)
    const m = make()
    source.id = A
    expect(m.state.status).toBe('ready')
    expect(m.state.committed).toEqual([])
    expect(m.state.activation).toBe(false)
    expect(m.state.diagnostic).toBe(diagnostic)
  })

  it('degrades to memory when reads, writes and removes throw', () => {
    const throwing: PreferenceStorage = {
      getItem: () => {
        throw new Error('denied')
      },
      setItem: () => {
        throw new DOMException('full', 'QuotaExceededError')
      },
      removeItem: () => {
        throw new Error('denied')
      }
    }
    const m = make(throwing)
    source.id = A
    expect(m.state.status).toBe('ready')
    expect(m.state.diagnostic).toBe('read-failed')
    m.setPending([6])
    m.commit()
    expect(m.state.committed).toEqual([6])
    expect(m.state.diagnostic).toBe('quota-exceeded')
  })

  it('reports remove failures for discarded entries', () => {
    stored('{nope')
    const m = make({ getItem: (k) => storage.getItem(k), setItem: () => undefined, removeItem: () => { throw new Error('x') } })
    source.id = A
    expect(m.state.diagnostic).toBe('remove-failed')
  })

  it('handles a rejecting async read and an unavailable storage', async () => {
    const m = make({ getItem: () => Promise.reject(new Error('x')), setItem: () => undefined, removeItem: () => undefined })
    source.id = A
    await Promise.resolve()
    await Promise.resolve()
    expect(m.state.status).toBe('ready')
    expect(m.state.diagnostic).toBe('read-failed')
    const none = make(null)
    source.id = B
    none.setPending([1])
    none.commit()
    expect(none.state.committed).toEqual([1])
    expect(none.state.diagnostic).toBe('storage-unavailable')
  })
})

describe('integration with the account session', () => {
  let fetchCalls: string[]
  beforeEach(() => {
    resetAccountSession()
    installAccountSession()
    fetchCalls = []
    vi.stubGlobal('fetch', vi.fn(async (input: string) => {
      fetchCalls.push(input)
      return new Response(JSON.stringify({ accountId: input.endsWith('/session') ? currentId : undefined, accessContext: `ctx-${fetchCalls.length}`, expiresAt: '2099-01-01T00:00:00Z', grants: [] }), { status: 200 })
    }))
  })
  afterEach(() => {
    installAccountTransport(null)
    resetAccountSession()
    vi.unstubAllGlobals()
  })
  let currentId = A

  it('follows the verified account, issues no request itself and never stores secrets', async () => {
    const m = createMaterialProtectionState({ storage })
    created.push(m)
    currentId = A
    const first = connectKey('SECRET-KEY-A')
    expect(connection.accountId).toBeNull()
    await first
    await nextTick()
    expect(m.state.accountId).toBe(A)
    m.setPending([1])
    m.commit()
    const callsAfterProof = fetchCalls.length

    currentId = B
    const second = connectKey('SECRET-KEY-B')
    expect(m.state.accountId).toBeNull()
    expect(m.state.committed).toEqual([])
    await second
    expect(m.state.accountId).toBe(B)
    m.setPending([2])
    m.commit()
    m.reconcile(m.beginRequest(), { materialProtectionEnabled: true, protectedItemIds: [2] })
    expect(fetchCalls.length).toBe(callsAfterProof + 1)

    await disconnect()
    expect(m.state.status).toBe('uninitialized')
    const everything = [...storage.data.entries()].join('|')
    expect(everything).not.toContain('SECRET-KEY')
    expect(everything).not.toContain('ctx-')
    expect([...storage.data.keys()].sort()).toEqual([PREFERENCE_KEY_PREFIX + A, PREFERENCE_KEY_PREFIX + B])
  })

  it('keeps the partition on renewal of the same account and tolerates a missing accountId', async () => {
    const m = createMaterialProtectionState({ storage })
    created.push(m)
    currentId = A
    await connectKey('KEY')
    m.setPending([4])
    m.commit()
    await connectKey('KEY')
    expect(m.state.accountId).toBe(A)
    expect(m.state.committed).toEqual([4])
    currentId = undefined as unknown as string
    await connectKey('KEY2')
    expect(connection.phase).toBe('connected')
    expect(connection.accountId).toBeNull()
    expect(m.state.status).toBe('uninitialized')
  })
})
