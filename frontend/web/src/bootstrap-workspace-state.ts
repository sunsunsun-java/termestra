import type { WorkspaceSummary } from '../../src/shared/types.js'

export const resolveBootstrapActiveWorkspaceId = (
  workspaces: WorkspaceSummary[],
  persistedId: string | null,
  currentId: string | null
) => {
  if (currentId && workspaces.some((workspace) => workspace.id === currentId)) {
    return currentId
  }
  if (persistedId && workspaces.some((workspace) => workspace.id === persistedId)) {
    return persistedId
  }
  return workspaces[0]?.id ?? null
}

export const mergeBootstrapWorkspaces = (
  current: WorkspaceSummary[] | null,
  incoming: WorkspaceSummary[]
): WorkspaceSummary[] => {
  if (current === null) return incoming
  const merged = new Map(current.map((workspace) => [workspace.id, workspace]))
  for (const workspace of incoming) merged.set(workspace.id, workspace)
  return Array.from(merged.values())
}
