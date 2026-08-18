interface VisibleSingleFlightProbeOptions {
  intervalMs: number
  onOnline: () => void
  probe: (signal: AbortSignal) => Promise<boolean>
  timeoutMs: number
}

export interface VisibleSingleFlightProbe {
  checkNow: () => Promise<boolean>
  dispose: () => void
}

/**
 * Runs at most one visibility-aware health probe at a time.
 *
 * Unlike async setInterval, the next timer is armed only after the current
 * request settles. Hiding the page aborts both the request and its timer;
 * returning to the page requests a fresh check immediately.
 */
export const createVisibleSingleFlightProbe = ({
  intervalMs,
  onOnline,
  probe,
  timeoutMs,
}: VisibleSingleFlightProbeOptions): VisibleSingleFlightProbe => {
  let disposed = false
  let online = false
  let pendingImmediateCheck = false
  let scheduledCheck: number | undefined
  let probeTimeout: number | undefined
  let inFlight: Promise<boolean> | null = null
  let inFlightController: AbortController | null = null

  const clearScheduledCheck = () => {
    if (scheduledCheck === undefined) return
    window.clearTimeout(scheduledCheck)
    scheduledCheck = undefined
  }

  const clearProbeTimeout = () => {
    if (probeTimeout === undefined) return
    window.clearTimeout(probeTimeout)
    probeTimeout = undefined
  }

  const abortInFlight = () => {
    clearProbeTimeout()
    inFlightController?.abort()
  }

  const scheduleNext = () => {
    clearScheduledCheck()
    if (disposed || online || document.visibilityState !== 'visible') return
    scheduledCheck = window.setTimeout(() => {
      scheduledCheck = undefined
      void checkNow()
    }, intervalMs)
  }

  const checkNow = (): Promise<boolean> => {
    if (disposed || online || document.visibilityState !== 'visible') {
      return Promise.resolve(false)
    }
    if (inFlight) return inFlight

    clearScheduledCheck()
    const controller = new AbortController()
    inFlightController = controller
    probeTimeout = window.setTimeout(() => controller.abort(), timeoutMs)

    let probeResult: Promise<boolean>
    try {
      probeResult = Promise.resolve(probe(controller.signal))
    } catch {
      probeResult = Promise.resolve(false)
    }

    let current: Promise<boolean>
    current = probeResult
      .catch(() => false)
      .then((available) => {
        if (available && !disposed && document.visibilityState === 'visible') {
          online = true
          clearScheduledCheck()
          onOnline()
        }
        return available
      })
      .finally(() => {
        clearProbeTimeout()
        if (inFlightController === controller) inFlightController = null
        if (inFlight === current) inFlight = null
        if (disposed || online) return
        if (pendingImmediateCheck && document.visibilityState === 'visible') {
          pendingImmediateCheck = false
          void checkNow()
          return
        }
        scheduleNext()
      })
    inFlight = current
    return current
  }

  const onVisibilityChange = () => {
    clearScheduledCheck()
    if (document.visibilityState !== 'visible') {
      pendingImmediateCheck = false
      abortInFlight()
      return
    }
    if (inFlight) {
      pendingImmediateCheck = true
      return
    }
    void checkNow()
  }

  document.addEventListener('visibilitychange', onVisibilityChange)
  void checkNow()

  return {
    checkNow,
    dispose() {
      disposed = true
      pendingImmediateCheck = false
      clearScheduledCheck()
      abortInFlight()
      document.removeEventListener('visibilitychange', onVisibilityChange)
    },
  }
}
