type DragEventName = 'mousemove' | 'pointermove'
type DragEndEventName = 'mouseup' | 'pointerup' | 'pointercancel'

interface DocumentDragOptions {
  cursor: string
  document: Document
  endEvents: readonly DragEndEventName[]
  moveEvent: DragEventName
  onFinish: () => void
  onMove: (event: Event) => void
}

const activeFinishers = new WeakMap<Document, () => void>()

/**
 * Owns the complete lifecycle of a document-level drag. The returned disposer
 * is intentionally idempotent so React effects can call it during unmount even
 * when the matching pointer/mouse-up event already completed the gesture.
 */
export const startDocumentDrag = ({
  cursor,
  document,
  endEvents,
  moveEvent,
  onFinish,
  onMove,
}: DocumentDragOptions): (() => void) => {
  activeFinishers.get(document)?.()
  const previousCursor = document.body.style.cursor
  const previousUserSelect = document.body.style.userSelect
  let active = true

  let finish: () => void
  const dispose = () => {
    if (!active) return
    active = false
    if (activeFinishers.get(document) === finish) activeFinishers.delete(document)
    document.body.style.cursor = previousCursor
    document.body.style.userSelect = previousUserSelect
    document.removeEventListener(moveEvent, onMove)
    for (const eventName of endEvents) document.removeEventListener(eventName, finish)
    document.defaultView?.removeEventListener('blur', finish)
  }
  finish = () => {
    if (!active) return
    dispose()
    onFinish()
  }

  document.body.style.cursor = cursor
  document.body.style.userSelect = 'none'
  document.addEventListener(moveEvent, onMove)
  for (const eventName of endEvents) document.addEventListener(eventName, finish)
  document.defaultView?.addEventListener('blur', finish)
  activeFinishers.set(document, finish)

  return dispose
}
