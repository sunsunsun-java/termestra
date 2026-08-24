import type { IMarker } from '@xterm/xterm'

export const MAX_TERMINAL_BOOKMARKS = 200
const MAX_PREVIEW_LENGTH = 96

export type TerminalBookmark = {
  createdAt: number
  id: number
  line: number
  positionPercent: number
  preview: string
}

type BookmarkEntry = {
  createdAt: number
  disposeSubscription: { dispose: () => void }
  id: number
  marker: IMarker
  preview: string
}

export const isTerminalBookmarkSubmission = (chunk: string): boolean =>
  chunk === '\r' || chunk === '\n'

export const normalizeTerminalBookmarkPreview = (value: string): string => {
  const normalized = value.replace(/\s+/g, ' ').trim()
  if (normalized.length <= MAX_PREVIEW_LENGTH) return normalized
  return `${normalized.slice(0, MAX_PREVIEW_LENGTH - 1)}…`
}

const positionPercent = (line: number, bufferLength: number): number => {
  const lastLine = Math.max(1, bufferLength - 1)
  return Math.max(2, Math.min(98, (line / lastLine) * 100))
}

/**
 * Owns the bounded lifecycle of xterm markers for one mounted Run. Markers are
 * intentionally browser-local: reconnect restore, refresh, and a new Run all
 * start with an empty registry.
 */
export class TerminalBookmarkRegistry {
  private readonly entries = new Map<number, BookmarkEntry>()
  private nextId = 1

  constructor(
    private readonly onChange: () => void,
    private readonly capacity = MAX_TERMINAL_BOOKMARKS
  ) {}

  add(marker: IMarker, preview: string, createdAt = Date.now()): number | null {
    if (this.capacity < 1 || marker.isDisposed || marker.line < 0) return null

    const id = this.nextId++
    const entry: BookmarkEntry = {
      createdAt,
      disposeSubscription: { dispose: () => {} },
      id,
      marker,
      preview: normalizeTerminalBookmarkPreview(preview),
    }
    entry.disposeSubscription = marker.onDispose(() => this.removeDisposed(id))
    this.entries.set(id, entry)

    while (this.entries.size > this.capacity) {
      const oldestId = this.entries.keys().next().value as number | undefined
      if (oldestId === undefined) break
      this.remove(oldestId, true)
    }
    this.onChange()
    return id
  }

  getMarker(id: number): IMarker | null {
    const entry = this.entries.get(id)
    if (!entry || entry.marker.isDisposed || entry.marker.line < 0) return null
    return entry.marker
  }

  snapshot(bufferLength: number): TerminalBookmark[] {
    const bookmarks: TerminalBookmark[] = []
    for (const entry of this.entries.values()) {
      if (entry.marker.isDisposed || entry.marker.line < 0) continue
      bookmarks.push({
        createdAt: entry.createdAt,
        id: entry.id,
        line: entry.marker.line,
        positionPercent: positionPercent(entry.marker.line, bufferLength),
        preview: entry.preview,
      })
    }
    return bookmarks
  }

  dispose(): void {
    for (const id of [...this.entries.keys()]) this.remove(id, true)
  }

  private removeDisposed(id: number): void {
    if (!this.entries.has(id)) return
    this.remove(id, false)
    this.onChange()
  }

  private remove(id: number, disposeMarker: boolean): void {
    const entry = this.entries.get(id)
    if (!entry) return
    this.entries.delete(id)
    entry.disposeSubscription.dispose()
    if (disposeMarker && !entry.marker.isDisposed) entry.marker.dispose()
  }
}

export const terminalBookmarkRenderStateEqual = (
  left: TerminalBookmark[],
  right: TerminalBookmark[]
): boolean =>
  left.length === right.length &&
  left.every((bookmark, index) => {
    const candidate = right[index]
    return (
      candidate !== undefined &&
      bookmark.id === candidate.id &&
      bookmark.createdAt === candidate.createdAt &&
      bookmark.line === candidate.line &&
      bookmark.positionPercent === candidate.positionPercent &&
      bookmark.preview === candidate.preview
    )
  })

/** Keeps document-relative positions while nudging dense adjacent ticks apart. */
export const layoutTerminalBookmarkPositions = (bookmarks: TerminalBookmark[]): number[] => {
  if (bookmarks.length === 0) return []
  if (bookmarks.length === 1) return [bookmarks[0]!.positionPercent]

  const minimumGap = Math.min(5, 96 / (bookmarks.length - 1))
  const positions = bookmarks.map((bookmark) => bookmark.positionPercent)
  for (let index = 1; index < positions.length; index += 1) {
    positions[index] = Math.max(positions[index]!, positions[index - 1]! + minimumGap)
  }
  if (positions[positions.length - 1]! <= 98) return positions

  positions[positions.length - 1] = 98
  for (let index = positions.length - 2; index >= 0; index -= 1) {
    positions[index] = Math.min(positions[index]!, positions[index + 1]! - minimumGap)
  }
  return positions
}
