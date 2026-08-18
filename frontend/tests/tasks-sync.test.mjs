import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createObservedRejection,
  createTasksStream,
  createTasksWriteQueue,
} from '../web/src/tasks/tasks-sync.ts'

const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, reject, resolve }
}

test('an observed fire-and-forget rejection still rejects explicit awaiters', async () => {
  const error = new Error('connection stale')
  createObservedRejection(error)
  await new Promise((resolve) => setImmediate(resolve))

  await assert.rejects(createObservedRejection(error), /connection stale/)
})

test('serializes full-document writes and never lets an older response replace a newer edit', async () => {
  const first = deferred()
  const second = deferred()
  const calls = []
  const applied = []
  const queue = createTasksWriteQueue({
    initialRevision: 'r0',
    onAccepted: (snapshot) => applied.push(snapshot),
    save: (content, revision) => {
      calls.push({ content, revision })
      return calls.length === 1 ? first.promise : second.promise
    },
  })

  const firstSave = queue.enqueue('first')
  const secondSave = queue.enqueue('second')
  await Promise.resolve()
  assert.deepEqual(calls, [{ content: 'first', revision: 'r0' }])

  first.resolve({ content: 'first', revision: 'r1' })
  await firstSave
  await Promise.resolve()
  assert.deepEqual(calls, [
    { content: 'first', revision: 'r0' },
    { content: 'second', revision: 'r1' },
  ])
  assert.deepEqual(applied, [], 'an older acknowledgement must not overwrite the newer edit')

  second.resolve({ content: 'second', revision: 'r2' })
  await secondSave
  assert.deepEqual(applied, [{ content: 'second', revision: 'r2' }])
})

test('bounds rapid whole-document saves to one in-flight write and one latest pending write', async () => {
  const first = deferred()
  const latest = deferred()
  const calls = []
  const applied = []
  const queue = createTasksWriteQueue({
    initialRevision: 'r0',
    onAccepted: (snapshot) => applied.push(snapshot),
    save: (content, revision) => {
      calls.push({ content, revision })
      return calls.length === 1 ? first.promise : latest.promise
    },
  })

  const firstSave = queue.enqueue('first')
  const pendingSaves = Array.from({ length: 1_000 }, (_, index) =>
    queue.enqueue(`pending-${index}`)
  )

  assert.equal(
    new Set(pendingSaves).size,
    1,
    'superseded pending saves must share one completion instead of retaining 1,000 promises'
  )
  assert.equal(queue.hasPendingWrites(), true)
  assert.equal(queue.hasPendingContent('pending-0'), false)
  assert.equal(queue.hasPendingContent('pending-999'), true)
  assert.deepEqual(calls, [{ content: 'first', revision: 'r0' }])

  first.resolve({ content: 'first', revision: 'r1' })
  await firstSave
  await Promise.resolve()
  assert.deepEqual(calls, [
    { content: 'first', revision: 'r0' },
    { content: 'pending-999', revision: 'r1' },
  ])

  latest.resolve({ content: 'pending-999', revision: 'r2' })
  const results = await Promise.all(pendingSaves)
  assert.ok(results.every((snapshot) => snapshot.content === 'pending-999'))
  assert.equal(queue.hasPendingWrites(), false)
  assert.deepEqual(applied, [{ content: 'pending-999', revision: 'r2' }])
})

test('a transient failed write still advances to the coalesced latest document', async () => {
  const first = deferred()
  const latest = deferred()
  const failure = new Error('temporary failure')
  const calls = []
  const failed = []
  const rejected = []
  const queue = createTasksWriteQueue({
    initialRevision: 'r0',
    onAccepted() {},
    onFailed: (error) => failed.push(error),
    onRejected: (error) => rejected.push(error),
    save: (content, revision) => {
      calls.push({ content, revision })
      return calls.length === 1 ? first.promise : latest.promise
    },
  })

  const firstSave = queue.enqueue('first')
  const pendingSave = queue.enqueue('intermediate')
  assert.equal(queue.enqueue('latest'), pendingSave)
  first.reject(failure)

  await assert.rejects(firstSave, /temporary failure/)
  await Promise.resolve()
  assert.deepEqual(calls, [
    { content: 'first', revision: 'r0' },
    { content: 'latest', revision: 'r0' },
  ])
  assert.deepEqual(failed, [failure])
  assert.deepEqual(rejected, [], 'an older failure must not reject the still-pending latest edit')

  latest.resolve({ content: 'latest', revision: 'r1' })
  assert.deepEqual(await pendingSave, { content: 'latest', revision: 'r1' })
})

