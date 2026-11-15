import { ref } from 'vue'

/**
 * A small account-scoped navigation intent: "open Crafting Profit searching for this text". It carries no
 * authentication data, is never written to the URL and is consumed exactly once by the Profit screen.
 */
const intent = ref<{ accountId: string; text: string } | null>(null)

export function requestProfitSearch(accountId: string, text: string): void {
  intent.value = { accountId, text }
}

/** Returns and clears the pending search when it belongs to `accountId`; otherwise leaves it alone. */
export function takeProfitSearch(accountId: string | null): string | null {
  const pending = intent.value
  if (pending === null || accountId === null || pending.accountId !== accountId) return null
  intent.value = null
  return pending.text
}

export const pendingProfitSearch = intent
