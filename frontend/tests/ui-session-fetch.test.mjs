import assert from 'node:assert/strict'
import test from 'node:test'
import { getEventListeners } from 'node:events'

import { createUiSessionFetch, MAX_API_RESPONSE_BYTES } from '../web/src/lib/ui-session-fetch.ts'

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

const streamingResponse = (status = 200) => {
  let controller
  let cancelled = false
  const response = new Response(new ReadableStream({
    start(value) { controller = value },
    cancel() { cancelled = true },
  }), { status, headers: { 'content-type': 'application/json', 'x-contract': 'preserved' } })
  return {
    response,
    write: (text) => controller.enqueue(new TextEncoder().encode(text)),
    close: () => controller.close(),
    get cancelled() { return cancelled },
  }
}

test('deadline includes a stalled JSON body after response headers', { timeout: 2000 }, async () => {
  const stream = streamingResponse()
  stream.write('{"value":')
  let signal
  const client = createUiSessionFetch(async (_url, init) => {
    signal = init.signal
    return stream.response
  }, { requestTimeoutMs: 20 })
  await assert.rejects(client.fetch('/runs').then((response) => response.json()), { name: 'TimeoutError' })
  assert.equal(signal.aborted, true)
  await new Promise(setImmediate)
  assert.equal(stream.cancelled, true)
})

test('caller abort after headers cancels both body branches and rejects without waiting for the deadline', { timeout: 2000 }, async () => {
  const stream = streamingResponse()
  const controller = new AbortController()
  let receivedHeaders
  const headers = new Promise((resolve) => { receivedHeaders = resolve })
  const client = createUiSessionFetch(async () => {
    receivedHeaders()
    return stream.response
  }, { requestTimeoutMs: 10_000 })
  const pending = client.fetch('/team', { signal: controller.signal })
  await headers
  await new Promise(setImmediate)
  controller.abort(new DOMException('workspace changed', 'AbortError'))
  await assert.rejects(pending, { name: 'AbortError' })
  await new Promise(setImmediate)
  assert.equal(stream.cancelled, true)
})

test('bounds received bytes even without Content-Length and cancels an oversized body', async () => {
  const stream = streamingResponse()
  stream.write('x'.repeat(MAX_API_RESPONSE_BYTES + 1))
  const client = createUiSessionFetch(async () => stream.response)
  await assert.rejects(client.fetch('/catalog'), new RegExp(`API response exceeds ${MAX_API_RESPONSE_BYTES} bytes`))
  await new Promise(setImmediate)
  assert.equal(stream.cancelled, true)
})

test('completed bounded responses preserve identity, status, headers and body cloning', async () => {
  const stream = streamingResponse(409)
  stream.write('{"value":1}')
  stream.close()
  const controller = new AbortController()
  const client = createUiSessionFetch(async () => stream.response)
  const response = await client.fetch('/tasks', { signal: controller.signal })
  assert.equal(response, stream.response)
  assert.equal(getEventListeners(controller.signal, 'abort').length, 0)
  assert.equal(response.bodyUsed, false)
  assert.equal(response.status, 409)
  assert.equal(response.headers.get('x-contract'), 'preserved')
  // The network body has finished; a later workspace change must not destroy
  // the fully received response before its caller parses it.
  controller.abort()
  assert.deepEqual(await response.clone().json(), { value: 1 })
  assert.deepEqual(await response.json(), { value: 1 })
})

test('stalled session body releases its single-flight gate at the session deadline', { timeout: 2000 }, async () => {
  const stream = streamingResponse()
  let calls = 0
  const client = createUiSessionFetch(async () => ++calls === 1 ? stream.response : json({ ok: true }), { sessionTimeoutMs: 20 })
  await assert.rejects(client.initialize(), { name: 'TimeoutError' })
  await client.initialize()
  assert.equal(calls, 2)
})

test('a stalled 403 body times out before stale-session parsing or retry', { timeout: 2000 }, async () => {
  const stream = streamingResponse(403)
  let calls = 0
  const client = createUiSessionFetch(async () => { calls++; return stream.response }, { requestTimeoutMs: 20 })
  await assert.rejects(client.fetch('/team'), { name: 'TimeoutError' })
  assert.equal(calls, 1)
})

test('body-less successful responses retain their 204 contract', async () => {
  const response = new Response(null, { status: 204 })
  const client = createUiSessionFetch(async () => response)
  assert.equal(await client.fetch('/delete'), response)
})


test('zero deadline requests still honor caller cancellation and bound their body', { timeout: 2000 }, async () => {
  const stream = streamingResponse()
  const controller = new AbortController()
  const client = createUiSessionFetch(async () => stream.response)
  const pending = client.fetch('/api/fs/pick-folder', { signal: controller.signal }, 0)
  await new Promise(setImmediate)
  controller.abort()
  await assert.rejects(pending, { name: 'AbortError' })
  await new Promise(setImmediate)
  assert.equal(stream.cancelled, true)
  assert.equal(getEventListeners(controller.signal, 'abort').length, 0)
})
