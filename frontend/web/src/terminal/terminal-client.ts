type TerminalControlServerMessage =
  | { type: 'error'; message: string }
  | { type: 'exit'; code: number | null }
  | { type: 'restore'; snapshot: string }

interface TerminalClientOptions {
  initialSize?: {
    cols: number
    pixelHeight?: number
    pixelWidth?: number
    rows: number
  }
  onError: (message: string) => void
  onExit: (code: number | null) => void
  onOutput: (chunk: string, acknowledge: (bytes: number) => void) => void
  onRestore: (snapshot: string) => void
  runId: string
}

const MAX_PENDING_RESTORE_BYTES = 256 * 1024
const MAX_PENDING_RESTORE_CHUNKS = 2_048
const MAX_OUTPUT_CHUNK_BYTES = 256 * 1024
// Matches the backend snapshot's JSON-safe transport budget and leaves room
// for the control envelope within the 1 MiB WebSocket frame.
const MAX_RESTORE_SNAPSHOT_BYTES = 900 * 1024
const MAX_CONTROL_MESSAGE_CHARS = MAX_RESTORE_SNAPSHOT_BYTES + 4_096
const MAX_CONTROL_ERROR_CHARS = 4_096
// Must stay aligned with TerminalWebSocketHandler.MAX_IO_MESSAGE_BYTES. A browser
// WebSocket message maps to one backend input message; larger pastes are rejected
// locally instead of letting the server tear down the terminal pair.
const MAX_INPUT_MESSAGE_BYTES = 256 * 1024
const MAX_SOCKET_BUFFERED_BYTES = 1024 * 1024
const RESTORE_TIMEOUT_MS = 15_000

interface TerminalClient {
  dispose: () => void
  resize: (cols: number, rows: number, pixelWidth?: number, pixelHeight?: number) => void
  sendBinaryInput: (chunk: string) => boolean
  sendInput: (chunk: string) => boolean
}

const toWebSocketUrl = (path: string, params: Record<string, number | string | undefined> = {}) => {
  const url = new URL(path, window.location.href)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) url.searchParams.set(key, String(value))
  }
  return url.toString()
}

const isTerminalControlServerMessage = (value: unknown): value is TerminalControlServerMessage => {
  if (typeof value !== 'object' || value === null || !('type' in value)) return false
  if (value.type === 'restore') return 'snapshot' in value && typeof value.snapshot === 'string'
  if (value.type === 'error') return 'message' in value && typeof value.message === 'string'
  if (value.type !== 'exit' || !('code' in value)) return false
  return value.code === null || (typeof value.code === 'number' && Number.isInteger(value.code))
}

const socketCanClose = (socket: WebSocket): boolean => socket.readyState < WebSocket.CLOSING

