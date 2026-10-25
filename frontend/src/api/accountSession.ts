import { reactive } from 'vue'
import {
  API_BASE_PATH,
  ApiRequestError,
  installAccountTransport,
  toRequestError,
  UNREACHABLE_CODE,
  type AccountRequestTicket,
  type AccountTransport
} from './http'

/**
 * The browser side of account access (`TARGET_ARCHITECTURE.md` 8.1, 8.2, UD-018).
 *
 * The only holder of the GW2 API key: it is kept in `sessionStorage`, so navigation and reloads do
 * not ask for it again, and is sent to the backend only as the `X-GW2-API-Key` header of a proof or
 * of account work that needs it — never in a URL, a log line or an error message. The backend turns a
 * proven key into an HttpOnly session cookie and an access context; this module renews that lease
 * from the retained key when it lapses.
 *
 * `generation` is the small request guard: it changes whenever the connected account may have changed
 * (connect, replace, disconnect, definitive rejection). Every account request records it when it
 * starts, and an answer that arrives under a different one is discarded by the transport.
 */
export const STORAGE_KEY = 'gw2.apiKey'

/** The lease is renewed this long before the backend would expire it. */
const RENEW_MARGIN_MS = 60_000

export type ConnectionPhase = 'disconnected' | 'restoring' | 'connecting' | 'connected' | 'unavailable'

export interface ConnectionState {
  phase: ConnectionPhase
  /** Why the last attempt failed, in words for the person; never contains the key. */
  error: string | null
  /** True when the failure was definitive (key refused), false when retrying may help. */
  errorIsDefinitive: boolean
  /** Set after a disconnect whose server-side logout did not succeed. */
  logoutNotice: string | null
  generation: number
  grants: readonly string[]
}

export const connection: ConnectionState = reactive({
  phase: 'disconnected',
  error: null,
  errorIsDefinitive: false,
  logoutNotice: null,
  generation: 0,
  grants: []
})

export const KEY_REQUIRED_CODE = 'KEY_REQUIRED'

interface Lease {
  accessContext: string
  expiresAtMs: number
}

let lease: Lease | null = null
let memoryKey: string | null = null
let inflight: Promise<void> | null = null

/* ------------------------------------------------------------------------- key storage --- */

function readKey(): string | null {
  if (memoryKey !== null) return memoryKey
  try {
    return window.sessionStorage.getItem(STORAGE_KEY) ?? memoryKey
  } catch {
    return memoryKey
  }
}

function writeKey(key: string): void {
  memoryKey = key
  try {
    window.sessionStorage.setItem(STORAGE_KEY, key)
  } catch {
    /* storage unavailable: the key lives for this page only */
  }
}

function removeKey(): void {
  memoryKey = null
  try {
    window.sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    /* nothing stored */
  }
}

/** Whether a key is retained for this browser session. */
export function hasStoredKey(): boolean {
  return readKey() !== null
}

/* ------------------------------------------------------------------------------ proof --- */

interface Proven {
  accessContext: string
  expiresAt: string
  grants: string[]
}

async function proveKey(key: string): Promise<Proven> {
  let response: Response
  try {
    response = await fetch(`${API_BASE_PATH}/session`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'X-GW2-API-Key': key }
    })
  } catch {
    throw new ApiRequestError('The backend could not be reached.', UNREACHABLE_CODE, null)
  }
  if (!response.ok) throw await toRequestError(response)
  const body = (await response.json()) as Partial<Proven>
  if (typeof body.accessContext !== 'string' || typeof body.expiresAt !== 'string') {
    throw new ApiRequestError('The backend answered the key check unexpectedly.', 'UNEXPECTED_STATUS', response.status)
  }
  return { accessContext: body.accessContext, expiresAt: body.expiresAt, grants: body.grants ?? [] }
}

function isDefinitiveRejection(error: unknown): boolean {
  return error instanceof ApiRequestError && error.code === 'INVALID_CREDENTIAL'
}

