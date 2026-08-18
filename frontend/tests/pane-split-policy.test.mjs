import assert from 'node:assert/strict'
import test from 'node:test'

import { boundsForPaneWidth } from '../web/src/lib/pane-split-policy.ts'

test('compact split bounds keep both panes readable when their desktop minima cannot fit', () => {
  for (const width of [320, 480, 700, 799]) {
    const bounds = boundsForPaneWidth(width)
    assert.ok(bounds.min >= 0.4, `orchestrator share at ${width}px was ${bounds.min}`)
    assert.ok(1 - bounds.max >= 0.4, `worker share at ${width}px was ${1 - bounds.max}`)
    assert.ok(bounds.min <= bounds.max)
  }
})

test('desktop split bounds enforce the pixel minima for both panes', () => {
  const width = 1200
  const bounds = boundsForPaneWidth(width)

  assert.ok(bounds.min * width >= 480)
  assert.ok((1 - bounds.max) * width >= 319.99)
})

test('compact and desktop bounds meet without a resize jump at 800px', () => {
  const compact = boundsForPaneWidth(799)
  const desktop = boundsForPaneWidth(800)

  assert.ok(Math.abs(compact.min - desktop.min) < 0.001)
  assert.ok(Math.abs(compact.max - desktop.max) < 0.001)
})
