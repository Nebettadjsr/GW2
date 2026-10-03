import type { ApiErrorBody } from './types'

/**
 * Minimal `fetch` wrapper for the backend HTTP API.
 *
 * The base path is relative by default, so the browser calls the same origin it was served from —
 * the dev server and any deployment proxy forward `/api` to the backend. No other host is ever
 * contacted from the browser, and no credential of any kind is held here.
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

async function sendJsonAttempt<T>(path: string, init: RequestInit, mayRecover: boolean): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${API_BASE_PATH}${path}`, init)
  } catch (cause) {
    throw new ApiRequestError(
      `The backend could not be reached (${describeCause(cause)}).`,
      UNREACHABLE_CODE,
      null
    )
  }

  if (!response.ok) {
    const error = await toRequestError(response)
    if (mayRecover && error.code === 'ACCOUNT_DATA_STALE' && error.taskStatusUrl) {
      await waitForAccountRefresh(error.taskStatusUrl)
      return sendJsonAttempt<T>(path, init, false)
    }
    throw error
  }
  return (await response.json()) as T
}

async function toRequestError(response: Response): Promise<ApiRequestError> {
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

async function waitForAccountRefresh(statusPath: string): Promise<void> {
  if (!statusPath.startsWith(`${API_BASE_PATH}/sync/tasks/`)) throw new Error('Invalid account sync status URL')
  const deadline = Date.now() + 5 * 60_000
  while (Date.now() < deadline) {
    const response = await fetch(statusPath, { headers: { Accept: 'application/json' } })
    if (!response.ok) throw await toRequestError(response)
    const status = await response.json() as { state?: string; failure?: { message?: string } | null }
    if (status.state === 'SUCCEEDED') return
    if (status.state === 'FAILED') throw new ApiRequestError(status.failure?.message ?? 'Account data refresh failed.', 'ACCOUNT_SYNC_FAILED', 503)
    await new Promise(resolve => window.setTimeout(resolve, 500))
  }
  throw new ApiRequestError('Account data refresh is taking longer than expected.', 'ACCOUNT_SYNC_TIMEOUT', 503)
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
