/**
 * Small, deterministic LRU cache. Reads promote an entry and writes evict the
 * least-recently-used key, so memory use is bounded independently of how many
 * resources a user opens during a long-lived UI session.
 */
export class BoundedLruMap<K, V> {
  readonly #entries = new Map<K, V>()
  readonly maxSize: number

  constructor(maxSize: number) {
    if (!Number.isSafeInteger(maxSize) || maxSize < 1) {
      throw new RangeError('BoundedLruMap maxSize must be a positive integer')
    }
    this.maxSize = maxSize
  }

  get size(): number {
    return this.#entries.size
  }

  delete(key: K): boolean {
    return this.#entries.delete(key)
  }

  get(key: K): V | undefined {
    const value = this.#entries.get(key)
    if (value === undefined) return undefined
    this.#entries.delete(key)
    this.#entries.set(key, value)
    return value
  }

  has(key: K): boolean {
    return this.#entries.has(key)
  }

  set(key: K, value: V): this {
    this.#entries.delete(key)
    this.#entries.set(key, value)
    while (this.#entries.size > this.maxSize) {
      const oldestKey = this.#entries.keys().next().value
      if (oldestKey === undefined) break
      this.#entries.delete(oldestKey)
    }
    return this
  }
}
