import { useEffect, useRef } from 'react'

export type ShortcutAction = 'add-workspace' | 'try-demo'

export interface UseShortcutActionOptions {
  onAddWorkspace: () => void
  onTryDemo: () => void
  /** Wait until bootstrap has either succeeded or reached an explicit error. */
  ready: boolean
}

const parseAction = (value: string | null): ShortcutAction | null => {
  switch (value) {
    case 'add-workspace':
    case 'try-demo':
      return value
    default:
      return null
  }
}

const consumeActionFromLocation = (): ShortcutAction | null => {
  const url = new URL(window.location.href)
  const action = parseAction(url.searchParams.get('action'))
  if (action === null) return null

  url.searchParams.delete('action')
  const replacement = `${url.pathname}${url.search}${url.hash}`
  window.history.replaceState(window.history.state, '', replacement)
  return action
}

/**
 * Dispatches a PWA manifest shortcut once the application can receive it.
 * Unknown values are left untouched; a consumed action is removed from the
 * URL so reload and React StrictMode cannot replay the user intent.
 */
export const useShortcutAction = ({
  onAddWorkspace,
  onTryDemo,
  ready,
}: UseShortcutActionOptions): void => {
  const dispatched = useRef(false)

  useEffect(() => {
    if (!ready || dispatched.current || typeof window === 'undefined') return

    const action = consumeActionFromLocation()
    if (action === null) return

    dispatched.current = true
    const dispatch: Record<ShortcutAction, () => void> = {
      'add-workspace': onAddWorkspace,
      'try-demo': onTryDemo,
    }
    dispatch[action]()
  }, [onAddWorkspace, onTryDemo, ready])
}
