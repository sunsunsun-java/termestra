import {
  type PointerEvent as ReactPointerEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react'

import { startDocumentDrag } from '../lib/document-drag.js'

const STORAGE_KEY = 'termestra.worker-modal.width'
export const WORKER_MODAL_MIN = 480
const WORKER_MODAL_DEFAULT_RATIO = 0.5

const clampToViewport = (value: number): number => {
  const viewportMax = typeof window !== 'undefined' ? window.innerWidth - 24 : 1200
  return Math.min(Math.max(value, WORKER_MODAL_MIN), Math.max(WORKER_MODAL_MIN, viewportMax))
}

// First-open default: half the viewport. Once the user resizes, the stored
// value wins on subsequent opens so we don't second-guess their preference.
const computeViewportDefault = (): number => {
  if (typeof window === 'undefined') return WORKER_MODAL_MIN
  return clampToViewport(Math.round(window.innerWidth * WORKER_MODAL_DEFAULT_RATIO))
}

const readStored = (): number => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return computeViewportDefault()
    const parsed = Number.parseInt(raw, 10)
    return Number.isFinite(parsed) ? clampToViewport(parsed) : computeViewportDefault()
  } catch {
    return computeViewportDefault()
  }
}

/**
 * Drives the WorkerModal's resize handles. The modal is centered (grid
 * place-items-center), so widening = handle moves outward, which means
 * dragging from either edge changes width by 2× the cursor delta. Width is
 * persisted to localStorage and clamped to viewport on every render so that
 * resizing the browser doesn't leave the modal stranded wider than the
 * viewport.
 */
export const useWorkerModalResize = () => {
  const [width, setWidth] = useState<number>(() => readStored())
  const [resizing, setResizing] = useState(false)
  const dragCleanupRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, String(Math.round(width)))
    } catch {
      // localStorage unavailable — keep in-memory value.
    }
  }, [width])

  // Re-clamp when viewport shrinks so the modal can't exceed available space.
  useEffect(() => {
    const handleResize = () => {
      setWidth((current) => clampToViewport(current))
    }
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

  const beginResize = useCallback(
    (side: 'left' | 'right') => (startEvent: ReactPointerEvent<HTMLDivElement>) => {
      startEvent.preventDefault()
      const startX = startEvent.clientX
      const startWidth = width
      dragCleanupRef.current?.()
      setResizing(true)

      const handleMove = (event: Event) => {
        const ev = event as PointerEvent
        const delta = ev.clientX - startX
        // Modal is centered, so growing one edge grows BOTH sides by `delta`.
        // Left handle: dragging left (delta < 0) → wider; right handle: dragging right (delta > 0) → wider.
        const next = side === 'left' ? startWidth - 2 * delta : startWidth + 2 * delta
        setWidth(clampToViewport(next))
      }
      let cleanup: () => void
      cleanup = startDocumentDrag({
        cursor: 'col-resize',
        document,
        endEvents: ['pointerup', 'pointercancel'],
        moveEvent: 'pointermove',
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

  return { width, resizing, beginResize }
}
