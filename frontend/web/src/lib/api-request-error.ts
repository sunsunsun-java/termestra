interface ApiRequestErrorOptions {
  code?: string | null
  resourceType?: string | null
  retryAfterMs?: number | null
  retryable?: boolean
  status: number
}

interface ApiErrorPayload {
  error?: unknown
  error_code?: unknown
  resource_type?: unknown
  retry_after_ms?: unknown
  retryable?: unknown
}

const nonBlankString = (value: unknown): string | null =>
  typeof value === 'string' && value.trim() ? value : null

const nonNegativeInteger = (value: unknown): number | null =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null

/** Stable, typed representation of an HTTP API failure at the browser boundary. */
export class ApiRequestError extends Error {
  readonly code: string | null
  readonly resourceType: string | null
  readonly retryAfterMs: number | null
  readonly retryable: boolean
  readonly status: number

  constructor(message: string, options: ApiRequestErrorOptions) {
    super(message)
    this.name = 'ApiRequestError'
    this.code = options.code ?? null
    this.resourceType = options.resourceType ?? null
    this.retryAfterMs = options.retryAfterMs ?? null
    this.retryable = options.retryable ?? false
    this.status = options.status
  }
}

export const readApiRequestError = async (
  response: Response,
  fallback: string
): Promise<ApiRequestError> => {
  let payload: ApiErrorPayload = {}
  try {
    payload = (await response.json()) as ApiErrorPayload
  } catch {
    // A non-JSON response still retains its HTTP status and the caller's fallback.
  }
  return new ApiRequestError(nonBlankString(payload.error) ?? fallback, {
    code: nonBlankString(payload.error_code),
    resourceType: nonBlankString(payload.resource_type),
    retryAfterMs: nonNegativeInteger(payload.retry_after_ms),
    retryable: payload.retryable === true,
    status: response.status,
  })
}
