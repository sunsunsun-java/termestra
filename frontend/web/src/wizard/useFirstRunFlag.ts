import { useCallback, useState } from 'react'

const KEY = 'termestra.first-run-seen'

export const useFirstRunFlag = () => {
  const [seen, setSeen] = useState(() => {
    try {
      return window.localStorage.getItem(KEY) === '1'
    } catch {
      // Storage denial must not make a genuinely new user silently miss the
      // first-run guidance. The hook still remembers dismissal in state for
      // the lifetime of this page.
      return false
    }
  })

  const markSeen = useCallback(() => {
    try {
      window.localStorage.setItem(KEY, '1')
    } catch {
      // Best-effort preference persistence; in-memory state still dismisses it.
    }
    setSeen(true)
  }, [])

  return { seen, markSeen }
}
