import { computed, reactive, watch, type ComputedRef } from 'vue'
import { connection } from '@/api/accountSession'
import type { EffectiveSettings } from '@/api/types'

/**
 * The one owner of browser-side Material Protection state (STORY-MAT-002).
 *
 * It remembers, per backend-verified account GUID, the committed protected item IDs and the Profit
 * activation preference in browser-local storage, and keeps a separate pending selection for later
 * Bank/Materials UI. It enforces nothing and issues no request: calculation and protection stay in
 * the backend, whose echoed settings are the only source of the "effective" state.
 *
 * Partition key: the verified `accountId` only — never the API key, access context or generation.
 */

export const PREFERENCE_KEY_PREFIX = 'gw2.materialProtection.v1.'
export const PREFERENCE_SCHEMA_VERSION = 1

/** Minimal synchronous or asynchronous key-value port; `localStorage` satisfies it. */
export interface PreferenceStorage {
  getItem(key: string): string | null | Promise<string | null>
  setItem(key: string, value: string): void | Promise<void>
  removeItem(key: string): void | Promise<void>
}

/** Bounded, secret-free storage diagnostics. */
export type StorageDiagnostic =
  | 'storage-unavailable'
  | 'read-failed'
  | 'write-failed'
  | 'quota-exceeded'
  | 'remove-failed'
  | 'corrupt'
  | 'unsupported-version'

export type RestoreStatus = 'uninitialized' | 'loading' | 'ready'

interface StoredPreference {
  version: number
  protectedItemIds: number[]
  enabled: boolean
}

/** Correlates a calculation request with the account and edit state it was built from. */
export interface RequestToken {
  readonly accountId: string
  readonly editVersion: number
}

export interface EffectiveProtection {
  enabled: boolean
  protectedItemIds: number[]
}

/** Unique positive integers in numeric order; everything else is dropped. */
export function canonicalIds(values: unknown): number[] {
  if (!Array.isArray(values)) return []
  const seen = new Set<number>()
  for (const value of values) {
    if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) seen.add(value)
  }
  return [...seen].sort((a, b) => a - b)
}

function sameIds(a: readonly number[], b: readonly number[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index])
}

function isThenable<T>(value: T | Promise<T>): value is Promise<T> {
  return typeof (value as { then?: unknown } | null)?.then === 'function'
}

function browserStorage(): PreferenceStorage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export interface MaterialProtectionOptions {
  /** Defaults to `localStorage`; null/undefined-throwing storage degrades to memory. */
  storage?: PreferenceStorage | null
  /** The verified account GUID source; defaults to the connection state. */
  accountId?: () => string | null
}

