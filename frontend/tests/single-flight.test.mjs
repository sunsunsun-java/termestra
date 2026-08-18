import assert from 'node:assert/strict'
import test from 'node:test'

import { createSingleFlight } from '../web/src/lib/single-flight.ts'

test('concurrent submissions share one operation and one result', async () => {
  let calls = 0
  let resolveOperation
  const operation = new Promise((resolve) => {
    resolveOperation = resolve
  })
  const singleFlight = createSingleFlight(async (value) => {
    calls += 1
    return operation.then(() => value)
  })

  const first = singleFlight.run('first')
  const second = singleFlight.run('second')

  assert.equal(singleFlight.isRunning(), true)
  assert.strictEqual(second, first)
  await Promise.resolve()
  assert.equal(calls, 1)

  resolveOperation()
  assert.deepEqual(await Promise.all([first, second]), ['first', 'first'])
  assert.equal(singleFlight.isRunning(), false)
})

test('a failed operation releases the gate for an explicit retry', async () => {
  let calls = 0
  const singleFlight = createSingleFlight(async () => {
    calls += 1
    if (calls === 1) throw new Error('create failed')
    return 'created'
  })

  await assert.rejects(singleFlight.run(), /create failed/)
  assert.equal(singleFlight.isRunning(), false)
  assert.equal(await singleFlight.run(), 'created')
  assert.equal(calls, 2)
})
