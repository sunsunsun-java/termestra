// @vitest-environment jsdom

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

const terminalState = vi.hoisted(() => ({ instances: [] as unknown[] }))
const clientState = vi.hoisted(() => ({ instances: [] as unknown[] }))

vi.mock('@xterm/xterm', () => {
  class FakeMarker {
    private disposed = false
    private readonly listeners = new Set<() => void>()

    constructor(
      readonly id: number,
      private currentLine: number
    ) {}

    get isDisposed() {
      return this.disposed
    }

    get line() {
      return this.disposed ? -1 : this.currentLine
    }

    dispose() {
      if (this.disposed) return
      this.disposed = true
      for (const listener of this.listeners) listener()
    }

    onDispose(listener: () => void) {
      this.listeners.add(listener)
      return { dispose: () => this.listeners.delete(listener) }
    }
  }

  class Terminal {
    readonly buffer = {
      active: null as unknown,
      normal: {
        baseY: 10,
        cursorY: 2,
        getLine: () => ({ translateToString: () => '› review the lifecycle' }),
        length: 100,
        type: 'normal' as const,
      },
    }
    readonly cols = 80
    readonly decorations: Array<{ dispose: ReturnType<typeof vi.fn> }> = []
    readonly markers: FakeMarker[] = []
    markerAvailable = true
    readonly rows = 24
    readonly scrollToLine = vi.fn()
    readonly unicode = { activeVersion: '' }
    readonly dispose = vi.fn(() => {
      for (const marker of this.markers) marker.dispose()
    })
    private dataListener: ((chunk: string) => void) | null = null
    private writeParsedListener: (() => void) | null = null

    constructor() {
      this.buffer.active = this.buffer.normal
      terminalState.instances.push(this)
    }

    attachCustomKeyEventHandler() {}
    loadAddon() {}
    open() {}
    clear() {}
    focus() {}

    emitData(chunk: string) {
      this.dataListener?.(chunk)
    }

    emitWriteParsed() {
      this.writeParsedListener?.()
    }

    onBinary() {
      return { dispose: vi.fn() }
    }

    onData(listener: (chunk: string) => void) {
      this.dataListener = listener
      return { dispose: vi.fn(() => (this.dataListener = null)) }
    }

    onWriteParsed(listener: () => void) {
      this.writeParsedListener = listener
      return { dispose: vi.fn(() => (this.writeParsedListener = null)) }
    }

    registerDecoration() {
      const decoration = { dispose: vi.fn() }
      this.decorations.push(decoration)
      return decoration
    }

    registerMarker() {
      if (!this.markerAvailable) return undefined
      const marker = new FakeMarker(
        this.markers.length + 1,
        this.buffer.normal.baseY + this.buffer.normal.cursorY
      )
      this.markers.push(marker)
      return marker
    }

    write(_chunk: string, callback?: () => void) {
      callback?.()
      this.emitWriteParsed()
    }
  }

  return { Terminal }
})

vi.mock('@xterm/addon-fit', () => ({
  FitAddon: class {
    dispose() {}
    fit() {}
  },
}))
vi.mock('@xterm/addon-unicode11', () => ({ Unicode11Addon: class {} }))
vi.mock('@xterm/addon-clipboard', () => ({ ClipboardAddon: class {} }))
vi.mock('@xterm/addon-web-links', () => ({ WebLinksAddon: class {} }))
vi.mock('@xterm/addon-webgl', () => ({
  WebglAddon: class {
    dispose() {}
    onContextLoss() {}
  },
}))

vi.mock('../web/src/terminal/terminal-client.js', () => ({
  createTerminalClient: (options: { onError: (message: string) => void; onExit: () => void }) => {
    let active = true
    const client = {
      dispose: vi.fn(() => {
        active = false
      }),
      fail(message: string) {
        active = false
        options.onError(message)
      },
      exit() {
        active = false
        options.onExit()
      },
      resize: vi.fn(),
      sendBinaryInput: vi.fn(() => active),
      sendInput: vi.fn(() => active),
    }
    clientState.instances.push(client)
    return client
  },
}))

import { useTerminalRun } from '../web/src/terminal/useTerminalRun.js'

type FakeTerminal = {
  buffer: { active: { type: 'alternate' | 'normal' }; normal: { type: 'normal' } }
  decorations: Array<{ dispose: ReturnType<typeof vi.fn> }>
  dispose: ReturnType<typeof vi.fn>
  emitData: (chunk: string) => void
  emitWriteParsed: () => void
  markers: Array<{ isDisposed: boolean }>
  markerAvailable: boolean
  scrollToLine: ReturnType<typeof vi.fn>
}

type FakeClient = {
  exit: () => void
  fail: (message: string) => void
  sendInput: ReturnType<typeof vi.fn>
}

