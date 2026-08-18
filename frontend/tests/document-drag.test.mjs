import assert from 'node:assert/strict'
import test from 'node:test'

import { startDocumentDrag } from '../web/src/lib/document-drag.ts'

const createDocument = () => {
  const listeners = new Map()
  const windowListeners = new Map()
  const defaultView = {
    addEventListener(type, listener) {
      const entries = windowListeners.get(type) ?? new Set()
      entries.add(listener)
      windowListeners.set(type, entries)
    },
    removeEventListener(type, listener) {
      windowListeners.get(type)?.delete(listener)
    },
  }
  const document = {
    body: { style: { cursor: 'default', userSelect: 'text' } },
    defaultView,
    addEventListener(type, listener) {
      const entries = listeners.get(type) ?? new Set()
      entries.add(listener)
      listeners.set(type, entries)
    },
    removeEventListener(type, listener) {
      listeners.get(type)?.delete(listener)
    },
  }
  return {
    document,
    dispatch(type, event = {}) {
      for (const listener of [...(listeners.get(type) ?? [])]) listener(event)
    },
    dispatchWindow(type, event = {}) {
      for (const listener of [...(windowListeners.get(type) ?? [])]) listener(event)
    },
    listenerCount(type) {
      return listeners.get(type)?.size ?? 0
    },
  }
}

test('disposing an active pointer drag restores body styles and removes every listener', () => {
  const env = createDocument()
  let moves = 0
  let finishes = 0

  const dispose = startDocumentDrag({
    cursor: 'col-resize',
    document: env.document,
    endEvents: ['pointerup', 'pointercancel'],
    moveEvent: 'pointermove',
    onFinish: () => {
      finishes += 1
    },
    onMove: () => {
      moves += 1
    },
  })

  assert.equal(env.document.body.style.cursor, 'col-resize')
  assert.equal(env.document.body.style.userSelect, 'none')
  assert.equal(env.listenerCount('pointermove'), 1)
  assert.equal(env.listenerCount('pointerup'), 1)
  assert.equal(env.listenerCount('pointercancel'), 1)

  dispose()
  dispose()
  env.dispatch('pointermove')
  env.dispatch('pointerup')

  assert.equal(moves, 0)
  assert.equal(finishes, 0)
  assert.equal(env.document.body.style.cursor, 'default')
  assert.equal(env.document.body.style.userSelect, 'text')
  assert.equal(env.listenerCount('pointermove'), 0)
  assert.equal(env.listenerCount('pointerup'), 0)
  assert.equal(env.listenerCount('pointercancel'), 0)
})

test('losing the browser window ends the drag and cannot leave sticky body styles', () => {
  const env = createDocument()
  let finishes = 0
  startDocumentDrag({
    cursor: 'col-resize',
    document: env.document,
    endEvents: ['pointerup', 'pointercancel'],
    moveEvent: 'pointermove',
    onFinish: () => {
      finishes += 1
    },
    onMove() {},
  })

  env.dispatchWindow('blur')

  assert.equal(finishes, 1)
  assert.equal(env.document.body.style.cursor, 'default')
  assert.equal(env.document.body.style.userSelect, 'text')
  assert.equal(env.listenerCount('pointermove'), 0)
})

test('a natural drag end cleans up once and reports completion once', () => {
  const env = createDocument()
  let finishes = 0
  let lastX = null
  const dispose = startDocumentDrag({
    cursor: 'ew-resize',
    document: env.document,
    endEvents: ['mouseup'],
    moveEvent: 'mousemove',
    onFinish: () => {
      finishes += 1
    },
    onMove: (event) => {
      lastX = event.clientX
    },
  })

  env.dispatch('mousemove', { clientX: 42 })
  env.dispatch('mouseup')
  env.dispatch('mouseup')
  dispose()

  assert.equal(lastX, 42)
  assert.equal(finishes, 1)
  assert.equal(env.listenerCount('mousemove'), 0)
  assert.equal(env.listenerCount('mouseup'), 0)
  assert.equal(env.document.body.style.cursor, 'default')
  assert.equal(env.document.body.style.userSelect, 'text')
})

test('starting a second document drag completes the first without corrupting restored styles', () => {
  const env = createDocument()
  let firstFinishes = 0
  let secondFinishes = 0
  startDocumentDrag({
    cursor: 'col-resize',
    document: env.document,
    endEvents: ['pointerup'],
    moveEvent: 'pointermove',
    onFinish: () => {
      firstFinishes += 1
    },
    onMove() {},
  })
  startDocumentDrag({
    cursor: 'ns-resize',
    document: env.document,
    endEvents: ['pointerup'],
    moveEvent: 'pointermove',
    onFinish: () => {
      secondFinishes += 1
    },
    onMove() {},
  })

  assert.equal(firstFinishes, 1)
  assert.equal(env.listenerCount('pointermove'), 1)
  assert.equal(env.document.body.style.cursor, 'ns-resize')
  env.dispatch('pointerup')

  assert.equal(secondFinishes, 1)
  assert.equal(env.document.body.style.cursor, 'default')
  assert.equal(env.document.body.style.userSelect, 'text')
})
