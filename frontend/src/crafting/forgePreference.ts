import { ref } from 'vue'
import { connection, registerDeletionCleanup } from '@/api/accountSession'

/**
 * Browser-local "Allow Mystic Forge recipes" preference, one record per backend-verified account GUID.
 * It is a request input only: calculation, Forge catalog and eligibility stay in the backend. Absent,
 * corrupt or unsupported records read as `false`; nothing secret (no API key) is stored.
 */
export const FORGE_PREFERENCE_KEY_PREFIX = 'gw2.craftingForge.v1.'
const SCHEMA_VERSION = 1

export interface ForgePreferenceStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem?(key: string): void
}

function browserStorage(): ForgePreferenceStorage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export function createForgePreference(options: {
  storage?: ForgePreferenceStorage | null
  accountId?: () => string | null
} = {}) {
  const storage = options.storage === undefined ? browserStorage() : options.storage
  const currentAccountId = options.accountId ?? (() => connection.accountId)
  /** Latest committed value per account for this page: wins over storage so a failed write never reverts. */
  const shadow = new Map<string, boolean>()
  /** Bumped on every write so computed/watch consumers recompute. */
  const version = ref(0)

  function read(accountId: string): boolean {
    const known = shadow.get(accountId)
    if (known !== undefined) return known
    if (storage === null) return false
    try {
      const parsed: unknown = JSON.parse(storage.getItem(FORGE_PREFERENCE_KEY_PREFIX + accountId) ?? 'null')
      if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
        const record = parsed as Record<string, unknown>
        if (record.version === SCHEMA_VERSION && record.allowMysticForge === true) return true
      }
    } catch {
      // Invalid or unreadable storage means the default (off), never another account's value.
    }
    return false
  }

  return {
    /**
     * Account-data deletion: removes only this GUID's stored record and memory shadow (other accounts are kept).
     * Disconnecting alone never calls this, and the in-memory shadow is retained as explicit false even if storage cannot be written.
     */
    forgetAccount(accountId: string): void {
      // Explicit false memory state: a stale durable `true` that cannot be removed must never be re-read.
      shadow.set(accountId, false)
      const key = FORGE_PREFERENCE_KEY_PREFIX + accountId
      let removed = false
      try {
        if (storage?.removeItem) {
          storage.removeItem(key)
          removed = true
        }
      } catch {
        // Blocked removal: fall through to overwriting the durable record with false.
      }
      if (!removed) {
        try {
          storage?.setItem(key, JSON.stringify({ version: SCHEMA_VERSION, allowMysticForge: false }))
        } catch {
          // Fully blocked storage: the in-page false shadow keeps the preference off.
        }
      }
      version.value++
    },
    /** The preference of the CURRENT verified account; false while no account is verified. */
    current(): boolean {
      void version.value
      const accountId = currentAccountId()
      return accountId === null ? false : read(accountId)
    },
    /** Saves for the account captured now (never "whatever is current later"). Returns whether it changed. */
    set(allow: boolean): boolean {
      const accountId = currentAccountId()
      if (accountId === null || read(accountId) === allow) return false
      shadow.set(accountId, allow)
      try {
        storage?.setItem(FORGE_PREFERENCE_KEY_PREFIX + accountId,
          JSON.stringify({ version: SCHEMA_VERSION, allowMysticForge: allow }))
      } catch {
        // Quota/blocked storage: the page keeps the committed value in memory.
      }
      version.value++
      return true
    }
  }
}

export type ForgePreference = ReturnType<typeof createForgePreference>

let shared: ForgePreference | null = null
/** The page-wide instance (browser storage + connection account). */
export function useForgePreference(): ForgePreference {
  return (shared ??= createForgePreference())
}

// Successful account-data deletion removes this GUID's secret-free preference (same authority as material protection).
registerDeletionCleanup(accountId => useForgePreference().forgetAccount(accountId))
