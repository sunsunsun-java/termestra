import type { TerminalRunSummary } from '../api.js'

const GLOBAL_TERMINAL_PROBE_CONCURRENCY = 4

export interface GlobalTerminalRunSnapshot {
  failure?: unknown
  failureWorkspaceId?: string
  ready: boolean
  runs: TerminalRunSummary[]
}

type TerminalRunLoader = (
  workspaceId: string,
  signal: AbortSignal
) => Promise<TerminalRunSummary[]>

const unavailableSnapshot = (
  failureWorkspaceId?: string,
  failure?: unknown
): GlobalTerminalRunSnapshot => ({
  ...(failure !== undefined ? { failure } : {}),
  ...(failureWorkspaceId ? { failureWorkspaceId } : {}),
  ready: false,
  runs: [],
})

/**
 * Loads the bounded run-summary endpoint for every workspace without opening
 * an unbounded fan-out. Finding one active run is conclusive (reload is
 * unsafe), so workers stop claiming new work as soon as that happens. A
 * request failure is never allowed to reuse a previous safe result.
 */
export const probeGlobalTerminalRuns = async (
  workspaceIds: readonly string[],
  loadRuns: TerminalRunLoader,
  signal: AbortSignal,
  requestedConcurrency = GLOBAL_TERMINAL_PROBE_CONCURRENCY
): Promise<GlobalTerminalRunSnapshot> => {
  const ids = Array.from(new Set(workspaceIds))
  if (signal.aborted) return unavailableSnapshot()
  if (ids.length === 0) return { ready: true, runs: [] }

  const concurrency = Math.min(
    GLOBAL_TERMINAL_PROBE_CONCURRENCY,
    Math.max(1, Math.floor(requestedConcurrency))
  )
  const runsByIndex = new Map<number, TerminalRunSummary[]>()
  let nextIndex = 0
  let activeRunFound = false
  let failure: unknown
  let failureWorkspaceId: string | undefined

  const worker = async () => {
    while (!signal.aborted && !activeRunFound && failureWorkspaceId === undefined) {
      const index = nextIndex
      nextIndex += 1
      if (index >= ids.length) return
      const workspaceId = ids[index]
      if (workspaceId === undefined) return

      try {
        const runs = await loadRuns(workspaceId, signal)
        if (signal.aborted) return
        runsByIndex.set(index, runs)
        if (runs.some((run) => run.status !== 'stopped')) activeRunFound = true
      } catch (error: unknown) {
        if (!signal.aborted) {
          failure = error
          failureWorkspaceId = workspaceId
        }
        return
      }
    }
  }

  await Promise.all(
    Array.from({ length: Math.min(concurrency, ids.length) }, () => worker())
  )

  if (signal.aborted) return unavailableSnapshot()
  if (failureWorkspaceId !== undefined && !activeRunFound) {
    return unavailableSnapshot(failureWorkspaceId, failure)
  }

  const runs = [...runsByIndex.entries()]
    .sort(([left], [right]) => left - right)
    .flatMap(([, workspaceRuns]) => workspaceRuns)
  return { ready: activeRunFound || runsByIndex.size === ids.length, runs }
}
