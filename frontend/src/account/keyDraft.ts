import { ref, watch } from 'vue'
import { connectKey, connection } from '@/api/accountSession'

/**
 * The typed (never stored) API-key draft and whether it is shown as text. This presentation state
 * outlives the panel's own remount: a connect attempt bumps the account generation before it
 * completes, which re-creates the workspace, and a failed attempt must still leave the text for
 * correction. It is cleared when the attempt is accepted, on Disconnect, on any account change that
 * did not come from this draft's own attempt, and when the panel is closed without an attempt running.
 */
export const draft = ref('')
export const showDraft = ref(false)

let attempting = false

export function clearDraft(): void {
  draft.value = ''
  showDraft.value = false
}

export function isAttempting(): boolean {
  return attempting
}

// An account change that is not this draft's own attempt discards the typed text.
watch(
  () => connection.generation,
  () => {
    if (!attempting) clearDraft()
  },
  { flush: 'sync' }
)

export async function submitDraft(): Promise<void> {
  attempting = true
  const pending = connectKey(draft.value)
  // connectKey bumps the generation synchronously; later movement is an external change.
  const own = connection.generation
  let accepted = false
  try {
    accepted = await pending
  } finally {
    attempting = false
  }
  if (accepted || connection.generation !== own) clearDraft()
}
