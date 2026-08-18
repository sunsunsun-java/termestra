import { useEffect } from 'react'

let activeGuards = 0
let listenerAttached = false
let silentAttemptPending = false

const handleBeforeUnload = (event: BeforeUnloadEvent): void => {
  if (silentAttemptPending) {
    silentAttemptPending = false
    return
  }
  if (activeGuards === 0) return
  event.preventDefault()
  event.returnValue = ''
}

const attachGuard = (): void => {
  activeGuards += 1
  if (listenerAttached) return
  window.addEventListener('beforeunload', handleBeforeUnload)
  listenerAttached = true
}

const detachGuard = (): void => {
  activeGuards = Math.max(0, activeGuards - 1)
  if (activeGuards > 0 || !listenerAttached) return
  window.removeEventListener('beforeunload', handleBeforeUnload)
  listenerAttached = false
}

export const allowNextUnloadSilently = (): void => {
  silentAttemptPending = true
}

export const silentReload = (): void => {
  allowNextUnloadSilently()
  window.location.reload()
}

export const useBeforeUnloadGuard = (enabled: boolean): void => {
  useEffect(() => {
    if (!enabled) return
    attachGuard()
    return detachGuard
  }, [enabled])
}

export const __resetBeforeUnloadGuardForTests = (): void => {
  if (listenerAttached) window.removeEventListener('beforeunload', handleBeforeUnload)
  activeGuards = 0
  listenerAttached = false
  silentAttemptPending = false
}
