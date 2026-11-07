import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive } from 'vue'
import {
  PREFERENCE_KEY_PREFIX,
  canonicalIds,
  createMaterialProtectionState,
  resetMaterialProtection,
  useMaterialProtection,
  type MaterialProtectionState,
  type PreferenceStorage
} from '../materialProtectionState'
import { connectKey, connection, disconnect, installAccountSession, resetAccountSession } from '@/api/accountSession'
import { getJson, installAccountTransport } from '@/api/http'

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
  it('never shows Account A values under Account B while the replacement proof is pending', () => {
    const m = make()
    source.id = A
    m.setPending([1, 2])
    m.commit()
    source.id = null
    expect(m.state.committed).toEqual([])
    expect(m.state.status).toBe('uninitialized')
    source.id = B
    expect(m.state.committed).toEqual([])
    m.setPending([9])
    m.commit()
    expect(JSON.parse(storage.data.get(PREFERENCE_KEY_PREFIX + A)!).protectedItemIds).toEqual([1, 2])
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

  it('keeps working with unavailable storage', () => {
    const none = make(null)
    source.id = B
    none.setPending([1])
    none.commit()
    expect(none.state.committed).toEqual([1])
    expect(none.state.diagnostic).toBe('storage-unavailable')
  })
})

describe('per-page committed shadow', () => {
  it('restores each account\'s latest committed values with unavailable storage', () => {
    const m = make(null)
    source.id = A
    m.setPending([4, 2])
    m.commit()
    m.setActivation(true)
    m.setPending([99])
    source.id = B
    expect(m.state.committed).toEqual([])
    expect(m.state.activation).toBe(false)
    m.setPending([8])
    m.commit()
    source.id = A
    expect(m.state.committed).toEqual([2, 4])
    expect(m.state.activation).toBe(true)
    expect(m.state.pending).toEqual([2, 4])
    source.id = null
    source.id = B
    expect(m.state.committed).toEqual([8])
    expect(m.state.activation).toBe(false)
  })

  it('prefers the latest committed value over an older stored record after a failed write', () => {
    storage.data.set(PREFERENCE_KEY_PREFIX + A, JSON.stringify({ version: 1, protectedItemIds: [1], enabled: false }))
    let failWrites = false
    const flaky: PreferenceStorage = {
      getItem: (k) => storage.getItem(k),
      setItem: (k, v) => {
        if (failWrites) throw new Error('full')
        storage.setItem(k, v)
      },
      removeItem: (k) => storage.removeItem(k)
    }
    const m = make(flaky)
    source.id = A
    expect(m.state.committed).toEqual([1])
    failWrites = true
    m.setPending([1, 7])
    m.commit()
    m.setActivation(true)
    expect(m.state.diagnostic).toBe('write-failed')
    source.id = B
    expect(m.state.committed).toEqual([])
    source.id = A
    expect(m.state.committed).toEqual([1, 7])
    expect(m.state.activation).toBe(true)
  })

  it('uses the shadow when a later read fails, without committing pending edits', () => {
    let failReads = false
    const flaky: PreferenceStorage = {
      getItem: (k) => {
        if (failReads) throw new Error('denied')
        return storage.getItem(k)
      },
      setItem: (k, v) => storage.setItem(k, v),
      removeItem: (k) => storage.removeItem(k)
    }
    const m = make(flaky)
    source.id = A
    m.setPending([3])
    m.commit()
    m.setPending([3, 5])
    failReads = true
    source.id = null
    source.id = A
    expect(m.state.diagnostic).toBe('read-failed')
    expect(m.state.committed).toEqual([3])
    expect(m.state.pending).toEqual([3])
  })
})