export function createMaterialProtectionState(options: MaterialProtectionOptions = {}) {
  const storage = options.storage === undefined ? browserStorage() : options.storage
  const currentAccountId = options.accountId ?? (() => connection.accountId)

  const state = reactive({
    accountId: null as string | null,
    status: 'uninitialized' as RestoreStatus,
    committed: [] as number[],
    pending: [] as number[],
    activation: false,
    diagnostic: null as StorageDiagnostic | null,
    effective: null as EffectiveProtection | null
  })
  /** Bumped on every account change; guards delayed restores. */
  let epoch = 0
  /** Bumped on every local edit and account change; guards delayed canonical responses. */
  let editVersion = 0

  const dirty: ComputedRef<boolean> = computed(() => !sameIds(state.pending, state.committed))

  function keyFor(accountId: string): string {
    return PREFERENCE_KEY_PREFIX + accountId
  }

  function diagnose(error: unknown, fallback: StorageDiagnostic): StorageDiagnostic {
    const name = (error as { name?: string } | null)?.name
    return name === 'QuotaExceededError' || name === 'NS_ERROR_DOM_QUOTA_REACHED' ? 'quota-exceeded' : fallback
  }

  function parse(raw: string | null): { value: StoredPreference | null; diagnostic: StorageDiagnostic | null } {
    if (raw === null) return { value: null, diagnostic: null }
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      return { value: null, diagnostic: 'corrupt' }
    }
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return { value: null, diagnostic: 'corrupt' }
    }
    const record = parsed as Record<string, unknown>
    if (record.version !== PREFERENCE_SCHEMA_VERSION) return { value: null, diagnostic: 'unsupported-version' }
    return {
      value: {
        version: PREFERENCE_SCHEMA_VERSION,
        protectedItemIds: canonicalIds(record.protectedItemIds),
        enabled: record.enabled === true
      },
      diagnostic: Array.isArray(record.protectedItemIds) && typeof record.enabled === 'boolean' ? null : 'corrupt'
    }
  }

  /** Writes under the key of the account the caller captured, never "whatever is current". */
  function persist(accountId: string, ids: readonly number[], enabled: boolean): void {
    if (storage === null) {
      state.diagnostic = 'storage-unavailable'
      return
    }
    const value: StoredPreference = { version: PREFERENCE_SCHEMA_VERSION, protectedItemIds: [...ids], enabled }
    const fail = (error: unknown): void => {
      if (state.accountId === accountId) state.diagnostic = diagnose(error, 'write-failed')
    }
    try {
      const result = storage.setItem(keyFor(accountId), JSON.stringify(value))
      if (isThenable(result)) result.catch(fail)
    } catch (error) {
      fail(error)
    }
  }

  function discardStored(accountId: string): void {
    if (storage === null) return
    const fail = (): void => {
      if (state.accountId === accountId) state.diagnostic = 'remove-failed'
    }
    try {
      const result = storage.removeItem(keyFor(accountId))
      if (isThenable(result)) result.catch(fail)
    } catch {
      fail()
    }
  }

  function reset(): void {
    state.accountId = null
    state.status = 'uninitialized'
    state.committed = []
    state.pending = []
    state.activation = false
    state.diagnostic = null
    state.effective = null
  }

  function apply(accountId: string, raw: string | null, readError: unknown, failed: boolean): void {
    state.accountId = accountId
    if (failed) {
      state.diagnostic = diagnose(readError, 'read-failed')
    } else {
      const { value, diagnostic } = parse(raw)
      state.diagnostic = diagnostic
      if (value !== null) {
        state.committed = value.protectedItemIds
        state.activation = value.enabled
      }
      if (diagnostic === 'unsupported-version' || (diagnostic === 'corrupt' && value === null)) {
        discardStored(accountId)
      }
    }
    state.pending = [...state.committed]
    state.status = 'ready'
  }

  function accountChanged(accountId: string | null): void {
    epoch += 1
    editVersion += 1
    const token = epoch
    reset()
    if (accountId === null) return
    state.accountId = accountId
    state.status = 'loading'
    if (storage === null) {
      apply(accountId, null, null, false)
      state.diagnostic = 'storage-unavailable'
      return
    }
    const current = (): boolean => token === epoch && currentAccountId() === accountId
    let read: string | null | Promise<string | null>
    try {
      read = storage.getItem(keyFor(accountId))
    } catch (error) {
      apply(accountId, null, error, true)
      return
    }
    if (isThenable(read)) {
      read.then(
        (raw) => {
          if (current()) apply(accountId, raw, null, false)
        },
        (error) => {
          if (current()) apply(accountId, null, error, true)
        }
      )
    } else {
      apply(accountId, read, null, false)
    }
  }

  const stop = watch(currentAccountId, accountChanged, { flush: 'sync', immediate: true })

  function ready(): string | null {
    return state.status === 'ready' && state.accountId !== null ? state.accountId : null
  }

  /** Replaces the pending selection; committed storage is untouched. */
  function setPending(ids: readonly number[]): void {
    if (ready() === null) return
    editVersion += 1
    state.pending = canonicalIds([...ids])
  }

  function togglePending(itemId: number): void {
    if (ready() === null) return
    const next = new Set(state.pending)
    if (next.has(itemId)) next.delete(itemId)
    else next.add(itemId)
    setPending([...next])
  }

  function discardPending(): void {
    if (ready() === null) return
    editVersion += 1
    state.pending = [...state.committed]
  }

  /** Explicit confirmation: the canonical pending selection becomes the committed one and is stored. */
  function commit(): void {
    const accountId = ready()
    if (accountId === null) return
    editVersion += 1
    state.committed = canonicalIds(state.pending)
    state.pending = [...state.committed]
    persist(accountId, state.committed, state.activation)
  }

  /** The stored Profit activation preference. */
  function setActivation(enabled: boolean): void {
    const accountId = ready()
    if (accountId === null) return
    editVersion += 1
    state.activation = enabled
    persist(accountId, state.committed, enabled)
  }

  /** Captures the account and edit state a later request is built from; null before restoration. */
  function beginRequest(): RequestToken | null {
    const accountId = ready()
    return accountId === null ? null : { accountId, editVersion }
  }

  /**
   * Applies the backend's echoed settings to the effective state, only if the account and edit state
   * are still those the request was built from. Omitted fields (older backend) change nothing.
   * The committed browser preference is never overwritten.
   */
  function reconcile(token: RequestToken | null, settings: Partial<EffectiveSettings> | null | undefined): boolean {
    if (token === null || settings == null) return false
    if (token.accountId !== state.accountId || token.editVersion !== editVersion || ready() === null) return false
    if (typeof settings.materialProtectionEnabled !== 'boolean' || !Array.isArray(settings.protectedItemIds)) {
      return false
    }
    state.effective = {
      enabled: settings.materialProtectionEnabled,
      protectedItemIds: canonicalIds(settings.protectedItemIds)
    }
    return true
  }

  return {
    state,
    dirty,
    setPending,
    togglePending,
    discardPending,
    commit,
    setActivation,
    beginRequest,
    reconcile,
    dispose: stop
  }
}

export type MaterialProtectionState = ReturnType<typeof createMaterialProtectionState>

let shared: MaterialProtectionState | null = null

/** The application-wide instance every Material Protection consumer must share. */
export function useMaterialProtection(): MaterialProtectionState {
  shared ??= createMaterialProtectionState()
  return shared
}

/** Test support. */
export function resetMaterialProtection(): void {
  shared?.dispose()
  shared = null
}
