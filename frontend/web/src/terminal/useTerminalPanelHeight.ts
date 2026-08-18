import {
  type PointerEvent as ReactPointerEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react'

import { startDocumentDrag } from '../lib/document-drag.js'

const HEIGHT_KEY = 'termestra.terminal-panel.height'
const COLLAPSED_KEY = 'termestra.terminal-panel.collapsed'

export const TERMINAL_PANEL_MIN_HEIGHT = 160
const DEFAULT_RATIO = 0.35
const BOTTOM_SAFE_AREA = 160

const clampHeight = (value: number): number => {
  const viewport = typeof window !== 'undefined' ? window.innerHeight : 800
  const maxHeight = Math.max(TERMINAL_PANEL_MIN_HEIGHT, viewport - BOTTOM_SAFE_AREA)
  return Math.min(Math.max(value, TERMINAL_PANEL_MIN_HEIGHT), maxHeight)
}

const computeDefaultHeight = (): number => {
  const viewport = typeof window !== 'undefined' ? window.innerHeight : 800
  return clampHeight(Math.floor(viewport * DEFAULT_RATIO))
}

const readStoredHeight = (): number => {
  try {
    const raw = window.localStorage.getItem(HEIGHT_KEY)
    if (!raw) return computeDefaultHeight()
    const parsed = Number.parseInt(raw, 10)
    return Number.isFinite(parsed) ? clampHeight(parsed) : computeDefaultHeight()
  } catch {
    return computeDefaultHeight()
  }
}

const readStoredCollapsed = (): boolean => {
  try {
    return window.localStorage.getItem(COLLAPSED_KEY) === '1'
  } catch {
    return false
  }
}

/**
 * Drives the horizontal splitter on top of the bottom terminal panel inside
 * the right column. Height is persisted globally so layout sticks across
 * reloads regardless of workspace. Collapsed is a global preference for the
 * panel itself, not per-workspace.
 */
export const useTerminalPanelHeight = () => {
  const [height, setHeightRaw] = useState<number>(() => readStoredHeight())
  const [collapsed, setCollapsedRaw] = useState<boolean>(() => readStoredCollapsed())
  const [dragging, setDragging] = useState(false)
  const dragCleanupRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    try {
      window.localStorage.setItem(HEIGHT_KEY, String(Math.round(height)))
    } catch {
      // quota / private browsing — silently keep in-memory value
    }
  }, [height])

  useEffect(() => {
    try {
      window.localStorage.setItem(COLLAPSED_KEY, collapsed ? '1' : '0')
    } catch {
      // ignored
    }
  }, [collapsed])

  useEffect(() => {
    const handleResize = () => setHeightRaw((h) => clampHeight(h))
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [])

  useEffect(
    () => () => {
      dragCleanupRef.current?.()
      dragCleanupRef.current = null
    },
    []
  )

  const setHeight = useCallback((next: number) => setHeightRaw(clampHeight(next)), [])
  const setCollapsed = useCallback((next: boolean) => setCollapsedRaw(next), [])

  const beginDrag = useCallback(
    (startEvent: ReactPointerEvent<HTMLDivElement>) => {
      startEvent.preventDefault()
      const startY = startEvent.clientY
      let startHeight = height
      setHeightRaw((current) => {
        startHeight = current
        return current
      })
      dragCleanupRef.current?.()
      setDragging(true)

      const handleMove = (event: Event) => {
        const ev = event as PointerEvent
        // Dragging UP grows the panel; deltaY is negative when moving up.
        const delta = ev.clientY - startY
        setHeightRaw(clampHeight(startHeight - delta))
      }
      let cleanup: () => void
      cleanup = startDocumentDrag({
        cursor: 'ns-resize',
        document,
        endEvents: ['pointerup', 'pointercancel'],
        moveEvent: 'pointermove',
        onFinish: () => {
          if (dragCleanupRef.current === cleanup) dragCleanupRef.current = null
          setDragging(false)
        },
        onMove: handleMove,
      })
      dragCleanupRef.current = cleanup
    },
    [height]
  )

  return { height, collapsed, dragging, setHeight, setCollapsed, beginDrag }
}
