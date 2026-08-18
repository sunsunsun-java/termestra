import assert from 'node:assert/strict'
import test from 'node:test'

import {
  MAX_TASKS_TRANSPORT_CONTENT_BYTES,
  tasksContentFitsTransport,
  tasksTransportContentBytes,
} from '../web/src/tasks/tasks-content-limit.ts'

test('tasks transport accounting includes JSON escaping instead of only raw UTF-8 bytes', () => {
  assert.equal(tasksTransportContentBytes('plain'), 5)
  assert.equal(tasksTransportContentBytes('"\\\n'), 6)
  assert.equal(tasksTransportContentBytes('😀'), 4)
})

test('tasks transport limit rejects escape-heavy content that would overflow its JSON frame', () => {
  const safe = 'x'.repeat(MAX_TASKS_TRANSPORT_CONTENT_BYTES)
  const escapeHeavy = '"'.repeat(MAX_TASKS_TRANSPORT_CONTENT_BYTES / 2 + 1)

  assert.equal(tasksContentFitsTransport(safe), true)
  assert.equal(tasksContentFitsTransport(`${safe}x`), false)
  assert.equal(tasksContentFitsTransport(escapeHeavy), false)
})
