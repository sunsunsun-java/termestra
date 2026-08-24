// @vitest-environment jsdom

import type { IMarker } from '@xterm/xterm'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { TerminalBookmarkRail } from '../web/src/terminal/TerminalBookmarkRail.js'
import {
  isTerminalBookmarkSubmission,
  layoutTerminalBookmarkPositions,
  normalizeTerminalBookmarkPreview,
  TerminalBookmarkRegistry,
} from '../web/src/terminal/terminal-bookmarks.js'

afterEach(cleanup)

const createMarker = (initialLine: number) => {
  let disposed = false
  let line = initialLine
  const listeners = new Set<() => void>()
  const marker = {
    dispose() {
      if (disposed) return
      disposed = true
      line = -1
      for (const listener of listeners) listener()
    },
    get id() {
      return initialLine
    },
    get isDisposed() {
      return disposed
    },
    get line() {
      return line
    },
    onDispose(listener: () => void) {
      listeners.add(listener)
      return { dispose: () => listeners.delete(listener) }
    },
  } as IMarker
  return marker
}

describe('terminal input bookmarks', () => {
  test('only treats an unmodified Enter chunk as a submission', () => {
    expect(isTerminalBookmarkSubmission('\r')).toBe(true)
    expect(isTerminalBookmarkSubmission('\n')).toBe(true)
    expect(isTerminalBookmarkSubmission('hello\r')).toBe(false)
    expect(isTerminalBookmarkSubmission('\x1b[200~hello\r\x1b[201~')).toBe(false)
  })

  test('normalizes and bounds the visible terminal-line preview', () => {
    expect(normalizeTerminalBookmarkPreview('  ›   fix the tests  ')).toBe('› fix the tests')
    expect(normalizeTerminalBookmarkPreview('x'.repeat(120))).toHaveLength(96)
    expect(normalizeTerminalBookmarkPreview('x'.repeat(120))).toMatch(/…$/)
  })

  test('bounds markers and removes one when xterm disposes it', () => {
    const onChange = vi.fn()
    const registry = new TerminalBookmarkRegistry(onChange, 2)
    const first = createMarker(10)
    const second = createMarker(20)
    const third = createMarker(30)

    registry.add(first, 'first', 1)
    registry.add(second, 'second', 2)
    registry.add(third, 'third', 3)

    expect(first.isDisposed).toBe(true)
    expect(registry.snapshot(41).map((bookmark) => bookmark.preview)).toEqual([
      'second',
      'third',
    ])
    expect(registry.snapshot(41).map((bookmark) => bookmark.positionPercent)).toEqual([50, 75])

    second.dispose()
    expect(registry.snapshot(41).map((bookmark) => bookmark.preview)).toEqual(['third'])
    expect(onChange).toHaveBeenCalledTimes(4)
  })

  test('nudges dense ticks apart without changing their order', () => {
    const bookmarks = [2, 3, 90].map((positionPercent, index) => ({
      createdAt: index,
      id: index,
      line: index,
      positionPercent,
      preview: '',
    }))

    expect(layoutTerminalBookmarkPositions(bookmarks)).toEqual([2, 7, 90])
  })

  test('renders accessible edge ticks and selects the requested marker', () => {
    const onSelect = vi.fn()
    render(
      <TerminalBookmarkRail
        activeBookmarkId={2}
        bookmarks={[
          {
            createdAt: new Date('2026-08-24T10:00:00').getTime(),
            id: 2,
            line: 25,
            positionPercent: 25,
            preview: 'check the reconnect path',
          },
        ]}
        onSelect={onSelect}
      />
    )

    const button = screen.getByRole('button', { name: /Jump to input 1 of 1/ })
    expect(screen.getByRole('navigation', { name: 'Input bookmarks' })).toBeTruthy()
    expect(screen.getByText('Current Run · available scrollback only')).toBeTruthy()
    expect(button.className).toContain('is-active')
    expect(button.getAttribute('style')).toContain('top: 25%')

    fireEvent.click(button)
    expect(onSelect).toHaveBeenCalledWith(2)
  })

})
