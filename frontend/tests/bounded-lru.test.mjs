import assert from 'node:assert/strict'
import test from 'node:test'

import { BoundedLruMap } from '../web/src/lib/bounded-lru-map.ts'
import { requireBoundedList } from '../web/src/lib/bounded-list.ts'

test('bounded LRU cache evicts the least recently used entry', () => {
  const cache = new BoundedLruMap(2)
  cache.set('a', 1)
  cache.set('b', 2)
  assert.equal(cache.get('a'), 1)
  cache.set('c', 3)

  assert.equal(cache.has('a'), true)
  assert.equal(cache.has('b'), false)
  assert.equal(cache.has('c'), true)
  assert.equal(cache.size, 2)
})

test('bounded response lists reject an oversized or malformed public payload', () => {
  assert.deepEqual(requireBoundedList(['a', 'b'], 'items', 2), ['a', 'b'])
  assert.throws(() => requireBoundedList(['a', 'b', 'c'], 'items', 2), /safe limit/)
  assert.throws(() => requireBoundedList({ 0: 'a' }, 'items', 2), /array/)
})
