type FetchRequest = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>
type UiSessionRequest = (
  input: RequestInfo | URL,
  init?: RequestInit,
  timeoutMs?: number
) => Promise<Response>

interface UiSessionErrorPayload {
  error?: unknown
  error_code?: unknown
}

export interface UiSessionFetch {
  fetch: UiSessionRequest
  initialize: () => Promise<void>
}

interface UiSessionFetchOptions {
  requestTimeoutMs?: number
  sessionTimeoutMs?: number
}

const DEFAULT_REQUEST_TIMEOUT_MS = 60_000
const DEFAULT_SESSION_TIMEOUT_MS = 10_000

const abortError = (signal: AbortSignal): unknown =>
  signal.reason ?? new DOMException('The request was aborted.', 'AbortError')

const awaitWithSignal = async <T>(operation: Promise<T>, signal?: AbortSignal): Promise<T> => {
  if (!signal) return operation
  if (signal.aborted) throw abortError(signal)
  let rejectAbort: ((reason?: unknown) => void) | undefined
  const aborted = new Promise<never>((_resolve, reject) => {
    rejectAbort = reject
  })
  const onAbort = () => rejectAbort?.(abortError(signal))
  signal.addEventListener('abort', onAbort, { once: true })
  try {
    return await Promise.race([operation, aborted])
  } finally {
    signal.removeEventListener('abort', onAbort)
  }
}

const requestWithTimeout = async (
  request: FetchRequest,
  input: RequestInfo | URL,
  init: RequestInit | undefined,
  timeoutMs: number
): Promise<Response> => {
  if (timeoutMs <= 0) return request(input, init)
  const callerSignal = init?.signal
  if (callerSignal?.aborted) throw abortError(callerSignal)

  const controller = new AbortController()
  let rejectCallerAbort: ((reason?: unknown) => void) | undefined
  const callerAborted = new Promise<never>((_resolve, reject) => {
    rejectCallerAbort = reject
  })
  const onCallerAbort = () => {
    const reason = abortError(callerSignal!)
    controller.abort(reason)
    rejectCallerAbort?.(reason)
  }
  callerSignal?.addEventListener('abort', onCallerAbort, { once: true })
  let timeout: ReturnType<typeof setTimeout> | undefined
  const timeoutError = new DOMException(
    `The request exceeded its ${timeoutMs} ms deadline.`,
    'TimeoutError'
  )
  const deadline = new Promise<never>((_resolve, reject) => {
    timeout = setTimeout(() => {
      controller.abort(timeoutError)
      reject(timeoutError)
    }, timeoutMs)
  })
  try {
    const response = Promise.resolve().then(() =>
      request(input, { ...init, signal: controller.signal })
    )
    return await Promise.race([response, deadline, callerAborted])
  } finally {
    if (timeout !== undefined) clearTimeout(timeout)
    callerSignal?.removeEventListener('abort', onCallerAbort)
  }
}

const isStaleUiSession = async (response: Response): Promise<boolean> => {
  if (response.status !== 403) return false
  try {
    const body = (await response.clone().json()) as UiSessionErrorPayload
    return (
      body.error_code === 'UI_SESSION_INVALID' ||
      body.error === 'UI endpoint requires valid UI token'
    )
  } catch {
    return false
  }
}

/**
 * Coordinates the origin-wide HttpOnly UI cookie.
 *
 * Bootstrap and stale-session recovery share one refresh request. Protected
 * requests arriving during that refresh wait instead of knowingly sending an
 * invalid cookie and producing another avoidable 403.
 */
export const createUiSessionFetch = (
  request: FetchRequest,
  {
    requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
    sessionTimeoutMs = DEFAULT_SESSION_TIMEOUT_MS,
  }: UiSessionFetchOptions = {}
): UiSessionFetch => {
  let refreshPromise: Promise<void> | null = null
  let sessionGeneration = 0

  const requestSession = async (): Promise<void> => {
    const response = await requestWithTimeout(
      request,
      '/api/ui/session',
      {
        cache: 'no-store',
        credentials: 'same-origin',
        mode: 'same-origin',
      },
      sessionTimeoutMs
    )
    if (!response.ok) throw new Error('Failed to initialize UI session')
    await response.json()
    sessionGeneration += 1
  }

  const initialize = (): Promise<void> => {
    refreshPromise ??= requestSession().finally(() => {
      refreshPromise = null
    })
    return refreshPromise
  }

  const fetchWithSession: UiSessionRequest = async (
    input,
    init,
    timeoutMs = requestTimeoutMs
  ) => {
    if (refreshPromise) await awaitWithSignal(refreshPromise, init?.signal ?? undefined)
    const requestGeneration = sessionGeneration
    const response = await requestWithTimeout(request, input, init, timeoutMs)
    if (!(await isStaleUiSession(response))) return response

    // Another request may already have completed a refresh while this one was
    // still receiving/parsing its 403. Starting a second refresh here can
    // rotate the cookie again and make the first request's retry stale.
    if (sessionGeneration === requestGeneration) {
      await awaitWithSignal(initialize(), init?.signal ?? undefined)
    } else if (refreshPromise) {
      await awaitWithSignal(refreshPromise, init?.signal ?? undefined)
    }
    return requestWithTimeout(request, input, init, timeoutMs)
  }

  return { fetch: fetchWithSession, initialize }
}
