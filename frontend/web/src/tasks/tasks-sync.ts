export interface TasksSnapshot {
  content: string
  revision?: string
}

interface TasksWriteQueueOptions {
  initialRevision: string | undefined
  isBlockingFailure?: (error: unknown) => boolean
  onAccepted: (snapshot: TasksSnapshot) => void
  onCommitted?: (snapshot: TasksSnapshot) => void
  onFailed?: (error: unknown) => void
  onRejected?: (error: unknown) => void
  save: (content: string, revision: string | undefined) => Promise<TasksSnapshot>
}

export interface TasksWriteQueue {
  enqueue: (content: string) => Promise<TasksSnapshot | undefined>
  hasPendingContent: (content: string) => boolean
  hasPendingWrites: () => boolean
  invalidate: () => void
  setRevision: (revision: string | undefined) => void
  supersede: (revision: string | undefined) => void
}

/**
 * Marks an intentionally fire-and-forget rejection as observed without
 * changing the rejecting promise returned to an explicit caller.
 */
export const createObservedRejection = (error: unknown): Promise<never> => {
  const operation = Promise.reject(error)
  void operation.catch(() => undefined)
  return operation
}

interface TasksWriteRequest {
  content: string
  generation: number
  promise: Promise<TasksSnapshot | undefined>
  reject: (error: unknown) => void
  resolve: (snapshot: TasksSnapshot | undefined) => void
}

const createWriteRequest = (content: string, generation: number): TasksWriteRequest => {
  let reject!: (error: unknown) => void
  let resolve!: (snapshot: TasksSnapshot | undefined) => void
  const promise = new Promise<TasksSnapshot | undefined>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  // UI event handlers may deliberately fire-and-forget. Observe rejection
  // once per bounded request while preserving the rejecting promise for
  // explicit callers that await it.
  void promise.catch(() => undefined)
  return { content, generation, promise, reject, resolve }
}

/**
 * Serializes whole-document writes behind one bounded interface. At most one
 * request is in flight and one mutable latest-value request is pending. Rapid
 * edits therefore retain two document strings and two completion promises,
 * regardless of how many intermediate whole-document saves are superseded.
 *
 * Every caller waiting on the pending slot observes the eventual latest
 * write's result. A blocking revision failure invalidates that whole pending
 * generation instead of replaying it against a remote revision.
 */
export const createTasksWriteQueue = ({
  initialRevision,
  isBlockingFailure,
  onAccepted,
  onCommitted,
  onFailed,
  onRejected,
  save,
}: TasksWriteQueueOptions): TasksWriteQueue => {
  let generation = 0
  let revision = initialRevision
  let revisionEpoch = 0
  let active: TasksWriteRequest | undefined
  let pending: TasksWriteRequest | undefined

  const discardPendingGeneration = (discardedGeneration: number) => {
    if (pending?.generation !== discardedGeneration) return
    const discarded = pending
    pending = undefined
    discarded.resolve(undefined)
  }

  const hasPendingGeneration = (expectedGeneration: number) =>
    pending?.generation === expectedGeneration

  const supersedeGeneration = (nextRevision: string | undefined) => {
    const supersededGeneration = generation
    generation += 1
    revision = nextRevision
    revisionEpoch += 1
    discardPendingGeneration(supersededGeneration)
  }

  const drain = () => {
    if (active || !pending) return
    const request = pending
    pending = undefined
    active = request
    const startedRevisionEpoch = revisionEpoch

    void (async () => {
      try {
        const snapshot = await save(request.content, revision)
        if (request.generation === generation) {
          // A stream/recovery snapshot received while this request was in
          // flight represents newer revision knowledge. A late HTTP response
          // must not move the next pending write back to its older base.
          if (revisionEpoch === startedRevisionEpoch) {
            revision = snapshot.revision ?? revision
          }
          onCommitted?.(snapshot)
          if (!hasPendingGeneration(generation)) onAccepted(snapshot)
        }
        request.resolve(snapshot)
      } catch (error) {
        if (request.generation === generation) {
          onFailed?.(error)
          if (isBlockingFailure?.(error)) {
            const rejectedGeneration = generation
            generation += 1
            discardPendingGeneration(rejectedGeneration)
            onRejected?.(error)
          } else if (!hasPendingGeneration(generation)) {
            onRejected?.(error)
          }
        }
        request.reject(error)
      } finally {
        if (active === request) active = undefined
        drain()
      }
    })()
  }

  return {
    enqueue(content) {
      if (pending?.generation === generation) {
        pending.content = content
        return pending.promise
      }
      if (active?.generation === generation && active.content === content) {
        return active.promise
      }
      pending = createWriteRequest(content, generation)
      const operation = pending.promise
      drain()
      return operation
    },
    hasPendingContent(content) {
      return (
        (active?.generation === generation && active.content === content) ||
        (pending?.generation === generation && pending.content === content)
      )
    },
    hasPendingWrites() {
      return active?.generation === generation || pending?.generation === generation
    },
    invalidate() {
      supersedeGeneration(undefined)
    },
    setRevision(nextRevision) {
      revision = nextRevision
      revisionEpoch += 1
    },
    supersede(nextRevision) {
      supersedeGeneration(nextRevision)
    },
  }
}

type TasksSocketLike = Pick<WebSocket, 'close' | 'onclose' | 'onerror' | 'onmessage'>

interface TasksStreamTimers {
  clearTimeout: (id: number) => void
  setTimeout: (callback: () => void, delay: number) => number
}

