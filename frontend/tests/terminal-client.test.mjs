import assert from 'node:assert/strict'
import test from 'node:test'

import { createTerminalClient } from '../web/src/terminal/terminal-client.ts'

class FakeWebSocket {
  static CLOSED = 3
  static CLOSING = 2
  static CONNECTING = 0
  static OPEN = 1
  static instances = []
  static throwOnInstance = null

  CLOSED = FakeWebSocket.CLOSED
  CLOSING = FakeWebSocket.CLOSING
  CONNECTING = FakeWebSocket.CONNECTING
  OPEN = FakeWebSocket.OPEN
  bufferedAmount = 0
  closeCalls = 0
  onclose = null
  onerror = null
  onmessage = null
  onopen = null
  readyState = this.OPEN
  sent = []

  constructor(url) {
    if (FakeWebSocket.instances.length + 1 === FakeWebSocket.throwOnInstance) {
      throw new DOMException('blocked', 'SecurityError')
    }
    this.url = url
    FakeWebSocket.instances.push(this)
  }

  close() {
    this.closeCalls += 1
    this.readyState = this.CLOSING
  }

  emitClose() {
    this.readyState = this.CLOSED
    this.onclose?.({ code: 1006, reason: '', wasClean: false })
  }

  emitError() {
    this.onerror?.(new Event('error'))
  }

  send(message) {
    this.sent.push(message)
  }
}

const installBrowserGlobals = () => {
  const originalWebSocket = globalThis.WebSocket
  const originalWindow = globalThis.window
  FakeWebSocket.instances = []
  FakeWebSocket.throwOnInstance = null
  globalThis.WebSocket = FakeWebSocket
  globalThis.window = {
    clearTimeout,
    location: new URL('http://127.0.0.1:5180/'),
    setTimeout,
  }
  return () => {
    globalThis.WebSocket = originalWebSocket
    globalThis.window = originalWindow
  }
}

const createClient = (overrides = {}) => {
  const errors = []
  const exits = []
  const output = []
  const restored = []
  const client = createTerminalClient({
    onError: (message) => errors.push(message),
    onExit: (code) => exits.push(code),
    onOutput: (chunk, acknowledge) => {
      output.push(chunk)
      acknowledge(new TextEncoder().encode(chunk).byteLength)
    },
    onRestore: (snapshot) => restored.push(snapshot),
    runId: 'run-1',
    ...overrides,
  })
  const [ioSocket, controlSocket] = FakeWebSocket.instances
  return { client, controlSocket, errors, exits, ioSocket, output, restored }
}

test('restores queued output before streaming live output', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const { client, controlSocket, errors, ioSocket, output, restored } = createClient()

    ioSocket.onmessage({ data: 'queued' })
    assert.deepEqual(output, [])
    controlSocket.onmessage({ data: JSON.stringify({ type: 'restore', snapshot: 'snapshot' }) })

    assert.deepEqual(errors, [])
    assert.deepEqual(restored, ['snapshot'])
    assert.deepEqual(output, ['queued'])
    assert.ok(controlSocket.sent.includes(JSON.stringify({ type: 'restore_complete' })))
    assert.ok(controlSocket.sent.includes(JSON.stringify({ type: 'output_ack', bytes: 6 })))
    client.dispose()
  } finally {
    restoreGlobals()
  }
})

