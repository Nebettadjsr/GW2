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

  constructor(message: string, code: string, status: number | null) {
    super(message)
    this.name = 'ApiRequestError'
    this.code = code
    this.status = status
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
    throw await toRequestError(response)
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
  return new ApiRequestError(body.message, body.error, response.status)
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