describe('newest-request and shared-lifetime regressions', () => {
  const full = (ids: number[]) => ({ materialProtectionEnabled: true, protectedItemIds: ids })

  it('accepts only the newest of two requests started in the same account and edit state', () => {
    const m = make()
    source.id = A
    const older = m.beginRequest()
    const newer = m.beginRequest()
    expect(m.reconcile(newer, full([2]))).toBe(true)
    expect(m.reconcile(older, full([9]))).toBe(false)
    expect(m.state.effective?.protectedItemIds).toEqual([2])

    const first = m.beginRequest()
    m.beginRequest()
    expect(m.reconcile(first, full([7]))).toBe(false)
    expect(m.state.effective?.protectedItemIds).toEqual([2])
  })

  it('clears effective protection on a current response with missing fields, ignores a stale one', () => {
    const m = make()
    source.id = A
    m.setPending([1])
    m.commit()
    expect(m.reconcile(m.beginRequest(), full([1]))).toBe(true)
    const stale = m.beginRequest()
    const current = m.beginRequest()
    expect(m.reconcile(stale, {})).toBe(false)
    expect(m.state.effective).not.toBeNull()
    expect(m.reconcile(current, {})).toBe(false)
    expect(m.state.effective).toBeNull()
    expect(m.state.committed).toEqual([1])

    expect(m.reconcile(m.beginRequest(), full([1]))).toBe(true)
    expect(m.reconcile(m.beginRequest(), null)).toBe(false)
    expect(m.state.effective).toBeNull()
    expect(m.state.committed).toEqual([1])
  })

  it('keeps following the account after the first consumer component unmounts', () => {
    resetMaterialProtection()
    // The shared instance reads the real connection; emulate it with a first use inside a component.
    const Consumer = defineComponent({ setup() { useMaterialProtection(); return () => h('div') } })
    const app = createApp(Consumer)
    app.mount(document.createElement('div'))
    const shared = useMaterialProtection()
    app.unmount()
    connection.accountId = A
    expect(shared.state.accountId).toBe(A)
    shared.setPending([4])
    shared.commit()
    connection.accountId = B
    expect(shared.state.accountId).toBe(B)
    expect(shared.state.committed).toEqual([])
    expect(shared.state.pending).toEqual([])
    shared.setPending([8])
    shared.commit()
    const written = [...Object.keys(window.localStorage)].filter((k) => k.startsWith(PREFERENCE_KEY_PREFIX))
    expect(window.localStorage.getItem(PREFERENCE_KEY_PREFIX + A)).toContain('[4]')
    expect(window.localStorage.getItem(PREFERENCE_KEY_PREFIX + B)).toContain('[8]')
    expect(written.length).toBe(2)
    resetMaterialProtection()
    expect(shared.state.accountId).toBeNull()
    connection.accountId = A
    expect(shared.state.accountId).toBeNull()
    connection.accountId = null
    window.localStorage.clear()
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

  it('preserves committed and pending state across genuine same-account lease renewal', async () => {
    const m = createMaterialProtectionState({ storage })
    created.push(m)
    currentId = A
    let sessions = 0
    let reads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string) => {
      fetchCalls.push(input)
      if (input.endsWith('/session')) {
        sessions += 1
        return new Response(JSON.stringify({ accountId: A, accessContext: `ctx-${sessions}`, expiresAt: '2099-01-01T00:00:00Z', grants: [] }), { status: 200 })
      }
      reads += 1
      return reads === 1
        ? new Response(JSON.stringify({ error: 'SESSION_REQUIRED', message: 'Prove' }), { status: 401, headers: { 'Content-Type': 'application/json' } })
        : new Response(JSON.stringify({ ok: 1 }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    await connectKey('KEY')
    m.setPending([4])
    m.commit()
    m.setPending([4, 6])
    await expect(getJson('/account/bank')).resolves.toEqual({ ok: 1 })
    expect(sessions).toBe(2)
    expect(m.state.accountId).toBe(A)
    expect(m.state.committed).toEqual([4])
    expect(m.state.pending).toEqual([4, 6])
  })

  it('keeps the partition on explicit replacement with the same account and tolerates a missing accountId', async () => {
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
