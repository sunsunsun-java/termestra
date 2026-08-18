import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'

import { startDocumentDrag } from './lib/document-drag.js'
import { boundsForPaneWidth } from './lib/pane-split-policy.js'

const STORAGE_KEY = 'termestra.split.orch-pct'
const MIN_PCT = 0.3
const MAX_PCT = 0.78
const DEFAULT_PCT = 0.6
export const DEFAULT_WORKERS_PANE_WIDTH = `${Math.round((1 - DEFAULT_PCT) * 100)}%`
const KEY_STEP = 0.02

const clamp = (n: number, min: number, max: number) => Math.min(max, Math.max(min, n))

const readStored = (): number => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return DEFAULT_PCT
    const n = Number.parseFloat(raw)
    return Number.isFinite(n) ? clamp(n, MIN_PCT, MAX_PCT) : DEFAULT_PCT
  } catch {
    return DEFAULT_PCT
  }
}

/**
 * Drives a draggable splitter between OrchestratorPane and WorkersPane.
 * State is the orchestrator pane's share (0–1) of the container width;
 * persisted to localStorage so layout sticks across reloads.
 */
export const usePaneSplit = () => {
  const containerRef = useRef<HTMLDivElement>(null)
  const [orchPct, setOrchPct] = useState<number>(() => readStored())
  const [dragging, setDragging] = useState(false)
  const [limits, setLimits] = useState(() => ({ min: MIN_PCT, max: MAX_PCT }))
  const dragCleanupRef = useRef<(() => void) | null>(null)

  useLayoutEffect(() => {
    const container = containerRef.current
    if (!container) return
    const applyBounds = () => {
      const next = boundsForPaneWidth(container.getBoundingClientRect().width)
      setLimits((current) =>
        current.min === next.min && current.max === next.max ? current : next
      )
      setOrchPct((current) => clamp(current, next.min, next.max))
    }
    applyBounds()
    const observer = new ResizeObserver(applyBounds)
    observer.observe(container)
    return () => observer.disconnect()
  }, [])

  useEffect(
    () => () => {
      dragCleanupRef.current?.()
      dragCleanupRef.current = null
    },
    []
  )

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, orchPct.toFixed(4))
    } catch {
      // localStorage unavailable (private mode, quota) — degrade silently.
    }
  }, [orchPct])

  const beginDrag = useCallback((startEvent: React.PointerEvent<HTMLDivElement>) => {
    startEvent.preventDefault()
    const container = containerRef.current
    if (!container) return
    dragCleanupRef.current?.()
    setDragging(true)

    const handleMove = (event: Event) => {
      const ev = event as PointerEvent
      const rect = container.getBoundingClientRect()
      if (rect.width <= 0) return
      const pct = (ev.clientX - rect.left) / rect.width
      const currentBounds = boundsForPaneWidth(rect.width)
      setLimits(currentBounds)
      setOrchPct(clamp(pct, currentBounds.min, currentBounds.max))
    }
    let cleanup: () => void
    cleanup = startDocumentDrag({
      cursor: 'col-resize',
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
  }, [])

  const onKeyDown = useCallback((e: React.KeyboardEvent<HTMLDivElement>) => {
    const width = containerRef.current?.getBoundingClientRect().width ?? 0
    const bounds = boundsForPaneWidth(width)
    if (e.key === 'ArrowLeft') {
      e.preventDefault()
      setOrchPct((p) => clamp(p - KEY_STEP, bounds.min, bounds.max))
    } else if (e.key === 'ArrowRight') {
      e.preventDefault()
      setOrchPct((p) => clamp(p + KEY_STEP, bounds.min, bounds.max))
    } else if (e.key === 'Home') {
      e.preventDefault()
      setOrchPct(bounds.min)
    } else if (e.key === 'End') {
      e.preventDefault()
      setOrchPct(bounds.max)
    } else if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      // ⌘+Enter to reset to default (small power-user touch).
      e.preventDefault()
      setOrchPct(clamp(DEFAULT_PCT, bounds.min, bounds.max))
    }
  }, [])

  return {
    containerRef,
    orchPct,
    dragging,
    minPct: limits.min,
    maxPct: limits.max,
    beginDrag,
    onKeyDown,
  }
}
