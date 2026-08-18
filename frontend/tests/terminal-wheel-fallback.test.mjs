import assert from 'node:assert/strict'
import test from 'node:test'

import { createAlternateScreenWheelInputResolver } from '../web/src/terminal/wheelFallback.ts'

test('pixel wheel input resolves without requiring a global WheelEvent constructor', () => {
  const terminal = {
    buffer: { active: { type: 'alternate' } },
    modes: { applicationCursorKeysMode: false, mouseTrackingMode: 'none' },
  }
  const resolveWheelInput = createAlternateScreenWheelInputResolver(terminal)

  assert.equal(globalThis.WheelEvent, undefined)
  assert.deepEqual(resolveWheelInput({ deltaMode: 0, deltaY: 64, shiftKey: false }), {
    handled: true,
    input: '\u001b[B',
  })
})