test('a live revision learned during a save remains the base for the pending latest write', async () => {
  const first = deferred()
  const latest = deferred()
  const calls = []
  const queue = createTasksWriteQueue({
    initialRevision: 'r0',
    onAccepted() {},
    save: (content, revision) => {
      calls.push({ content, revision })
      return calls.length === 1 ? first.promise : latest.promise
    },
  })

  const firstSave = queue.enqueue('first')
  const pendingSave = queue.enqueue('latest')
  queue.setRevision('remote-r2')
  first.resolve({ content: 'first', revision: 'r1' })
  await firstSave
  await Promise.resolve()

  assert.deepEqual(calls, [
    { content: 'first', revision: 'r0' },
    { content: 'latest', revision: 'remote-r2' },
  ])
  latest.resolve({ content: 'latest', revision: 'r3' })
  await pendingSave
})

test('superseding a generation makes its active acknowledgement inert and discards its pending write', async () => {
  const active = deferred()
  const next = deferred()
  const calls = []
  const applied = []
  const committed = []
  const queue = createTasksWriteQueue({
    initialRevision: 'r0',
    onAccepted: (snapshot) => applied.push(snapshot),
    onCommitted: (snapshot) => committed.push(snapshot),
    save: (content, revision) => {
      calls.push({ content, revision })
      return calls.length === 1 ? active.promise : next.promise
    },
  })

  const activeSave = queue.enqueue('active A')
  const discardedSave = queue.enqueue('pending B')
  queue.supersede('remote-r2')
  assert.equal(await discardedSave, undefined)

  const explicitSave = queue.enqueue('explicit C')
  active.resolve({ content: 'active A', revision: 'r1' })
  await activeSave
  await Promise.resolve()

  assert.deepEqual(applied, [])
  assert.deepEqual(committed, [])
  assert.deepEqual(calls, [
    { content: 'active A', revision: 'r0' },
    { content: 'explicit C', revision: 'remote-r2' },
  ])
  next.resolve({ content: 'explicit C', revision: 'r3' })
  await explicitSave
})

test('invalidating a task queue prevents a previous workspace response from mutating the new one', async () => {
  const pending = deferred()
  const applied = []
  const queue = createTasksWriteQueue({
    initialRevision: undefined,
    onAccepted: (snapshot) => applied.push(snapshot),
    save: () => pending.promise,
  })

  const save = queue.enqueue('workspace-a edit')
  await Promise.resolve()
  queue.invalidate()
  pending.resolve({ content: 'workspace-a edit', revision: 'a1' })
  await save

  assert.deepEqual(applied, [])
})

test('coalesces an identical latest write instead of persisting the same revision twice', async () => {
  const pending = deferred()
  const calls = []
  const queue = createTasksWriteQueue({
    initialRevision: 'r0',
    onAccepted() {},
    save: (content, revision) => {
      calls.push({ content, revision })
      return pending.promise
    },
  })

  const first = queue.enqueue('same content')
  const duplicate = queue.enqueue('same content')
  await Promise.resolve()

  assert.equal(duplicate, first, 'concurrent duplicate saves should share one operation')
  assert.deepEqual(calls, [{ content: 'same content', revision: 'r0' }])

  pending.resolve({ content: 'same content', revision: 'r1' })
  await Promise.all([first, duplicate])
})

test('a revision conflict blocks queued writes instead of silently overwriting remote content', async () => {
  const first = deferred()
  const calls = []
  const rejected = []
  const conflict = new Error('revision conflict')
  const queue = createTasksWriteQueue({
    initialRevision: 'r0',
    isBlockingFailure: (error) => error === conflict,
    onAccepted() {},
    onRejected: (error) => rejected.push(error),
    save: (content, revision) => {
      calls.push({ content, revision })
      return first.promise
    },
  })

  const firstSave = queue.enqueue('first local edit')
  const queuedSave = queue.enqueue('newest local edit')
  await Promise.resolve()
  first.reject(conflict)

  await assert.rejects(firstSave, /revision conflict/)
  assert.equal(await queuedSave, undefined)
  assert.deepEqual(calls, [{ content: 'first local edit', revision: 'r0' }])
  assert.deepEqual(rejected, [conflict])
})

