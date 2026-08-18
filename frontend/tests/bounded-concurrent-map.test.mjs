import assert from 'node:assert/strict'
import test from 'node:test'

import {
  MAX_BOUNDED_MAP_CONCURRENCY,
  mapSettledWithConcurrencyLimit,
} from '../web/src/lib/bounded-concurrent-map.ts'

test('caps a 256-item workload at four requests and preserves settled result order', async () => {
  const items = Array.from({ length: 256 }, (_, index) => index)
  let active = 0
  let maxActive = 0

  const results = await mapSettledWithConcurrencyLimit(items, async (item) => {
    active += 1
    maxActive = Math.max(maxActive, active)
    await new Promise((resolve) => setImmediate(resolve))
    active -= 1
    if (item === 73) throw new Error('workspace unavailable')
    return `worker-${item}`
  })

  assert.equal(MAX_BOUNDED_MAP_CONCURRENCY, 4)
  assert.equal(maxActive, 4)
  assert.equal(results.length, items.length)
  assert.deepEqual(
    results.map((result) => result.item),
    items,
    'completion order must not reorder workspace results'
  )
  assert.deepEqual(results[72], {
    index: 72,
    item: 72,
    status: 'fulfilled',
    value: 'worker-72',
  })
  assert.equal(results[73]?.status, 'rejected')
  assert.match(String(results[73]?.reason), /workspace unavailable/)
  assert.deepEqual(results[74], {
    index: 74,
    item: 74,
    status: 'fulfilled',
    value: 'worker-74',
  })
})

test('does not claim queued work after the caller aborts', async () => {
  const controller = new AbortController()
  const releases = []
  const started = []
  const work = mapSettledWithConcurrencyLimit(
    Array.from({ length: 256 }, (_, index) => index),
    async (item) => {
      started.push(item)
      await new Promise((resolve) => releases.push(resolve))
      return item
    },
    controller.signal
  )

  for (let index = 0; index < 4; index += 1) await Promise.resolve()
  assert.deepEqual(started, [0, 1, 2, 3])
  controller.abort()
  releases.splice(0).forEach((release) => release())

  const results = await work
  assert.deepEqual(started, [0, 1, 2, 3])
  assert.deepEqual(
    results.map((result) => result.item),
    [0, 1, 2, 3]
  )
})
