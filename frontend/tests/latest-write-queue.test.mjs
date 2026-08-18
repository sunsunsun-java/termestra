import assert from 'node:assert/strict'
import test from 'node:test'

import { createLatestWriteQueue } from '../web/src/lib/latest-write-queue.ts'

const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, reject, resolve }
}

test('bounds pending writes to the latest value while one write is in flight', async () => {
  const first = deferred()
  const calls = []
  const queue = createLatestWriteQueue(async (value) => {
    calls.push(value)
    if (calls.length === 1) await first.promise
  })

  queue.enqueue('workspace-a')
  queue.enqueue('workspace-b')
  queue.enqueue('workspace-c')
  await Promise.resolve()
  assert.deepEqual(calls, ['workspace-a'])

  first.resolve()
  await queue.whenIdle()
  assert.deepEqual(calls, ['workspace-a', 'workspace-c'])
})

test('a failed write does not prevent the latest selection from being persisted', async () => {
  const errors = []
  const calls = []
  const first = deferred()
  const queue = createLatestWriteQueue(
    async (value) => {
      calls.push(value)
      if (calls.length === 1) await first.promise
    },
    (error) => errors.push(error)
  )

  queue.enqueue('workspace-a')
  queue.enqueue('workspace-b')
  first.reject(new Error('database unavailable'))
  await queue.whenIdle()

  assert.deepEqual(calls, ['workspace-a', 'workspace-b'])
  assert.equal(errors.length, 1)
})