export const createTerminalClient = ({
  initialSize,
  onError,
  onExit,
  onOutput,
  onRestore,
  runId,
}: TerminalClientOptions): TerminalClient => {
  const clientId = crypto.randomUUID()
  const connectionParams = { ...initialSize, clientId }
  const ioSocket = new WebSocket(toWebSocketUrl(`/ws/terminal/${runId}/io`, connectionParams))
  let controlSocket: WebSocket
  try {
    controlSocket = new WebSocket(
      toWebSocketUrl(`/ws/terminal/${runId}/control`, connectionParams)
    )
  } catch (error) {
    if (socketCanClose(ioSocket)) ioSocket.close()
    throw error
  }
  const textEncoder = new TextEncoder()
  let lifecycle: 'active' | 'disposed' | 'failed' | 'stopped' = 'active'
  let restored = false
  let pendingOutputBytes = 0
  const pendingOutput: string[] = []
  let pendingResize: {
    cols: number
    rows: number
    pixelWidth?: number
    pixelHeight?: number
  } | null = null
  let restoreTimeout: number | undefined

  const clearPendingOutput = () => {
    pendingOutput.splice(0)
    pendingOutputBytes = 0
  }

  const clearRestoreTimeout = () => {
    if (restoreTimeout === undefined) return
    window.clearTimeout(restoreTimeout)
    restoreTimeout = undefined
  }

  const closePair = () => {
    if (socketCanClose(ioSocket)) ioSocket.close()
    if (socketCanClose(controlSocket)) controlSocket.close()
  }

  const stop = (nextLifecycle: 'disposed' | 'failed' | 'stopped') => {
    if (lifecycle !== 'active') return false
    lifecycle = nextLifecycle
    clearRestoreTimeout()
    clearPendingOutput()
    pendingResize = null
    closePair()
    return true
  }

  const fail = (message: string) => {
    if (!stop('failed')) return
    onError(message)
  }

  const stopAfterExit = (code: number | null) => {
    if (!stop('stopped')) return
    onExit(code)
  }

  const sendControl = (message: object): boolean => {
    if (lifecycle !== 'active' || controlSocket.readyState !== WebSocket.OPEN) return false
    const serialized = JSON.stringify(message)
    const bytes = textEncoder.encode(serialized).byteLength
    if (controlSocket.bufferedAmount + bytes > MAX_SOCKET_BUFFERED_BYTES) {
      fail('Terminal control output exceeded the safe buffer. Reopen the terminal.')
      return false
    }
    controlSocket.send(serialized)
    return true
  }

  const sendResize = () => {
    if (!pendingResize || controlSocket.readyState !== WebSocket.OPEN) return
    if (sendControl({ type: 'resize', ...pendingResize })) pendingResize = null
  }

  const acknowledgeOutput = (chunkBytes: number) => {
    let acknowledged = false
    return (bytes: number) => {
      if (acknowledged || lifecycle !== 'active') return
      acknowledged = true
      if (!Number.isSafeInteger(bytes) || bytes <= 0 || bytes > chunkBytes) {
        fail('Terminal output acknowledgement was invalid. Reopen the terminal.')
        return
      }
      if (!sendControl({ type: 'output_ack', bytes }) && lifecycle === 'active') {
        fail('Terminal control connection is unavailable. Reopen the terminal to reconnect.')
      }
    }
  }

  const deliverOutput = (chunk: string, chunkBytes: number) => {
    if (lifecycle !== 'active') return
    try {
      onOutput(chunk, acknowledgeOutput(chunkBytes))
    } catch {
      fail('Terminal could not render output. Reopen the terminal.')
    }
  }

  const handleSocketClose = (channel: 'control' | 'io') => {
    fail(`Terminal ${channel} connection closed. Reopen the terminal to reconnect.`)
  }

  const handleSocketError = (channel: 'control' | 'io') => {
    fail(`Terminal ${channel} connection failed. Reopen the terminal to reconnect.`)
  }

  ioSocket.onmessage = (event) => {
    if (lifecycle !== 'active') return
    if (typeof event.data !== 'string') {
      fail('Terminal received invalid output. Reopen the terminal.')
      return
    }
    const chunk = event.data
    if (chunk.length === 0) return
    if (chunk.length > MAX_OUTPUT_CHUNK_BYTES) {
      fail('Terminal output exceeded the safe chunk limit. Reopen the terminal.')
      return
    }
    const chunkBytes = textEncoder.encode(chunk).byteLength
    if (chunkBytes > MAX_OUTPUT_CHUNK_BYTES) {
      fail('Terminal output exceeded the safe chunk limit. Reopen the terminal.')
      return
    }
    if (!restored) {
      if (
        pendingOutput.length >= MAX_PENDING_RESTORE_CHUNKS ||
        pendingOutputBytes + chunkBytes > MAX_PENDING_RESTORE_BYTES
      ) {
        fail('Terminal restore output exceeded the safe buffer. Reopen the terminal.')
        return
      }
      pendingOutputBytes += chunkBytes
      pendingOutput.push(chunk)
      return
    }
    deliverOutput(chunk, chunkBytes)
  }
  ioSocket.onclose = () => handleSocketClose('io')
  ioSocket.onerror = () => handleSocketError('io')

  controlSocket.onopen = () => {
    if (lifecycle !== 'active') return
    sendResize()
  }
  controlSocket.onmessage = (event) => {
    if (lifecycle !== 'active') return
    const raw = String(event.data)
    if (raw.length > MAX_CONTROL_MESSAGE_CHARS) {
      fail('Terminal control message exceeded the safe limit. Reopen the terminal.')
      return
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch (error) {
      if (!(error instanceof SyntaxError)) throw error
      fail('Terminal received an invalid control message. Reopen the terminal.')
      return
    }
    if (!isTerminalControlServerMessage(parsed)) {
      fail('Terminal received an invalid control message. Reopen the terminal.')
      return
    }
    const message = parsed
    if (message.type === 'exit') {
      stopAfterExit(message.code)
      return
    }
    if (message.type === 'error') {
      if (message.message.length > MAX_CONTROL_ERROR_CHARS) {
        fail('Terminal error message exceeded the safe limit. Reopen the terminal.')
        return
      }
      fail(message.message)
      return
    }
    if (restored) {
      fail('Terminal received a duplicate restore message. Reopen the terminal.')
      return
    }
    if (textEncoder.encode(message.snapshot).byteLength > MAX_RESTORE_SNAPSHOT_BYTES) {
      fail('Terminal restore snapshot exceeded the safe limit. Reopen the terminal.')
      return
    }

    clearRestoreTimeout()
    try {
      onRestore(message.snapshot)
    } catch {
      fail('Terminal could not render the restore snapshot. Reopen the terminal.')
      return
    }
    if (lifecycle !== 'active') return
    restored = true
    if (!sendControl({ type: 'restore_complete' })) {
      if (lifecycle === 'active') {
        fail('Terminal control connection is unavailable. Reopen the terminal to reconnect.')
      }
      return
    }
    for (const chunk of pendingOutput.splice(0)) {
      const chunkBytes = textEncoder.encode(chunk).byteLength
      deliverOutput(chunk, chunkBytes)
      if (lifecycle !== 'active') break
    }
    pendingOutputBytes = 0
  }
  controlSocket.onclose = () => handleSocketClose('control')
  controlSocket.onerror = () => handleSocketError('control')

  restoreTimeout = window.setTimeout(() => {
    fail('Terminal restore timed out. Reopen the terminal to reconnect.')
  }, RESTORE_TIMEOUT_MS)

  return {
    dispose() {
      stop('disposed')
    },
    resize(cols, rows, pixelWidth, pixelHeight) {
      if (lifecycle !== 'active') return
      pendingResize = { cols, rows }
      if (pixelWidth !== undefined) pendingResize.pixelWidth = pixelWidth
      if (pixelHeight !== undefined) pendingResize.pixelHeight = pixelHeight
      sendResize()
    },
    sendBinaryInput(chunk) {
      if (lifecycle !== 'active' || ioSocket.readyState !== WebSocket.OPEN) return false
      if (chunk.length > MAX_INPUT_MESSAGE_BYTES) {
        fail('Terminal input exceeded the 256 KiB message limit. Split the input and try again.')
        return false
      }
      if (ioSocket.bufferedAmount + chunk.length > MAX_SOCKET_BUFFERED_BYTES) {
        fail('Terminal input exceeded the safe buffer. Reopen the terminal.')
        return false
      }
      const bytes = new Uint8Array(chunk.length)
      for (let index = 0; index < chunk.length; index++) {
        bytes[index] = chunk.charCodeAt(index) & 0xff
      }
      ioSocket.send(bytes)
      return true
    },
    sendInput(chunk) {
      if (lifecycle !== 'active' || ioSocket.readyState !== WebSocket.OPEN) return false
      const bytes = textEncoder.encode(chunk).byteLength
      if (bytes > MAX_INPUT_MESSAGE_BYTES) {
        fail('Terminal input exceeded the 256 KiB message limit. Split the input and try again.')
        return false
      }
      if (ioSocket.bufferedAmount + bytes > MAX_SOCKET_BUFFERED_BYTES) {
        fail('Terminal input exceeded the safe buffer. Reopen the terminal.')
        return false
      }
      ioSocket.send(chunk)
      return true
    },
  }
}