const HookHarness = ({ bookmarksEnabled, runId }: { bookmarksEnabled: boolean; runId: string }) => {
  const terminal = useTerminalRun(runId, 'default', bookmarksEnabled)
  return (
    <div>
      <div ref={terminal.containerRef} />
      <output data-testid="bookmark-count">{terminal.bookmarks.length}</output>
      <output data-testid="bookmark-available">
        {terminal.bookmarkNavigationAvailable ? 'available' : 'unavailable'}
      </output>
      {terminal.bookmarks[0] ? (
        <button type="button" onClick={() => terminal.selectBookmark(terminal.bookmarks[0]!.id)}>
          select bookmark
        </button>
      ) : null}
    </div>
  )
}

const latestTerminal = (): FakeTerminal => terminalState.instances.at(-1) as FakeTerminal
const latestClient = (): FakeClient => clientState.instances.at(-1) as FakeClient

afterEach(() => {
  cleanup()
  terminalState.instances.length = 0
  clientState.instances.length = 0
})

describe('useTerminalRun bookmark lifecycle', () => {
  test('records only delivered normal-buffer submissions and clears them on client failure', async () => {
    render(<HookHarness bookmarksEnabled runId="run-1" />)
    await waitFor(() => expect(terminalState.instances).toHaveLength(1))
    const terminal = latestTerminal()
    const client = latestClient()

    act(() => terminal.emitData('\r'))
    expect(client.sendInput).toHaveBeenCalledWith('\r')
    expect(terminal.markers).toHaveLength(1)
    expect(screen.getByTestId('bookmark-count').textContent).toBe('1')

    fireEvent.click(screen.getByRole('button', { name: 'select bookmark' }))
    expect(terminal.scrollToLine).toHaveBeenCalledWith(11)
    expect(terminal.decorations).toHaveLength(1)

    act(() => client.fail('offline'))
    expect(terminal.markers[0]!.isDisposed).toBe(true)
    expect(terminal.decorations[0]!.dispose).toHaveBeenCalled()
    expect(screen.getByTestId('bookmark-count').textContent).toBe('0')
    expect(screen.getByTestId('bookmark-available').textContent).toBe('unavailable')

    act(() => terminal.emitData('\r'))
    expect(terminal.markers).toHaveLength(1)
  })

  test('does not create bookmarks when disabled or while the alternate buffer is active', async () => {
    const { rerender } = render(<HookHarness bookmarksEnabled={false} runId="run-1" />)
    await waitFor(() => expect(terminalState.instances).toHaveLength(1))
    act(() => latestTerminal().emitData('\r'))
    expect(latestTerminal().markers).toHaveLength(0)

    rerender(<HookHarness bookmarksEnabled runId="run-2" />)
    await waitFor(() => expect(terminalState.instances).toHaveLength(2))
    const terminal = latestTerminal()
    terminal.buffer.active = { type: 'alternate' }
    act(() => {
      terminal.emitWriteParsed()
      terminal.emitData('\r')
    })
    expect(terminal.markers).toHaveLength(0)
    expect(screen.getByTestId('bookmark-available').textContent).toBe('unavailable')
  })

  test('keeps input usable when xterm cannot create a marker', async () => {
    render(<HookHarness bookmarksEnabled runId="run-1" />)
    await waitFor(() => expect(terminalState.instances).toHaveLength(1))
    const terminal = latestTerminal()
    const client = latestClient()
    terminal.markerAvailable = false

    act(() => terminal.emitData('\r'))

    expect(client.sendInput).toHaveBeenCalledWith('\r')
    expect(terminal.markers).toHaveLength(0)
    expect(screen.getByTestId('bookmark-count').textContent).toBe('0')
  })

  test('clears bookmarks when the PTY exits normally', async () => {
    render(<HookHarness bookmarksEnabled runId="run-1" />)
    await waitFor(() => expect(terminalState.instances).toHaveLength(1))
    const terminal = latestTerminal()
    act(() => terminal.emitData('\r'))

    act(() => latestClient().exit())

    expect(terminal.markers[0]!.isDisposed).toBe(true)
    expect(screen.getByTestId('bookmark-count').textContent).toBe('0')
    expect(screen.getByTestId('bookmark-available').textContent).toBe('unavailable')
  })

  test('disposes markers, decoration, and the old terminal when the Run changes', async () => {
    const { rerender } = render(<HookHarness bookmarksEnabled runId="run-1" />)
    await waitFor(() => expect(terminalState.instances).toHaveLength(1))
    const firstTerminal = latestTerminal()
    act(() => firstTerminal.emitData('\r'))
    fireEvent.click(screen.getByRole('button', { name: 'select bookmark' }))

    rerender(<HookHarness bookmarksEnabled runId="run-2" />)
    await waitFor(() => expect(terminalState.instances).toHaveLength(2))
    expect(firstTerminal.markers[0]!.isDisposed).toBe(true)
    expect(firstTerminal.decorations[0]!.dispose).toHaveBeenCalled()
    expect(firstTerminal.dispose).toHaveBeenCalled()
    expect(screen.getByTestId('bookmark-count').textContent).toBe('0')
  })
})