test('closes both sockets when pre-restore output exceeds the byte limit', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const { controlSocket, errors, ioSocket } = createClient()

    ioSocket.onmessage({ data: 'x'.repeat(200 * 1024) })
    ioSocket.onmessage({ data: 'x'.repeat(60 * 1024) })

    assert.equal(errors.length, 1)
    assert.match(errors[0], /safe buffer/i)
    assert.equal(ioSocket.closeCalls, 1)
    assert.equal(controlSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('closes both sockets when pre-restore output exceeds the chunk limit', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const { controlSocket, errors, ioSocket } = createClient()

    for (let index = 0; index <= 2_048; index += 1) ioSocket.onmessage({ data: 'x' })

    assert.equal(errors.length, 1)
    assert.match(errors[0], /safe buffer/i)
    assert.equal(ioSocket.closeCalls, 1)
    assert.equal(controlSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('an io disconnect closes the control socket and reports one error', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const { controlSocket, errors, ioSocket } = createClient()

    ioSocket.emitClose()
    controlSocket.emitError()
    controlSocket.emitClose()

    assert.equal(errors.length, 1)
    assert.match(errors[0], /io connection closed/i)
    assert.equal(controlSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('a control error closes the io socket and ignores stale callbacks', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const { controlSocket, errors, ioSocket, output } = createClient()

    controlSocket.emitError()
    ioSocket.onmessage({ data: 'stale output' })
    controlSocket.onmessage({ data: JSON.stringify({ type: 'error', message: 'stale error' }) })

    assert.equal(errors.length, 1)
    assert.match(errors[0], /control connection failed/i)
    assert.deepEqual(output, [])
    assert.equal(ioSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('dispose closes the pair without reporting a later close as an error', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const { client, controlSocket, errors, ioSocket } = createClient()

    client.dispose()
    ioSocket.emitClose()
    controlSocket.emitClose()

    assert.deepEqual(errors, [])
    assert.equal(ioSocket.closeCalls, 1)
    assert.equal(controlSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('exit stops and closes the pair without a disconnect error', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const { controlSocket, errors, exits, ioSocket } = createClient()

    controlSocket.onmessage({ data: JSON.stringify({ type: 'exit', code: 7 }) })
    ioSocket.emitClose()
    controlSocket.emitClose()

    assert.deepEqual(exits, [7])
    assert.deepEqual(errors, [])
    assert.equal(ioSocket.closeCalls, 1)
    assert.equal(controlSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('invalid or duplicate control messages fail the pair exactly once', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const first = createClient()
    first.controlSocket.onmessage({ data: '{invalid' })
    first.controlSocket.onmessage({ data: '{invalid again' })
    assert.equal(first.errors.length, 1)
    assert.match(first.errors[0], /invalid control message/i)

    FakeWebSocket.instances = []
    const second = createClient()
    const restore = JSON.stringify({ type: 'restore', snapshot: 'snapshot' })
    second.controlSocket.onmessage({ data: restore })
    second.controlSocket.onmessage({ data: restore })
    assert.equal(second.errors.length, 1)
    assert.match(second.errors[0], /duplicate restore/i)
  } finally {
    restoreGlobals()
  }
})

test('rejects input when the browser WebSocket send queue reaches its hard limit', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const { client, controlSocket, errors, ioSocket } = createClient()
    ioSocket.bufferedAmount = 1024 * 1024

    assert.equal(client.sendInput('x'), false)

    assert.equal(errors.length, 1)
    assert.match(errors[0], /input exceeded the safe buffer/i)
    assert.equal(ioSocket.closeCalls, 1)
    assert.equal(controlSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('accepts text and binary input exactly at the backend 256 KiB message limit', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const { client, errors, ioSocket } = createClient()
    const unicodeAtLimit = `${'你'.repeat(87_381)}a`
    assert.equal(new TextEncoder().encode(unicodeAtLimit).byteLength, 256 * 1024)

    assert.equal(client.sendInput(unicodeAtLimit), true)
    assert.equal(client.sendBinaryInput('x'.repeat(256 * 1024)), true)

    assert.deepEqual(errors, [])
    assert.equal(ioSocket.sent[0], unicodeAtLimit)
    assert.equal(ioSocket.sent[1].byteLength, 256 * 1024)
    client.dispose()
  } finally {
    restoreGlobals()
  }
})

test('rejects text and binary input one byte above the backend message limit', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const textPair = createClient()
    const unicodeOverLimit = `${'你'.repeat(87_381)}ab`
    assert.equal(new TextEncoder().encode(unicodeOverLimit).byteLength, 256 * 1024 + 1)

    assert.equal(textPair.client.sendInput(unicodeOverLimit), false)

    assert.equal(textPair.errors.length, 1)
    assert.match(textPair.errors[0], /256 KiB message limit/i)
    assert.deepEqual(textPair.ioSocket.sent, [])
    assert.equal(textPair.ioSocket.closeCalls, 1)
    assert.equal(textPair.controlSocket.closeCalls, 1)

    FakeWebSocket.instances = []
    const binaryPair = createClient()
    assert.equal(binaryPair.client.sendBinaryInput('x'.repeat(256 * 1024 + 1)), false)

    assert.equal(binaryPair.errors.length, 1)
    assert.match(binaryPair.errors[0], /256 KiB message limit/i)
    assert.deepEqual(binaryPair.ioSocket.sent, [])
    assert.equal(binaryPair.ioSocket.closeCalls, 1)
    assert.equal(binaryPair.controlSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('rejects an oversized live output chunk instead of queuing it in xterm', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const { controlSocket, errors, ioSocket, output } = createClient()
    controlSocket.onmessage({ data: JSON.stringify({ type: 'restore', snapshot: '' }) })

    ioSocket.onmessage({ data: 'x'.repeat(256 * 1024 + 1) })

    assert.equal(errors.length, 1)
    assert.match(errors[0], /safe chunk limit/i)
    assert.deepEqual(output, [])
    assert.equal(ioSocket.closeCalls, 1)
    assert.equal(controlSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('rejects an oversized restore snapshot before handing it to xterm', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const { controlSocket, errors, ioSocket, restored } = createClient()

    controlSocket.onmessage({
      data: JSON.stringify({ type: 'restore', snapshot: 'x'.repeat(900 * 1024 + 1) }),
    })

    assert.equal(errors.length, 1)
    assert.match(errors[0], /snapshot exceeded the safe limit/i)
    assert.deepEqual(restored, [])
    assert.equal(ioSocket.closeCalls, 1)
    assert.equal(controlSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('closes the io socket when constructing the control socket fails', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    FakeWebSocket.throwOnInstance = 2
    let error
    try {
      createClient()
    } catch (caught) {
      error = caught
    }

    assert.equal(error?.name, 'SecurityError')
    assert.equal(FakeWebSocket.instances.length, 1)
    assert.equal(FakeWebSocket.instances[0].closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('a restore callback failure closes the pair with one visible error', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const pair = createClient({
      onRestore() {
        throw new Error('renderer disposed')
      },
    })

    pair.controlSocket.onmessage({
      data: JSON.stringify({ type: 'restore', snapshot: 'snapshot' }),
    })

    assert.equal(pair.errors.length, 1)
    assert.match(pair.errors[0], /render.*restore/i)
    assert.equal(pair.ioSocket.closeCalls, 1)
    assert.equal(pair.controlSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})

test('an output callback failure closes the pair instead of wedging backpressure', () => {
  const restoreGlobals = installBrowserGlobals()
  try {
    const pair = createClient({
      onOutput() {
        throw new Error('xterm write failed')
      },
    })
    pair.controlSocket.onmessage({
      data: JSON.stringify({ type: 'restore', snapshot: '' }),
    })
    pair.ioSocket.onmessage({ data: 'live output' })

    assert.equal(pair.errors.length, 1)
    assert.match(pair.errors[0], /render.*output/i)
    assert.equal(pair.ioSocket.closeCalls, 1)
    assert.equal(pair.controlSocket.closeCalls, 1)
  } finally {
    restoreGlobals()
  }
})