function describeProofFailure(error: unknown): string {
  if (isDefinitiveRejection(error)) {
    return 'The GW2 API key was not accepted. Check that it is a valid key with the account permission.'
  }
  if (error instanceof ApiRequestError && error.code === 'PROOF_UNAVAILABLE') {
    return 'Guild Wars 2 could not check the key right now. The key is kept; try again shortly.'
  }
  if (error instanceof ApiRequestError && error.code === UNREACHABLE_CODE) {
    return 'The backend could not be reached. The key is kept; try again.'
  }
  if (error instanceof ApiRequestError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : String(error)
}

function adopt(proven: Proven): void {
  lease = { accessContext: proven.accessContext, expiresAtMs: Date.parse(proven.expiresAt) }
  connection.grants = proven.grants
}

/** The key was refused for good: forget it and ask for a new one. */
function reject(error: unknown): void {
  removeKey()
  lease = null
  connection.generation += 1
  connection.phase = 'disconnected'
  connection.error = describeProofFailure(error)
  connection.errorIsDefinitive = true
  connection.grants = []
}

/**
 * Proves the retained key and installs the lease. One proof at a time: concurrent callers share it.
 * A definitive rejection discards the key; any other failure keeps it so the user can retry.
 */
function establish(restoring: boolean): Promise<void> {
  if (inflight !== null) return inflight
  const key = readKey()
  if (key === null) return Promise.reject(keyRequired())
  const hadLease = connection.phase === 'connected'
  const generation = connection.generation
  if (!hadLease) connection.phase = restoring ? 'restoring' : 'connecting'
  const run = (async () => {
    try {
      const proven = await proveKey(key)
      if (generation !== connection.generation || readKey() !== key) return
      adopt(proven)
      if (!hadLease) connection.generation += 1
      connection.phase = 'connected'
      connection.error = null
      connection.errorIsDefinitive = false
    } catch (error) {
      if (generation !== connection.generation || readKey() !== key) return
      if (isDefinitiveRejection(error)) {
        reject(error)
      } else if (!(hadLease && leaseIsLive())) {
        lease = null
        connection.phase = 'unavailable'
        connection.error = describeProofFailure(error)
        connection.errorIsDefinitive = false
      }
      throw error
    }
  })()
  const pending = run.finally(() => {
    if (inflight === pending) inflight = null
  })
  inflight = pending
  // A caller that only wants the side effect must not leave an unhandled rejection behind.
  inflight.catch(() => undefined)
  return inflight
}

function leaseIsLive(): boolean {
  return lease !== null && lease.expiresAtMs > Date.now()
}

function keyRequired(): ApiRequestError {
  return new ApiRequestError('Connect a GW2 API key to use account data.', KEY_REQUIRED_CODE, null)
}

/* ------------------------------------------------------------------------ public API --- */

/** Proves a key retained from earlier in this browser session, if there is one. */
export async function restoreConnection(): Promise<void> {
  if (connection.phase !== 'disconnected' || readKey() === null) return
  try {
    await establish(true)
  } catch {
    /* the state already says why */
  }
}

/** Retries after a transient failure, keeping the retained key. */
export async function retryConnection(): Promise<void> {
  if (connection.phase !== 'unavailable') return
  try {
    await establish(true)
  } catch {
    /* the state already says why */
  }
}

/**
 * Connects or replaces the account, clearing old account state before proof. Transient failures keep
 * the candidate for retry; definitive rejection forgets it. Late proof replies cannot reconnect a
 * disconnected or replaced account.
 */
export async function connectKey(rawKey: string): Promise<boolean> {
  const key = rawKey.trim()
  if (key === '') {
    connection.error = 'Enter a GW2 API key.'
    connection.errorIsDefinitive = true
    return false
  }
  if (/\s/.test(key) || key.length > 200) {
    connection.error = 'That does not look like a GW2 API key.'
    connection.errorIsDefinitive = true
    return false
  }
  const generation = ++connection.generation
  inflight = null
  lease = null
  connection.grants = []
  writeKey(key)
  connection.phase = 'connecting'
  connection.error = null
  connection.logoutNotice = null
  try {
    const proven = await proveKey(key)
    if (generation !== connection.generation) return false
    adopt(proven)
    connection.generation += 1
    connection.phase = 'connected'
    connection.errorIsDefinitive = false
    return true
  } catch (error) {
    if (generation !== connection.generation) return false
    connection.error = describeProofFailure(error)
    connection.errorIsDefinitive = isDefinitiveRejection(error)
    if (connection.errorIsDefinitive) removeKey()
    connection.phase = connection.errorIsDefinitive ? 'disconnected' : 'unavailable'
    return false
  }
}

/**
 * Forgets the key and every account-derived thing at once, offline or not, then asks the backend to
 * end its session. A failed logout is reported; it is never presented as success.
 */
export async function disconnect(): Promise<void> {
  const context = lease?.accessContext ?? null
  removeKey()
  lease = null
  connection.generation += 1
  inflight = null
  connection.phase = 'disconnected'
  connection.error = null
  connection.errorIsDefinitive = false
  connection.grants = []
  connection.logoutNotice = null
  if (context === null) return
  try {
    const response = await fetch(`${API_BASE_PATH}/session/logout`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'X-Account-Context': context }
    })
    if (!response.ok && response.status !== 401) throw new Error(`HTTP ${response.status}`)
  } catch {
    connection.logoutNotice =
      'Disconnected here, but the server session could not be ended; it expires on its own within minutes.'
  }
}

