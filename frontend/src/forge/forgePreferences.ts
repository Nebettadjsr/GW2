import { registerDeletionCleanup } from '@/api/accountSession'
import type { ForgeSettings } from '@/api/types'

export const DEFAULT_FORGE_SETTINGS: ForgeSettings = {
  priceMode: 'INSTANT_BUY', useOwn: false, valueOwn: false, daily: 'CRAFT', promotions: false, reduce: 'NO'
}

const KEY_PREFIX = 'gw2.mysticForge.settings.v1.'

/** One set of choices for all Forge recipes, partitioned by the backend-verified account id. */
export function readForgePreferences(accountId: string | null): { settings: ForgeSettings; unavailable: boolean } {
  const defaults = { ...DEFAULT_FORGE_SETTINGS }
  if (accountId === null) return { settings: defaults, unavailable: false }
  let stored: string | null
  try { stored = window.localStorage.getItem(KEY_PREFIX + accountId) }
  catch { return { settings: defaults, unavailable: true } }
  if (stored === null) return { settings: defaults, unavailable: false }
  let value: unknown
  try { value = JSON.parse(stored) }
  catch { return { settings: defaults, unavailable: false } }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return { settings: defaults, unavailable: false }
  const fields = value as Record<string, unknown>
  const reduce = fields.reduce
  return {
    settings: {
      priceMode: fields.priceMode === 'BUY_ORDER' ? 'BUY_ORDER' : 'INSTANT_BUY',
      useOwn: fields.useOwn === true,
      valueOwn: fields.valueOwn === true,
      daily: fields.daily === 'BUY' ? 'BUY' : 'CRAFT',
      promotions: fields.promotions === true,
      reduce: reduce === 'YES' || reduce === 'TRADABLE' || reduce === 'UNTRADABLE' ? reduce : 'NO'
    },
    unavailable: false
  }
}

export function saveForgePreferences(accountId: string | null, settings: ForgeSettings): boolean {
  if (accountId === null) return true
  try {
    window.localStorage.setItem(KEY_PREFIX + accountId, JSON.stringify(settings))
    return true
  } catch { return false }
}

/** Removes only this account's saved choices; a storage failure never breaks the deletion and reports false. */
export function forgetForgePreferences(accountId: string): boolean {
  try {
    window.localStorage.removeItem(KEY_PREFIX + accountId)
    return true
  } catch { return false }
}

// Account-data deletion also clears this GUID's saved Forge choices (STORY-FORGE-002).
registerDeletionCleanup(accountId => { forgetForgePreferences(accountId) })
