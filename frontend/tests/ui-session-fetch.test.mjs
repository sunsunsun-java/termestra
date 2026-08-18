import assert from 'node:assert/strict'
import test from 'node:test'

import { createUiSessionFetch } from '../web/src/lib/ui-session-fetch.ts'

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), {
    headers: { 'content-type': 'application/json' },
    status,
  })

test('bootstrap and concurrent initialization share one no-store session request', async () => {
  const calls = []
  const client = createUiSessionFetch(async (url, init) => {
    calls.push({ init, url })
    return json({ ok: true })
  })

  await Promise.all([client.initialize(), client.initialize()])

  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/api/ui/session')
  assert.equal(calls[0].init.cache, 'no-store')
  assert.equal(calls[0].init.credentials, 'same-origin')
  assert.equal(calls[0].init.mode, 'same-origin')
  assert.equal(calls[0].init.signal instanceof AbortSignal, true)
})

test('concurrent stale responses share one refresh and both retry', async () => {
  const calls = []
  let protectedCalls = 0
  const client = createUiSessionFetch(async (url) => {
    calls.push(url)
    if (url === '/api/ui/session') return json({ ok: true })
    protectedCalls += 1
    if (protectedCalls <= 2) {
      return json(
        {
          error: 'UI endpoint requires valid UI token',
          error_code: 'UI_SESSION_INVALID',
        },
        403
      )
    }
    return json({ ok: true })
  })

  const responses = await Promise.all([client.fetch('/runs'), client.fetch('/team')])

  assert.deepEqual(responses.map((response) => response.status), [200, 200])
  assert.equal(calls.filter((url) => url === '/api/ui/session').length, 1)
  assert.equal(calls.length, 5)
})

test('a late stale response does not rotate the session again after its peer refreshed', async () => {
  let releaseLateError
  const lateError = new Promise((resolve) => {
    releaseLateError = resolve
  })
  let sessionCalls = 0
  let protectedCalls = 0
  const stale = (bodyPromise) => ({
    status: 403,
    clone: () => ({ json: () => bodyPromise }),
  })
  const stalePayload = {
    error: 'UI endpoint requires valid UI token',
    error_code: 'UI_SESSION_INVALID',
  }
  const client = createUiSessionFetch(async (url) => {
    if (url === '/api/ui/session') {
      sessionCalls += 1
      return json({ ok: true })
    }
    protectedCalls += 1
    if (protectedCalls === 1) return stale(Promise.resolve(stalePayload))
    if (protectedCalls === 2) return stale(lateError)
    return json({ ok: true })
  })

  const first = client.fetch('/runs')
  const second = client.fetch('/team')
  await first
  releaseLateError(stalePayload)
  await second

  assert.equal(sessionCalls, 1)
  assert.equal(protectedCalls, 4)
})

test('new protected requests wait for an in-flight session refresh', async () => {
  const calls = []
  let releaseSession
  const sessionResponse = new Promise((resolve) => {
    releaseSession = resolve
  })
  const client = createUiSessionFetch(async (url) => {
    calls.push(url)
    if (url === '/api/ui/session') return sessionResponse
    return json({ ok: true })
  })

  const initializing = client.initialize()
  const protectedRequest = client.fetch('/team')
  await Promise.resolve()
  assert.deepEqual(calls, ['/api/ui/session'])

  releaseSession(json({ ok: true }))
  await Promise.all([initializing, protectedRequest])
  assert.deepEqual(calls, ['/api/ui/session', '/team'])
})

test('an unrelated forbidden response is not treated as a stale UI session', async () => {
  const calls = []
  const client = createUiSessionFetch(async (url) => {
    calls.push(url)
    return json({ error: 'Forbidden by policy', error_code: 'POLICY_FORBIDDEN' }, 403)
  })

  const response = await client.fetch('/settings')

  assert.equal(response.status, 403)
  assert.deepEqual(calls, ['/settings'])
})

test('a stalled protected request is aborted at its deadline', async () => {
  let capturedSignal
  const client = createUiSessionFetch(
    async (_url, init) => {
      capturedSignal = init?.signal
      return new Promise(() => {})
    },
    { requestTimeoutMs: 5 }
  )

  const fallback = new Promise((_, reject) => {
    setTimeout(() => reject(new Error('request did not time out')), 50)
  })
  await assert.rejects(Promise.race([client.fetch('/runs'), fallback]), { name: 'TimeoutError' })
  assert.equal(capturedSignal?.aborted, true)
})

test('a timed-out session refresh releases the single-flight gate for retry', async () => {
  let calls = 0
  const client = createUiSessionFetch(
    async (url) => {
      assert.equal(url, '/api/ui/session')
      calls += 1
      if (calls === 1) return new Promise(() => {})
      return json({ ok: true })
    },
    { sessionTimeoutMs: 5 }
  )

  const fallback = new Promise((_, reject) => {
    setTimeout(() => reject(new Error('session did not time out')), 50)
  })
  await assert.rejects(Promise.race([client.initialize(), fallback]), { name: 'TimeoutError' })
  await client.initialize()
  assert.equal(calls, 2)
})

test('caller cancellation rejects immediately even when the fetch adapter ignores its signal', async () => {
  const controller = new AbortController()
  const client = createUiSessionFetch(async () => new Promise(() => {}), {
    requestTimeoutMs: 10_000,
  })

  const request = client.fetch('/runs', { signal: controller.signal })
  controller.abort(new DOMException('workspace changed', 'AbortError'))
  const fallback = new Promise((_, reject) => {
    setTimeout(() => reject(new Error('caller abort was not observed')), 50)
  })

  await assert.rejects(Promise.race([request, fallback]), { name: 'AbortError' })
})
