import { useCallback, useEffect, useRef, useState } from 'react'
import type { TerminalRunSummary } from '../api.js'
import { type OrchestratorStartResult, startAgentRun, stopAgentRun } from '../api.js'
import { useI18n } from '../i18n.js'
import { findOrchestratorRun, orchestratorAgentId } from '../terminal/useTerminalRuns.js'
import { presentAgentStartError } from './agent-start-error.js'
import type { OrchestratorPaneState } from './OrchestratorPane.js'

interface UseOrchestratorPaneStateInput {
  workspaceId: string
  terminalRuns: TerminalRunSummary[]
  /** Latest known autostart error for this workspace (sticky until cleared). */
  autostartError: string | null
  /**
   * A just-created workspace may already have a server-side autostart run.
   * Suppress client-side auto-start briefly until terminalRuns catches up.
   */
  suppressAutostartRunId?: string | null
  onClearAutostartError: () => void
  /** Optional callback fired after a manual start succeeds — lets parent
   *  invalidate caches / refresh runs immediately. */
  onAfterStart?: (result: OrchestratorStartResult) => void
}

interface UseOrchestratorPaneStateOutput {
  state: OrchestratorPaneState
  start: () => void
  stop: () => void
  restart: () => void
}

/**
 * Derives the Orchestrator pane shape from live terminal runs + explicit
 * start attempts. Live `running` always wins; runtime restarts intentionally
 * land in `stopped` instead of silently autostarting a new CLI process.
 */
export const useOrchestratorPaneState = ({
  workspaceId,
  terminalRuns,
  autostartError,
  suppressAutostartRunId,
  onClearAutostartError,
  onAfterStart,
}: UseOrchestratorPaneStateInput): UseOrchestratorPaneStateOutput => {
  const { language } = useI18n()
  const orchestratorRun = findOrchestratorRun(terminalRuns, workspaceId)
  const agentId = orchestratorAgentId(workspaceId)
  const [pendingStartWorkspaceId, setPendingStartWorkspaceId] = useState<string | null>(null)
  const [optimisticRun, setOptimisticRun] = useState<{
    workspaceId: string
    runId: string
  } | null>(null)
  const [suppressedRunId, setSuppressedRunId] = useState<string | null>(null)
  const selectedWorkspaceIdRef = useRef(workspaceId)
  selectedWorkspaceIdRef.current = workspaceId
  const startInFlightByWorkspaceRef = useRef(new Set<string>())
  const optimisticRunId = optimisticRun?.workspaceId === workspaceId ? optimisticRun.runId : null
  const suppressingAutostart = Boolean(suppressedRunId && !orchestratorRun && !optimisticRunId)

  useEffect(() => {
    setSuppressedRunId(suppressAutostartRunId ?? null)
  }, [suppressAutostartRunId])

  useEffect(() => {
    if (orchestratorRun) {
      setPendingStartWorkspaceId(null)
      setOptimisticRun(null)
      setSuppressedRunId(null)
      if (autostartError) onClearAutostartError()
    }
  }, [autostartError, onClearAutostartError, orchestratorRun])

  useEffect(() => {
    if (!suppressedRunId || orchestratorRun) return
    const timer = window.setTimeout(() => setSuppressedRunId(null), 1500)
    return () => window.clearTimeout(timer)
  }, [suppressedRunId, orchestratorRun])

  useEffect(() => {
    if (!optimisticRunId || orchestratorRun) return
    const timer = window.setTimeout(() => setOptimisticRun(null), 2000)
    return () => window.clearTimeout(timer)
  }, [optimisticRunId, orchestratorRun])

  let state: OrchestratorPaneState
  if (orchestratorRun) {
    state = { kind: 'running', runId: orchestratorRun.run_id }
  } else if (optimisticRunId) {
    state = { kind: 'running', runId: optimisticRunId }
  } else if (pendingStartWorkspaceId === workspaceId || suppressingAutostart) {
    state = { kind: 'starting' }
  } else if (autostartError) {
    state = { kind: 'failed', error: autostartError }
  } else {
    state = { kind: 'stopped' }
  }

  const start = useCallback(() => {
    if (
      !workspaceId ||
      startInFlightByWorkspaceRef.current.has(workspaceId) ||
      orchestratorRun
    ) {
      return
    }
    startInFlightByWorkspaceRef.current.add(workspaceId)
    onClearAutostartError()
    setPendingStartWorkspaceId(workspaceId)
    void startAgentRun(workspaceId, agentId)
      .then((result) => {
        if (selectedWorkspaceIdRef.current === workspaceId) {
          setOptimisticRun({ workspaceId, runId: result.runId })
        }
        onAfterStart?.({ ok: true, error: null, run_id: result.runId })
      })
      .catch((error: unknown) => {
        const message = presentAgentStartError(error, language)
        if (selectedWorkspaceIdRef.current === workspaceId) setOptimisticRun(null)
        onAfterStart?.({ ok: false, error: message, run_id: null })
      })
      .finally(() => {
        startInFlightByWorkspaceRef.current.delete(workspaceId)
        setPendingStartWorkspaceId((current) => (current === workspaceId ? null : current))
      })
  }, [
    agentId,
    language,
    onAfterStart,
    onClearAutostartError,
    orchestratorRun,
    workspaceId,
  ])

  const stop = useCallback(() => {
    if (!orchestratorRun) return
    void stopAgentRun(orchestratorRun.run_id).catch((error: unknown) => {
      console.error('[termestra] swallowed:orchestrator.stop', error)
    })
  }, [orchestratorRun])

  const restart = useCallback(() => {
    onClearAutostartError()
    if (orchestratorRun) {
      if (startInFlightByWorkspaceRef.current.has(workspaceId)) return
      startInFlightByWorkspaceRef.current.add(workspaceId)
      setPendingStartWorkspaceId(workspaceId)
      void stopAgentRun(orchestratorRun.run_id)
        .catch((error: unknown) => {
          // Best-effort stop before restart; failure is reported via the
          // subsequent .catch on startAgentRun if start fails.
          console.error('[termestra] swallowed:orchestrator.restart.stop', error)
        })
        .then(() => startAgentRun(workspaceId, agentId))
        .then((result) => {
          if (selectedWorkspaceIdRef.current === workspaceId) {
            setOptimisticRun({ workspaceId, runId: result.runId })
          }
          onAfterStart?.({ ok: true, error: null, run_id: result.runId })
        })
        .catch((error: unknown) => {
          const message = presentAgentStartError(error, language)
          onAfterStart?.({ ok: false, error: message, run_id: null })
        })
        .finally(() => {
          startInFlightByWorkspaceRef.current.delete(workspaceId)
          setPendingStartWorkspaceId((current) => (current === workspaceId ? null : current))
        })
      return
    }
    start()
  }, [agentId, language, onAfterStart, onClearAutostartError, orchestratorRun, start, workspaceId])

  return { state, start, stop, restart }
}
