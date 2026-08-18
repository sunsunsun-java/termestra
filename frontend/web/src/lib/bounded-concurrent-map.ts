export const MAX_BOUNDED_MAP_CONCURRENCY = 4

export type BoundedMapSettledResult<Item, Value> =
  | {
      index: number
      item: Item
      status: 'fulfilled'
      value: Value
    }
  | {
      index: number
      item: Item
      reason: unknown
      status: 'rejected'
    }

/**
 * Applies an asynchronous mapper with a hard per-invocation concurrency ceiling.
 *
 * Individual failures are returned as data so one unavailable resource cannot
 * short-circuit the rest of the queue. Results retain input order even when
 * requests finish out of order. Aborting prevents queued items from being
 * claimed; an in-flight mapper must observe the same signal to stop its work.
 */
export const mapSettledWithConcurrencyLimit = async <Item, Value>(
  items: readonly Item[],
  mapper: (item: Item, index: number) => Promise<Value>,
  signal?: AbortSignal
): Promise<BoundedMapSettledResult<Item, Value>[]> => {
  if (items.length === 0 || signal?.aborted) return []

  const input = [...items]
  const results: Array<BoundedMapSettledResult<Item, Value> | undefined> = new Array(
    input.length
  )
  let nextIndex = 0

  const worker = async (): Promise<void> => {
    while (!signal?.aborted) {
      const index = nextIndex
      if (index >= input.length) return
      nextIndex += 1
      const item = input[index] as Item

      try {
        const value = await mapper(item, index)
        results[index] = { index, item, status: 'fulfilled', value }
      } catch (reason: unknown) {
        results[index] = { index, item, reason, status: 'rejected' }
      }
    }
  }

  await Promise.all(
    Array.from(
      { length: Math.min(MAX_BOUNDED_MAP_CONCURRENCY, input.length) },
      () => worker()
    )
  )

  return results.filter(
    (result): result is BoundedMapSettledResult<Item, Value> => result !== undefined
  )
}
