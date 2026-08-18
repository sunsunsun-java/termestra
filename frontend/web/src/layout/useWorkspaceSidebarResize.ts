import { type KeyboardEvent, type MouseEvent, useCallback, useEffect, useRef, useState } from 'react'

import { startDocumentDrag } from '../lib/document-drag.js'

const STORAGE_KEY = 'termestra.workspace-sidebar.width'
const EXPANDED_STORAGE_KEY = 'termestra.workspace-sidebar.expanded-width'
export const WORKSPACE_SIDEBAR_MIN = 56
export const WORKSPACE_SIDEBAR_MAX = 280
const WORKSPACE_SIDEBAR_DEFAULT = 256
const KEYBOARD_STEP = 16
const COLLAPSED_BREAKPOINT = 96

const clamp = (value: number): number =>
  Math.min(WORKSPACE_SIDEBAR_MAX, Math.max(WORKSPACE_SIDEBAR_MIN, value))

const readStoredWidth = (): number => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return WORKSPACE_SIDEBAR_DEFAULT
    const parsed = Number.parseInt(raw, 10)
    return Number.isFinite(parsed) ? clamp(parsed) : WORKSPACE_SIDEBAR_DEFAULT
  } catch {
    return WORKSPACE_SIDEBAR_DEFAULT
  }
}

const readStoredExpandedWidth = (currentWidth: number): number => {
  const fallback =
    currentWidth <= COLLAPSED_BREAKPOINT ? WORKSPACE_SIDEBAR_DEFAULT : currentWidth
  try {
    const raw = localStorage.getItem(EXPANDED_STORAGE_KEY)
    if (!raw) return fallback
    const parsed = Number.parseInt(raw, 10)
    return Number.isFinite(parsed) && parsed > COLLAPSED_BREAKPOINT ? clamp(parsed) : fallback
  } catch {
    return fallback
  }
}

export const useWorkspaceSidebarResize = () => {
  const initialWidth = useRef(readStoredWidth()).current
  const [width, setWidth] = useState(initialWidth)
  const [resizing, setResizing] = useState(false)
  const expandedWidth = useRef(readStoredExpandedWidth(initialWidth))
  const dragCleanupRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, String(Math.round(width)))
      if (width > COLLAPSED_BREAKPOINT) {
        expandedWidth.current = width
        localStorage.setItem(EXPANDED_STORAGE_KEY, String(Math.round(width)))
      }
    } catch {
      // localStorage can be unavailable in private mode; width still works in-memory.
    }
  }, [width])

  useEffect(
    () => () => {
      dragCleanupRef.current?.()
      dragCleanupRef.current = null
    },
    []
  )

  const beginResize = useCallback(
    (event: MouseEvent<HTMLHRElement>) => {
      event.preventDefault()
      const startX = event.clientX
      const startWidth = width
      dragCleanupRef.current?.()
      setResizing(true)

      const handleMove = (event: Event) => {
        const moveEvent = event as globalThis.MouseEvent
        setWidth(clamp(startWidth + moveEvent.clientX - startX))
      }
      let cleanup: () => void
      cleanup = startDocumentDrag({
        cursor: 'col-resize',
        document,
        endEvents: ['mouseup'],
        moveEvent: 'mousemove',
        onFinish: () => {
          if (dragCleanupRef.current === cleanup) dragCleanupRef.current = null
          setResizing(false)
        },
        onMove: handleMove,
      })
      dragCleanupRef.current = cleanup
    },
    [width]
  )

  const onResizeKeyDown = useCallback((event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'ArrowLeft') {
      event.preventDefault()
      setWidth((current) => clamp(current - KEYBOARD_STEP))
    } else if (event.key === 'ArrowRight') {
      event.preventDefault()
      setWidth((current) => clamp(current + KEYBOARD_STEP))
    } else if (event.key === 'Home') {
      event.preventDefault()
      setWidth(WORKSPACE_SIDEBAR_MIN)
    } else if (event.key === 'End') {
      event.preventDefault()
      setWidth(WORKSPACE_SIDEBAR_MAX)
    }
  }, [])

  const collapsed = width <= COLLAPSED_BREAKPOINT
  const toggleCollapsed = useCallback(() => {
    setWidth((current) => {
      if (current <= COLLAPSED_BREAKPOINT) return expandedWidth.current
      expandedWidth.current = current
      return WORKSPACE_SIDEBAR_MIN
    })
  }, [])

  return { beginResize, collapsed, onResizeKeyDown, resizing, toggleCollapsed, width }
}
