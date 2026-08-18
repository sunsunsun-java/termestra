import { useCallback, useRef, useState } from 'react'
import type { WorkspaceSummary } from '../../src/shared/types.js'
import {
  type CreateWorkspaceResponse,
  createWorkspace,
  type OrchestratorStartResult,
} from './api.js'
import { createSingleFlight, type SingleFlight } from './lib/single-flight.js'
import type { WorkspaceCreateInput } from './workspace/workspace-create-input.js'

interface UseWorkspaceCreateInput {
  /** Mutate workspaces list when create succeeds. */
  onWorkspaceCreated: (workspace: WorkspaceSummary) => void
  /** Report that the canonical path was already registered and selected. */
  onWorkspaceExisting?: (workspace: WorkspaceSummary) => void
  /** Surface server / network errors so the caller can toast them. */
  onError?: (message: string) => void
}

interface UseWorkspaceCreateOutput {
  /** workspaceId → sticky autostart error (cleared on Retry). */
  orchestratorAutostartErrors: Record<string, string | null>
  /** workspaceId → recent server-side autostart run id; used only to avoid immediate duplicate starts. */
  orchestratorAutostartRunIds: Record<string, string | null>
  forgetWorkspaceResult: (workspaceId: string) => void
  recordOrchestratorResult: (workspaceId: string, result: OrchestratorStartResult) => void
  createNewWorkspace: (input: WorkspaceCreateInput) => Promise<CreateWorkspaceResponse>
}

/**
 * Owns the per-workspace orchestrator autostart error state. This is sticky:
 * the error remains until the user clicks Retry (or a successful manual start
 * happens elsewhere), so the OrchestratorPane can keep showing failed-state.
 */
export const useWorkspaceCreate = ({
  onWorkspaceCreated,
  onWorkspaceExisting,
  onError,
}: UseWorkspaceCreateInput): UseWorkspaceCreateOutput => {
  const [orchestratorAutostartErrors, setErrors] = useState<Record<string, string | null>>({})
  const [orchestratorAutostartRunIds, setRunIds] = useState<Record<string, string | null>>({})
  const createOperationRef = useRef<(input: WorkspaceCreateInput) => Promise<CreateWorkspaceResponse>>(
    async () => {
      throw new Error('Workspace create operation is not ready')
    }
  )
  const createSingleFlightRef = useRef<
    SingleFlight<[WorkspaceCreateInput], CreateWorkspaceResponse> | undefined
  >(undefined)

  const recordOrchestratorResult = useCallback(
    (workspaceId: string, result: OrchestratorStartResult) => {
      setErrors((current) => ({ ...current, [workspaceId]: result.ok ? null : result.error }))
      setRunIds((current) => ({ ...current, [workspaceId]: result.ok ? result.run_id : null }))
    },
    []
  )

  const forgetWorkspaceResult = useCallback((workspaceId: string) => {
    setErrors((current) => {
      if (!(workspaceId in current)) return current
      const next = { ...current }
      delete next[workspaceId]
      return next
    })
    setRunIds((current) => {
      if (!(workspaceId in current)) return current
      const next = { ...current }
      delete next[workspaceId]
      return next
    })
  }, [])

  createOperationRef.current = async (input: WorkspaceCreateInput) => {
    try {
      const response = await createWorkspace({
        name: input.name,
        path: input.path,
        autostart_orchestrator: true,
        command_preset_id: input.commandPresetId,
        startup_command: input.startupCommand ?? null,
      })
      // A 200 response means the canonical path already exists. Its no-op
      // orchestrator_start payload must not erase a prior run id or sticky error.
      if (response.created) recordOrchestratorResult(response.id, response.orchestrator_start)
      const workspace = { id: response.id, name: response.name, path: response.path }
      onWorkspaceCreated(workspace)
      if (!response.created) onWorkspaceExisting?.(workspace)
      return response
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to create workspace'
      onError?.(message)
      throw error
    }
  }

  createSingleFlightRef.current ??= createSingleFlight((input: WorkspaceCreateInput) =>
    createOperationRef.current(input)
  )

  const createNewWorkspace = useCallback(
    (input: WorkspaceCreateInput) => createSingleFlightRef.current!.run(input),
    []
  )

  return {
    orchestratorAutostartErrors,
    orchestratorAutostartRunIds,
    forgetWorkspaceResult,
    recordOrchestratorResult,
    createNewWorkspace,
  }
}
