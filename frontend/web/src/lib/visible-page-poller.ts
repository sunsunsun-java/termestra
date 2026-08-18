interface VisiblePagePollerOptions {
  getDelay: () => number
  load: (reason: VisiblePagePollReason) => void
}

export type VisiblePagePollReason = 'initial' | 'scheduled' | 'visible'

export interface VisiblePagePoller {
  dispose: () => void
  schedule: () => void
}

export const createVisiblePagePoller = ({
  getDelay,
  load,
}: VisiblePagePollerOptions): VisiblePagePoller => {
  let disposed = false
  let timeout: number | undefined

  const clearScheduledLoad = () => {
    if (timeout === undefined) return
    window.clearTimeout(timeout)
    timeout = undefined
  }

  const loadWhenVisible = (reason: VisiblePagePollReason) => {
    if (disposed || document.visibilityState !== 'visible') return
    load(reason)
  }

  const schedule = () => {
    clearScheduledLoad()
    if (disposed || document.visibilityState !== 'visible') return
    timeout = window.setTimeout(() => {
      timeout = undefined
      loadWhenVisible('scheduled')
    }, getDelay())
  }

  const onVisibilityChange = () => {
    clearScheduledLoad()
    loadWhenVisible('visible')
  }

  document.addEventListener('visibilitychange', onVisibilityChange)
  loadWhenVisible('initial')

  return {
    dispose() {
      disposed = true
      clearScheduledLoad()
      document.removeEventListener('visibilitychange', onVisibilityChange)
    },
    schedule,
  }
}
