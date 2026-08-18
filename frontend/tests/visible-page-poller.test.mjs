import assert from 'node:assert/strict'
import test from 'node:test'

import { createVisiblePagePoller } from '../web/src/lib/visible-page-poller.ts'

test('polls only while the page is visible and refreshes immediately on return', () => {
  const originalDocument = globalThis.document
  const originalWindow = globalThis.window
  const document = new EventTarget()
  document.visibilityState = 'visible'
  const timers = new Map()
  let nextTimerId = 1
  let loadCount = 0
  const reasons = []

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

  try {
    const poller = createVisiblePagePoller({
      getDelay: () => 500,
      load: (reason) => {
        loadCount += 1
        reasons.push(reason)
      },
    })

    assert.equal(loadCount, 1)
    poller.schedule()
    assert.equal(timers.size, 1)
    assert.equal([...timers.values()][0].delay, 500)

    document.visibilityState = 'hidden'
    document.dispatchEvent(new Event('visibilitychange'))
    assert.equal(timers.size, 0)
    assert.equal(loadCount, 1)

    document.visibilityState = 'visible'
    document.dispatchEvent(new Event('visibilitychange'))
    assert.equal(loadCount, 2)
    assert.deepEqual(reasons, ['initial', 'visible'])

    poller.schedule()
    poller.dispose()
    assert.equal(timers.size, 0)

    document.dispatchEvent(new Event('visibilitychange'))
    assert.equal(loadCount, 2)
  } finally {
    globalThis.document = originalDocument
    globalThis.window = originalWindow
  }
})
