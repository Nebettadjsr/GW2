/**
 * Same-origin tab invalidation after an account-data deletion. The payload carries only the deleted
 * account GUID — never the key, a session identifier or an access context.
 */
export const DELETION_CHANNEL = 'gw2.accountDataDeleted'
export const DELETION_STORAGE_SIGNAL = 'gw2.accountDataDeleted.signal'

export interface DeletionSignal {
  type: 'account-data-deleted'
  accountId: string
}

type Listener = (accountId: string) => void
const listeners: Listener[] = []
let channel: BroadcastChannel | null = null
let installed = false

function dispatch(data: unknown): void {
  const signal = data as Partial<DeletionSignal> | null
  if (signal === null || typeof signal !== 'object' || signal.type !== 'account-data-deleted') return
  if (typeof signal.accountId !== 'string' || signal.accountId === '') return
  for (const listener of listeners) listener(signal.accountId)
}

function install(): void {
  if (installed || typeof window === 'undefined') return
  installed = true
  if (typeof BroadcastChannel !== 'undefined') {
    channel = new BroadcastChannel(DELETION_CHANNEL)
    channel.onmessage = (event: MessageEvent) => dispatch(event.data)
  } else {
    window.addEventListener('storage', (event: StorageEvent) => {
      if (event.key !== DELETION_STORAGE_SIGNAL || event.newValue === null) return
      try {
        dispatch(JSON.parse(event.newValue))
      } catch {
        /* ignore malformed signal */
      }
    })
  }
}

export function onDeletionSignal(listener: Listener): void {
  listeners.push(listener)
  install()
}

export function publishDeletionSignal(accountId: string): void {
  install()
  const signal: DeletionSignal = { type: 'account-data-deleted', accountId }
  if (channel !== null) {
    channel.postMessage(signal)
    return
  }
  try {
    window.localStorage.setItem(DELETION_STORAGE_SIGNAL, JSON.stringify({ ...signal, nonce: Date.now() }))
    window.localStorage.removeItem(DELETION_STORAGE_SIGNAL)
  } catch {
    /* storage unavailable: the backend outcome still invalidates other tabs */
  }
}
