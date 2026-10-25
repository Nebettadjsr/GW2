import type { ApiErrorBody } from './types'

/**
 * Minimal `fetch` wrapper for the backend HTTP API.
 *
 * The base path is relative by default, so the browser calls the same origin it was served from —
 * the dev server and any deployment proxy forward `/api` to the backend. No other host is ever
 * contacted from the browser. The GW2 key is held by the account session module, never here.
 */
export const API_BASE_PATH: string = import.meta.env.VITE_API_BASE_PATH ?? '/api'

/** A mapped backend failure, carrying the backend's own error code where it supplied one. */
export class ApiRequestError extends Error {
  readonly code: string
  readonly status: number | null
  readonly taskStatusUrl: string | null

  constructor(message: string, code: string, status: number | null, taskStatusUrl: string | null = null) {
    super(message)
    this.name = 'ApiRequestError'
    this.code = code
    this.status = status
    this.taskStatusUrl = taskStatusUrl
  }
}

/** Code used when the request never reached the backend (offline, DNS, proxy down, abort). */
export const UNREACHABLE_CODE = 'BACKEND_UNREACHABLE'
/** Code used when the backend answered with a status but no readable error body. */
export const UNEXPECTED_STATUS_CODE = 'UNEXPECTED_STATUS'

export async function getJson<T>(path: string): Promise<T> {
  return sendJson<T>(path, { method: 'GET', headers: { Accept: 'application/json' } })
}

export async function postJson<T>(path: string, body: unknown): Promise<T> {
  return sendJson<T>(path, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
}

async function sendJson<T>(path: string, init: RequestInit): Promise<T> {
  return sendJsonAttempt<T>(path, init, true)
}

/**
 * The account-session hook (`TARGET_ARCHITECTURE.md` 8.2). It is installed once by the application
 * entry point, so the session lifecycle stays in one module and this transport only asks it three
 * things: headers for an account request, whether the answer still belongs to the current account,
 * and whether a refused request is worth one retry after re-proving the retained key.
 */
export interface AccountRequestTicket {
  readonly generation: number
  readonly headers: Record<string, string>
}

export interface AccountTransport {
  begin(sendKey: boolean): Promise<AccountRequestTicket>
  isCurrent(ticket: AccountRequestTicket): boolean
  /** Handles a refused request; resolves true when the caller may repeat it once. */
  recover(ticket: AccountRequestTicket, error: ApiRequestError): Promise<boolean>
}

/** Code of an answer discarded because the account changed while its request was in flight. */
export const ACCOUNT_CHANGED_CODE = 'ACCOUNT_CHANGED'

let accountTransport: AccountTransport | null = null

export function installAccountTransport(transport: AccountTransport | null): void {
  accountTransport = transport
}

/** Account-scoped backend routes; global routes (game data, prices, icons) carry no account. */
function isAccountPath(path: string): boolean {
  const route = path.split('?')[0] ?? path
  return route.startsWith('/account/') || route.startsWith('/crafting/profit') ||
    route.startsWith('/crafting/discovery') || route === '/crafting/selector-options' ||
    route.startsWith('/sync/account') || route.startsWith('/sync/tasks/') || route === '/system/status'
}

/**
 * Only an explicit account refresh trigger needs the key itself. Calculations (Profit, Discovery,
 * detail) are keyless cached reads under a live session; the backend reports a needed refresh
 * instead of re-proving a key.
 */
function sendsKey(path: string, method: string | undefined): boolean {
  if (method !== 'POST') return false
  const route = path.split('?')[0] ?? path
  return route.startsWith('/sync/account')
}

async function sendJsonAttempt<T>(path: string, init: RequestInit, mayRecover: boolean): Promise<T> {
  const transport = accountTransport !== null && isAccountPath(path) ? accountTransport : null
  let attempt = init
  let ticket: AccountRequestTicket | null = null
  if (transport !== null) {
    ticket = await transport.begin(sendsKey(path, init.method))
    attempt = { ...init, headers: { ...(init.headers as Record<string, string>), ...ticket.headers } }
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE_PATH}${path}`, attempt)
  } catch (cause) {
    throw new ApiRequestError(
      `The backend could not be reached (${describeCause(cause)}).`,
      UNREACHABLE_CODE,
      null
    )
  }

  // A late answer for a replaced or disconnected account must never reach the new one.
  if (transport !== null && ticket !== null && !transport.isCurrent(ticket)) {
    throw new ApiRequestError('The account changed while this request was in flight.', ACCOUNT_CHANGED_CODE, null)
  }

  if (!response.ok) {
    const error = await toRequestError(response)
    if (transport !== null && ticket !== null && mayRecover && (await transport.recover(ticket, error))) {
      return sendJsonAttempt<T>(path, init, false)
    }
    throw error
  }
  const body = (await response.json()) as T
  if (transport !== null && ticket !== null && !transport.isCurrent(ticket)) {
    throw new ApiRequestError('The account changed while this response was downloading.', ACCOUNT_CHANGED_CODE, null)
  }
  return body
}

export async function toRequestError(response: Response): Promise<ApiRequestError> {
  const body = await readErrorBody(response)
  if (body === null) {
    return new ApiRequestError(
      `The backend answered with HTTP ${response.status}.`,
      UNEXPECTED_STATUS_CODE,
      response.status
    )
  }
  return new ApiRequestError(body.message, body.error, response.status, body.taskStatusUrl ?? null)
}

async function readErrorBody(response: Response): Promise<ApiErrorBody | null> {
  try {
    const parsed: unknown = await response.json()
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      typeof (parsed as ApiErrorBody).error === 'string' &&
      typeof (parsed as ApiErrorBody).message === 'string'
    ) {
      return parsed as ApiErrorBody
    }
    return null
  } catch {
    return null
  }
}

function describeCause(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}
