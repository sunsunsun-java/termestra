import type { TeamListItem, WorkspaceSummary } from '../../../src/shared/types.js'
import { DEMO_WORKERS, DEMO_WORKSPACE } from './demo-fixture.js'

interface WorkspaceStateInput {
  demoMode: boolean
  workspaces: WorkspaceSummary[] | null
  activeWorkspaceId: string | null
  workersByWorkspaceId: Record<string, TeamListItem[]>
}

interface EffectiveWorkspaceState {
  effectiveActiveWorkspaceId: string | null
  effectiveWorkspaces: WorkspaceSummary[] | null
  effectiveWorkersByWorkspaceId: Record<string, TeamListItem[]>
  effectiveActiveWorkspace: WorkspaceSummary | undefined
  pollWorkspaceId: string | null
}

const projectLiveWorkspace = ({
  activeWorkspaceId,
  workersByWorkspaceId,
  workspaces,
}: Omit<WorkspaceStateInput, 'demoMode'>): EffectiveWorkspaceState => ({
  effectiveActiveWorkspace: workspaces?.find(({ id }) => id === activeWorkspaceId),
  effectiveActiveWorkspaceId: activeWorkspaceId,
  effectiveWorkersByWorkspaceId: workersByWorkspaceId,
  effectiveWorkspaces: workspaces,
  pollWorkspaceId: activeWorkspaceId,
})

const projectDemoWorkspace = (): EffectiveWorkspaceState => ({
  effectiveActiveWorkspace: DEMO_WORKSPACE,
  effectiveActiveWorkspaceId: DEMO_WORKSPACE.id,
  effectiveWorkersByWorkspaceId: { [DEMO_WORKSPACE.id]: DEMO_WORKERS },
  effectiveWorkspaces: [DEMO_WORKSPACE],
  pollWorkspaceId: null,
})

/** Selects one coherent workspace projection so demo data never leaks into live polling. */
export const useEffectiveWorkspaceState = (input: WorkspaceStateInput): EffectiveWorkspaceState => {
  if (input.demoMode) return projectDemoWorkspace()

  const { activeWorkspaceId, workersByWorkspaceId, workspaces } = input
  return projectLiveWorkspace({ activeWorkspaceId, workersByWorkspaceId, workspaces })
}
