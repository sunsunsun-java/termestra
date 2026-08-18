import { useCallback, useMemo, useReducer } from 'react'

interface DemoModeControls {
  demoMode: boolean
  enableDemo: () => void
  exitDemo: () => void
}

const setMode = (_current: boolean, enabled: boolean): boolean => enabled

export const useDemoMode = (): DemoModeControls => {
  const [demoMode, dispatchMode] = useReducer(setMode, false)
  const enableDemo = useCallback(() => dispatchMode(true), [])
  const exitDemo = useCallback(() => dispatchMode(false), [])

  return useMemo(
    () => ({ demoMode, enableDemo, exitDemo }),
    [demoMode, enableDemo, exitDemo]
  )
}
