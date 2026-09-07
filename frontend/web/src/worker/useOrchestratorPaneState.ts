import { useCallback, useEffect, useRef, useState } from 'react'
import type { TerminalRunSummary } from '../api.js'
import { type OrchestratorStartResult, startAgentRun, stopAgentRun } from '../api.js'
import { isRunActive, runStartupPhase } from '../terminal/run-startup.js'
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
  restart: () => void
  stop: () => Promise<void>
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
    if (orchestratorRun && (!optimisticRunId || optimisticRunId === orchestratorRun.run_id)) {
      setOptimisticRun(null)
      setSuppressedRunId(null)
      if (autostartError && isRunActive(orchestratorRun)) onClearAutostartError()
    }
  }, [autostartError, onClearAutostartError, orchestratorRun, optimisticRunId])

  useEffect(() => {
    if (!suppressedRunId || orchestratorRun) return
    const timer = window.setTimeout(() => setSuppressedRunId(null), 1500)
    return () => window.clearTimeout(timer)
  }, [suppressedRunId, orchestratorRun])

  useEffect(() => {
    if (!optimisticRunId || optimisticRunId === orchestratorRun?.run_id) return
    const timer = window.setTimeout(() => setOptimisticRun(null), 2000)
    return () => window.clearTimeout(timer)
  }, [optimisticRunId, orchestratorRun])

  let state: OrchestratorPaneState
  const phase = runStartupPhase(orchestratorRun)
  if (optimisticRunId && optimisticRunId !== orchestratorRun?.run_id) {
    state = { kind: 'starting', runId: optimisticRunId, phase: 'initializing' }
  } else if (pendingStartWorkspaceId === workspaceId || suppressingAutostart) {
    state = { kind: 'starting' }
  } else if (orchestratorRun && phase === 'ready') {
    state = { kind: 'running', runId: orchestratorRun.run_id }
  } else if (orchestratorRun && (phase === 'initializing' || phase === 'waiting_for_user')) {
    state = { kind: 'starting', runId: orchestratorRun.run_id, phase, message: orchestratorRun.startup_message }
  } else if (orchestratorRun && phase === 'failed') {
    // A retry can fail before creating a new run. Keep the retained output,
    // but display that latest request failure until the next attempt clears it.
    state = { kind: 'failed', runId: orchestratorRun.run_id, error: autostartError ?? orchestratorRun.startup_message ?? '' }
  } else if (autostartError) {
    state = { kind: 'failed', error: autostartError }
  } else {
    state = { kind: 'stopped' }
  }

  const start = useCallback(() => {
    if (
      !workspaceId ||
      startInFlightByWorkspaceRef.current.has(workspaceId) ||
      isRunActive(orchestratorRun)
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

  const restart = useCallback(() => {
    onClearAutostartError()
    if (orchestratorRun && isRunActive(orchestratorRun)) {
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

  const stop = async () => {
    if (orchestratorRun && isRunActive(orchestratorRun)) await stopAgentRun(orchestratorRun.run_id)
  }

  return { state, start, restart, stop }
}
