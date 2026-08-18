import { useCallback, useEffect, useRef } from 'react'

export const useWorkerHighlight = () => {
  const highlightTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const highlightedTargetRef = useRef<HTMLElement | null>(null)

  const clearHighlight = useCallback(() => {
    if (highlightTimeoutRef.current) clearTimeout(highlightTimeoutRef.current)
    highlightTimeoutRef.current = null
    highlightedTargetRef.current?.classList.remove('worker-card-shell--highlight')
    highlightedTargetRef.current = null
  }, [])

  useEffect(() => clearHighlight, [clearHighlight])

  return useCallback((workerName: string) => {
    if (typeof document === 'undefined') return
    const escaped =
      typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
        ? CSS.escape(workerName)
        : workerName.replace(/"/g, '\\"')
    const target = document.querySelector<HTMLElement>(`[data-worker-name="${escaped}"]`)
    if (!target) return
    clearHighlight()
    target.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' })
    target.classList.add('worker-card-shell--highlight')
    highlightedTargetRef.current = target
    highlightTimeoutRef.current = setTimeout(() => {
      if (highlightedTargetRef.current === target) clearHighlight()
    }, 1000)
  }, [clearHighlight])
}