test('reconnects the tasks stream after a post-snapshot disconnect and clears stale only on recovery', async () => {
  const sockets = []
  const timers = new Map()
  const stale = []
  const snapshots = []
  let nextTimer = 1
  const stream = createTasksStream({
    loadSnapshot: async () => ({ content: 'http', revision: 'http-r' }),
    onSnapshot: (snapshot) => snapshots.push(snapshot),
    onStaleChange: (value) => stale.push(value),
    openSocket: () => {
      const socket = {
        close() {},
        onclose: null,
        onerror: null,
        onmessage: null,
      }
      sockets.push(socket)
      return socket
    },
    timers: {
      clearTimeout: (id) => timers.delete(id),
      setTimeout: (callback, delay) => {
        const id = nextTimer++
        timers.set(id, { callback, delay })
        return id
      },
    },
    workspaceId: 'workspace-a',
  })

  assert.equal(sockets.length, 1)
  sockets[0].onmessage({
    data: JSON.stringify({ type: 'tasks-snapshot', content: 'one', revision: 'r1' }),
  })
  sockets[0].onclose()
  assert.equal(stale.at(-1), true)
  const reconnect = [...timers.values()].find(({ delay }) => delay < 8_000)
  assert.ok(reconnect, 'disconnect must schedule a reconnect')

  reconnect.callback()
  assert.equal(sockets.length, 2)
  sockets[1].onmessage({
    data: JSON.stringify({ type: 'tasks-snapshot', content: 'two', revision: 'r2' }),
  })
  assert.equal(stale.at(-1), false)
  assert.deepEqual(snapshots.at(-1), { content: 'two', revision: 'r2' })
  stream.dispose()
})

test('a stale HTTP recovery cannot overwrite a newer WebSocket snapshot', async () => {
  const recovery = deferred()
  const sockets = []
  const timers = new Map()
  const snapshots = []
  let nextTimer = 1
  const stream = createTasksStream({
    loadSnapshot: () => recovery.promise,
    onSnapshot: (snapshot) => snapshots.push(snapshot),
    onStaleChange() {},
    openSocket: () => {
      const socket = {
        close() {},
        onclose: null,
        onerror: null,
        onmessage: null,
      }
      sockets.push(socket)
      return socket
    },
    timers: {
      clearTimeout: (id) => timers.delete(id),
      setTimeout: (callback, delay) => {
        const id = nextTimer++
        timers.set(id, { callback, delay })
        return id
      },
    },
    workspaceId: 'workspace-a',
  })

  sockets[0].onmessage({
    data: JSON.stringify({ type: 'tasks-snapshot', content: 'initial', revision: 'r1' }),
  })
  sockets[0].onclose()
  const reconnect = [...timers.values()].find(({ delay }) => delay < 8_000)
  assert.ok(reconnect)
  reconnect.callback()
  sockets[1].onmessage({
    data: JSON.stringify({ type: 'tasks-snapshot', content: 'new live value', revision: 'r2' }),
  })

  recovery.resolve({ content: 'stale recovery value', revision: 'old' })
  await recovery.promise
  await Promise.resolve()

  assert.deepEqual(snapshots.at(-1), { content: 'new live value', revision: 'r2' })
  stream.dispose()
})

test('a synchronous WebSocket construction failure enters recovery and schedules reconnect', async () => {
  const timers = new Map()
  const stale = []
  const snapshots = []
  let nextTimer = 1
  const stream = createTasksStream({
    loadSnapshot: async () => ({ content: 'http fallback', revision: 'r1' }),
    onSnapshot: (snapshot) => snapshots.push(snapshot),
    onStaleChange: (value) => stale.push(value),
    openSocket: () => {
      throw new DOMException('blocked', 'SecurityError')
    },
    timers: {
      clearTimeout: (id) => timers.delete(id),
      setTimeout: (callback, delay) => {
        const id = nextTimer++
        timers.set(id, { callback, delay })
        return id
      },
    },
    workspaceId: 'workspace-a',
  })

  await Promise.resolve()
  assert.deepEqual(stale, [true])
  assert.deepEqual(snapshots, [{ content: 'http fallback', revision: 'r1' }])
  assert.ok([...timers.values()].some(({ delay }) => delay === 500))
  stream.dispose()
})

test('an oversized tasks message is disconnected before reaching application state', async () => {
  const sockets = []
  const timers = new Map()
  const stale = []
  const snapshots = []
  let closeCalls = 0
  let nextTimer = 1
  const stream = createTasksStream({
    loadSnapshot: async () => ({ content: 'bounded fallback', revision: 'r1' }),
    onSnapshot: (snapshot) => snapshots.push(snapshot),
    onStaleChange: (value) => stale.push(value),
    openSocket: () => {
      const socket = {
        close() {
          closeCalls += 1
        },
        onclose: null,
        onerror: null,
        onmessage: null,
      }
      sockets.push(socket)
      return socket
    },
    timers: {
      clearTimeout: (id) => timers.delete(id),
      setTimeout: (callback, delay) => {
        const id = nextTimer++
        timers.set(id, { callback, delay })
        return id
      },
    },
    workspaceId: 'workspace-a',
  })

  sockets[0].onmessage({
    data: JSON.stringify({ type: 'tasks-snapshot', content: 'x'.repeat(900 * 1024 + 1) }),
  })
  await Promise.resolve()

  assert.equal(closeCalls, 1)
  assert.equal(stale.at(-1), true)
  assert.deepEqual(snapshots, [{ content: 'bounded fallback', revision: 'r1' }])
  stream.dispose()
})
