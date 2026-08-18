import { useEffect, useState } from 'react'

import { listTerminalRuns, type TerminalRunSummary } from '../api.js'
import { createVisiblePagePoller } from '../lib/visible-page-poller.js'
import {
  initialTerminalRunsPollState,
  terminalRunsAfterFailure,
  terminalRunsAfterSuccess,
  type TerminalRunsPollState,
} from './terminal-runs-state.js'

const REFRESH_INTERVAL_MS = 500
const MAX_REFRESH_INTERVAL_MS = 5000

const getRefreshDelay = (failureCount: number) =>
  Math.min(REFRESH_INTERVAL_MS * 2 ** failureCount, MAX_REFRESH_INTERVAL_MS)

export const orchestratorAgentId = (workspaceId: string) => `${workspaceId}:orchestrator`

const areTerminalRunsEqual = (a: TerminalRunSummary[], b: TerminalRunSummary[]): boolean => {
  if (a.length !== b.length) return false
  return a.every((run, index) => {
    const other = b[index]
    return (
      other !== undefined &&
      run.agent_id === other.agent_id &&
      run.agent_name === other.agent_name &&
      run.run_id === other.run_id &&
      run.status === other.status &&
      run.terminal_input_profile === other.terminal_input_profile
    )
  })
}

export const useTerminalRuns = (workspaceId: string | null): TerminalRunsPollState => {
  const [stored, setStored] = useState<{
    state: TerminalRunsPollState
    workspaceId: string | null
  }>({ state: initialTerminalRunsPollState(), workspaceId: null })

  useEffect(() => {
    if (!workspaceId) {
      setStored({ state: initialTerminalRunsPollState(), workspaceId: null })
      return
    }
    setStored({ state: initialTerminalRunsPollState(), workspaceId })
    let cancelled = false
    let failureCount = 0
    let inFlight = false
    let requestController: AbortController | null = null
    const loadRuns = () => {
      if (inFlight) return
      inFlight = true
      const controller = new AbortController()
      requestController = controller
      void listTerminalRuns(workspaceId, controller.signal)
        .then((runs) => {
          if (cancelled) return
          failureCount = 0
          setStored((current) => {
            const previous =
              current.workspaceId === workspaceId
                ? current.state
                : initialTerminalRunsPollState()
            return {
              state: terminalRunsAfterSuccess(
                previous,
                areTerminalRunsEqual(previous.runs, runs) ? previous.runs : runs
              ),
              workspaceId,
            }
          })
        })
        .catch((error: unknown) => {
          if (!cancelled) {
            failureCount = Math.min(failureCount + 1, 4)
            setStored((current) => ({
              state: terminalRunsAfterFailure(
                current.workspaceId === workspaceId
                  ? current.state
                  : initialTerminalRunsPollState()
              ),
              workspaceId,
            }))
          }
          if (!cancelled && !controller.signal.aborted) {
            console.error('[termestra] terminalRuns.list failed', error)
          }
        })
        .finally(() => {
          if (requestController === controller) requestController = null
          inFlight = false
          poller.schedule()
        })
    }
    const poller = createVisiblePagePoller({
      getDelay: () => getRefreshDelay(failureCount),
      load: loadRuns,
    })
    return () => {
      cancelled = true
      requestController?.abort()
      requestController = null
      poller.dispose()
    }
  }, [workspaceId])

  return stored.workspaceId === workspaceId ? stored.state : initialTerminalRunsPollState()
}

export const findOrchestratorRun = (
  runs: TerminalRunSummary[],
  workspaceId: string
): TerminalRunSummary | undefined =>
  runs.find((run) => run.agent_id === orchestratorAgentId(workspaceId))

export const findRunByAgentId = (
  runs: TerminalRunSummary[],
  agentId: string
): TerminalRunSummary | undefined => runs.find((run) => run.agent_id === agentId)
