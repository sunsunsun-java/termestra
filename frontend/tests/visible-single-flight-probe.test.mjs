import assert from 'node:assert/strict'
import test from 'node:test'

import { createVisibleSingleFlightProbe } from '../web/src/lib/visible-single-flight-probe.ts'

const flushPromises = async () => {
  for (let index = 0; index < 6; index += 1) await Promise.resolve()
}

const installBrowserFakes = () => {
  const originalDocument = globalThis.document
  const originalWindow = globalThis.window
  const document = new EventTarget()
  document.visibilityState = 'visible'
  const timers = new Map()
  let nextTimerId = 1
  globalThis.document = document
  globalThis.window = {
    clearTimeout(id) {
      timers.delete(id)
    },
    setTimeout(callback, delay) {
      const id = nextTimerId++
      timers.set(id, { callback, delay })
      return id
    },
  }
  return {
    document,
    fire(delay) {
      const match = [...timers].find(([, timer]) => timer.delay === delay)
      assert.ok(match, `expected a ${delay}ms timer`)
      timers.delete(match[0])
      match[1].callback()
    },
    restore() {
      globalThis.document = originalDocument
      globalThis.window = originalWindow
    },
    timers,
  }
}

test('runs probes serially, pauses while hidden, and probes immediately when visible again', async () => {
  const browser = installBrowserFakes()
  const pending = []
  let calls = 0
  const probe = (signal) => {
    calls += 1
    return new Promise((resolve) => {
      signal.addEventListener('abort', () => resolve(false), { once: true })
      pending.push({ resolve, signal })
    })
  }

  try {
    const loop = createVisibleSingleFlightProbe({
      intervalMs: 3000,
      onOnline() {},
      probe,
      timeoutMs: 8000,
    })

    assert.equal(calls, 1)
    const shared = loop.checkNow()
    assert.equal(calls, 1, 'manual checks must share the in-flight request')
    pending[0].resolve(false)
    assert.equal(await shared, false)
    await flushPromises()
    assert.deepEqual([...browser.timers.values()].map(({ delay }) => delay), [3000])

    browser.fire(3000)
    assert.equal(calls, 2)
    browser.document.visibilityState = 'hidden'
    browser.document.dispatchEvent(new Event('visibilitychange'))
    assert.equal(pending[1].signal.aborted, true)
    await flushPromises()
    assert.equal(browser.timers.size, 0)

    browser.document.visibilityState = 'visible'
    browser.document.dispatchEvent(new Event('visibilitychange'))
    assert.equal(calls, 3)
    loop.dispose()
    assert.equal(pending[2].signal.aborted, true)
    await flushPromises()
    assert.equal(browser.timers.size, 0)
  } finally {
    browser.restore()
  }
})

test('aborts a stalled probe at the timeout and stops scheduling after success', async () => {
  const browser = installBrowserFakes()
  let calls = 0
  let onlineCount = 0
  const results = [null, true]
  const probe = (signal) => {
    const result = results[calls++]
    if (result !== null) return Promise.resolve(result)
    return new Promise((resolve) => {
      signal.addEventListener('abort', () => resolve(false), { once: true })
    })
  }

  try {
    const loop = createVisibleSingleFlightProbe({
      intervalMs: 3000,
      onOnline() {
        onlineCount += 1
      },
      probe,
      timeoutMs: 8000,
    })

    browser.fire(8000)
    await flushPromises()
    assert.deepEqual([...browser.timers.values()].map(({ delay }) => delay), [3000])
    browser.fire(3000)
    await flushPromises()
    assert.equal(calls, 2)
    assert.equal(onlineCount, 1)
    assert.equal(browser.timers.size, 0)
    loop.dispose()
  } finally {
    browser.restore()
  }
})
