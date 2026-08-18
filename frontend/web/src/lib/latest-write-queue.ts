export interface LatestWriteQueue<T> {
  enqueue: (value: T) => void
  whenIdle: () => Promise<void>
}

/**
 * Persists ordered UI preferences without allowing rapid interactions to grow
 * an unbounded promise chain. One write may run while exactly one latest value
 * waits; intermediate values are deliberately superseded.
 */
export const createLatestWriteQueue = <T>(
  write: (value: T) => Promise<void>,
  onError?: (error: unknown) => void
): LatestWriteQueue<T> => {
  let pending: { value: T } | undefined
  let drainPromise: Promise<void> | null = null

  const ensureDrain = (): void => {
    if (drainPromise) return
    drainPromise = (async () => {
      while (pending) {
        const next = pending
        pending = undefined
        try {
          await write(next.value)
        } catch (error) {
          onError?.(error)
        }
      }
    })().finally(() => {
      drainPromise = null
      if (pending) ensureDrain()
    })
  }

  return {
    enqueue(value) {
      pending = { value }
      ensureDrain()
    },
    async whenIdle() {
      while (drainPromise) await drainPromise
    },
  }
}