/* ------------------------------------------------------------------------- transport --- */

const transport: AccountTransport = {
  async begin(sendKey: boolean): Promise<AccountRequestTicket> {
    if (inflight !== null) await inflight.catch(() => undefined)
    if (connection.phase === 'disconnected') throw keyRequired()
    if (connection.phase === 'unavailable' || connection.phase === 'restoring' || connection.phase === 'connecting') {
      await establish(true)
    } else if (lease === null || lease.expiresAtMs - Date.now() < RENEW_MARGIN_MS) {
      try {
        await establish(false)
      } catch (error) {
        if (!leaseIsLive()) throw error
      }
    }
    const key = readKey()
    if (lease === null || key === null) throw keyRequired()
    const headers: Record<string, string> = { 'X-Account-Context': lease.accessContext }
    if (sendKey) headers['X-GW2-API-Key'] = key
    return { generation: connection.generation, headers }
  },

  isCurrent(ticket: AccountRequestTicket): boolean {
    return ticket.generation === connection.generation && connection.phase !== 'disconnected'
  },

  async recover(ticket: AccountRequestTicket, error: ApiRequestError): Promise<boolean> {
    if (!transport.isCurrent(ticket)) return false
    if (error.code === 'INVALID_CREDENTIAL') {
      reject(error)
      return false
    }
    // A lapsed lease (401) or a context another tab replaced in the shared cookie (403): prove the
    // retained key again and let the caller repeat the request once.
    const lapsed = error.status === 401 && error.code === 'SESSION_REQUIRED'
    const mismatched = error.status === 403 &&
      (error.code === 'ACCOUNT_ACCESS_DENIED' || error.code === 'ACCESS_CONTEXT_MISMATCH')
    if (!lapsed && !mismatched) return false
    lease = null
    await establish(false)
    return transport.isCurrent(ticket)
  }
}

/** Wires the session into the HTTP transport. Called once by the application entry point. */
export function installAccountSession(): void {
  installAccountTransport(transport)
}

/** Test support: back to a pristine, disconnected module without touching the transport. */
export function resetAccountSession(): void {
  removeKey()
  lease = null
  inflight = null
  connection.phase = 'disconnected'
  connection.error = null
  connection.errorIsDefinitive = false
  connection.logoutNotice = null
  connection.generation = 0
  connection.grants = []
}