interface TasksStreamOptions {
  loadSnapshot: (signal: AbortSignal) => Promise<TasksSnapshot>
  onLoadError?: (error: unknown) => void
  onSnapshot: (snapshot: TasksSnapshot) => void
  onStaleChange: (stale: boolean) => void
  openSocket: (workspaceId: string) => TasksSocketLike
  timers?: TasksStreamTimers
  workspaceId: string
}

interface TasksStream {
  dispose: () => void
}

const INITIAL_RECONNECT_DELAY_MS = 500
const MAX_RECONNECT_DELAY_MS = 10_000
const SNAPSHOT_TIMEOUT_MS = 8_000
const MAX_TASKS_TRANSPORT_CONTENT_BYTES = 900 * 1024
const MAX_TASKS_MESSAGE_CHARS = MAX_TASKS_TRANSPORT_CONTENT_BYTES + 4_096
const tasksTextEncoder = new TextEncoder()

// The raw message-length gate above accounts for JSON escaping before parse;
// this second gate bounds the decoded string's retained UTF-8 size.
const tasksContentFitsTransport = (content: string): boolean =>
  tasksTextEncoder.encode(content).byteLength <= MAX_TASKS_TRANSPORT_CONTENT_BYTES

const browserTimers: TasksStreamTimers = {
  clearTimeout: (id) => window.clearTimeout(id),
  setTimeout: (callback, delay) => window.setTimeout(callback, delay),
}

/**
 * Maintains snapshot-plus-stream ownership for one workspace. HTTP is a
 * bounded recovery snapshot; only a valid WebSocket snapshot marks the live
 * stream healthy again.
 */
export const createTasksStream = ({
  loadSnapshot,
  onLoadError,
  onSnapshot,
  onStaleChange,
  openSocket,
  timers = browserTimers,
  workspaceId,
}: TasksStreamOptions): TasksStream => {
  let attempt = 0
  let disposed = false
  let reconnectTimer: number | undefined
  let snapshotTimer: number | undefined
  let socket: TasksSocketLike | null = null
  let snapshotEpoch = 0
  let recoveryController: AbortController | null = null

  const clearTimer = (timer: number | undefined) => {
    if (timer !== undefined) timers.clearTimeout(timer)
  }

  const recoverSnapshot = () => {
    recoveryController?.abort()
    const controller = new AbortController()
    recoveryController = controller
    const recoveryEpoch = ++snapshotEpoch
    void loadSnapshot(controller.signal)
      .then((snapshot) => {
        if (!disposed && recoveryEpoch === snapshotEpoch) onSnapshot(snapshot)
      })
      .catch((error: unknown) => {
        if (!disposed && recoveryEpoch === snapshotEpoch) onLoadError?.(error)
      })
      .finally(() => {
        if (recoveryController === controller) recoveryController = null
      })
  }

  const scheduleReconnect = () => {
    if (disposed || reconnectTimer !== undefined) return
    const delay = Math.min(
      INITIAL_RECONNECT_DELAY_MS * 2 ** attempt,
      MAX_RECONNECT_DELAY_MS
    )
    attempt = Math.min(attempt + 1, 5)
    reconnectTimer = timers.setTimeout(() => {
      reconnectTimer = undefined
      connect()
    }, delay)
  }

  const connect = () => {
    if (disposed) return
    let ended = false
    try {
      socket = openSocket(workspaceId)
    } catch {
      socket = null
      onStaleChange(true)
      recoverSnapshot()
      scheduleReconnect()
      return
    }
    const activeSocket = socket
    snapshotTimer = timers.setTimeout(() => {
      snapshotTimer = undefined
      if (ended || disposed) return
      ended = true
      onStaleChange(true)
      recoverSnapshot()
      activeSocket.close()
      scheduleReconnect()
    }, SNAPSHOT_TIMEOUT_MS)

    const disconnect = () => {
      if (ended || disposed) return
      ended = true
      clearTimer(snapshotTimer)
      snapshotTimer = undefined
      onStaleChange(true)
      recoverSnapshot()
      activeSocket.close()
      scheduleReconnect()
    }
    activeSocket.onerror = disconnect
    activeSocket.onclose = disconnect
    activeSocket.onmessage = (event) => {
      if (ended || disposed) return
      const raw = String(event.data)
      if (raw.length > MAX_TASKS_MESSAGE_CHARS) {
        disconnect()
        return
      }
      let payload: { content?: unknown; revision?: unknown; type?: unknown }
      try {
        payload = JSON.parse(raw) as typeof payload
      } catch {
        return
      }
      if (payload.type !== 'tasks-snapshot' && payload.type !== 'tasks-updated') return
      if (typeof payload.content !== 'string') return
      if (!tasksContentFitsTransport(payload.content)) {
        disconnect()
        return
      }
      clearTimer(snapshotTimer)
      snapshotTimer = undefined
      attempt = 0
      snapshotEpoch += 1
      recoveryController?.abort()
      recoveryController = null
      onSnapshot({
        content: payload.content,
        ...(typeof payload.revision === 'string' ? { revision: payload.revision } : {}),
      })
      onStaleChange(false)
    }
  }

  connect()
  return {
    dispose() {
      disposed = true
      snapshotEpoch += 1
      recoveryController?.abort()
      recoveryController = null
      clearTimer(reconnectTimer)
      clearTimer(snapshotTimer)
      reconnectTimer = undefined
      snapshotTimer = undefined
      socket?.close()
      socket = null
    },
  }
}
