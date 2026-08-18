export interface SingleFlight<TArgs extends unknown[], TResult> {
  isRunning: () => boolean
  run: (...args: TArgs) => Promise<TResult>
}

/**
 * Shares one in-flight operation with every concurrent caller. The gate is
 * released only after the operation settles, so a failed request can be
 * retried without allowing double submission while the outcome is unknown.
 */
export const createSingleFlight = <TArgs extends unknown[], TResult>(
  operation: (...args: TArgs) => Promise<TResult>
): SingleFlight<TArgs, TResult> => {
  let inFlight: Promise<TResult> | null = null

  return {
    isRunning: () => inFlight !== null,
    run: (...args) => {
      if (inFlight) return inFlight

      let current: Promise<TResult>
      current = Promise.resolve()
        .then(() => operation(...args))
        .finally(() => {
          if (inFlight === current) inFlight = null
        })
      inFlight = current
      return current
    },
  }
}
