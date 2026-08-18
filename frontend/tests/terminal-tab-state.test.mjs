import assert from 'node:assert/strict'
import test from 'node:test'

import {
  appendBoundedTerminalTab,
  sanitizeTerminalTabIds,
} from '../web/src/terminal/terminal-tab-state.ts'

test('sanitizes untrusted stored terminal tabs to a unique bounded recent set', () => {
  const stored = [
    'not-a-tab',
    ...Array.from({ length: 80 }, (_, index) => `worker:${index}`),
    'worker:79',
    42,
  ]

  const result = sanitizeTerminalTabIds(stored)

  assert.equal(result.length, 64)
  assert.equal(result[0], 'worker:16')
  assert.equal(result.at(-1), 'worker:79')
})

test('opening a tab keeps recency without letting the tab collection grow', () => {
  const initial = Array.from({ length: 64 }, (_, index) => `worker:${index}`)
  const reopened = appendBoundedTerminalTab(initial, 'worker:0')
  const appended = appendBoundedTerminalTab(reopened, 'shell:new')

  assert.equal(reopened.length, 64)
  assert.deepEqual(reopened, initial)
  assert.equal(appended.length, 64)
  assert.equal(appended.at(-1), 'shell:new')
  assert.equal(appended.includes('worker:0'), false)
})
