import { type Dispatch, type SetStateAction, useRef } from 'react'

import type { TeamListItem, WorkspaceSummary } from '../../src/shared/types.js'
import { deleteWorkspace as deleteWorkspaceApi, listWorkspaces } from './api.js'

type WorkersByWorkspaceId = Record<string, TeamListItem[]>

type WorkspaceDeleteOptions = {
  activeWorkspaceId: string | null
  onActiveDeleted: () => void
  onWorkspaceDeleted?: (workspaceId: string) => void
  selectWorkspace: (workspaceId: string | null) => void
  setWorkersByWorkspaceId: Dispatch<SetStateAction<WorkersByWorkspaceId>>
  setWorkspaces: Dispatch<SetStateAction<WorkspaceSummary[] | null>>
  workspaces: WorkspaceSummary[] | null
}

const getNextWorkspaceIdAfterDelete = (
  workspaces: WorkspaceSummary[],
  deletedWorkspaceId: string
): string | null => {
  const deletedIndex = workspaces.findIndex((workspace) => workspace.id === deletedWorkspaceId)
  const remaining = workspaces.filter((workspace) => workspace.id !== deletedWorkspaceId)
  if (deletedIndex < 0) return remaining[0]?.id ?? null
  return remaining[Math.min(deletedIndex, remaining.length - 1)]?.id ?? null
}

/**
 * Returns an async deleter that performs the workspace removal — *no* native
 * confirm or alert. The caller (Sidebar) owns the user-facing confirmation
 * surface (a <Confirm dialog) and the error reporting (toast) so we can keep
 * the dialog/toast UX consistent with the rest of M6-A.
 *
 * The returned function rejects on API failure; the caller catches and toasts.
 */
export const useWorkspaceDelete = ({
  activeWorkspaceId,
  onActiveDeleted,
  onWorkspaceDeleted,
  selectWorkspace,
  setWorkersByWorkspaceId,
  setWorkspaces,
  workspaces,
}: WorkspaceDeleteOptions) => {
  const activeWorkspaceIdRef = useRef(activeWorkspaceId)
  activeWorkspaceIdRef.current = activeWorkspaceId

  return async (workspace: WorkspaceSummary): Promise<void> => {
    const currentWorkspaces = workspaces ?? []
    const nextWorkspaceId = getNextWorkspaceIdAfterDelete(currentWorkspaces, workspace.id)

    await deleteWorkspaceApi(workspace.id)
    onWorkspaceDeleted?.(workspace.id)
    setWorkersByWorkspaceId((current) => {
      const next = { ...current }
      delete next[workspace.id]
      return next
    })
    let refreshedWorkspaces: WorkspaceSummary[]
    try {
      refreshedWorkspaces = await listWorkspaces()
    } catch (error) {
      setWorkspaces((current) => current?.filter((item) => item.id !== workspace.id) ?? current)
      if (workspace.id === activeWorkspaceIdRef.current) {
        onActiveDeleted()
        selectWorkspace(nextWorkspaceId)
      }
      throw new Error('Workspace was removed, but the list could not be refreshed. Reload the page.', {
        cause: error,
      })
    }
    setWorkspaces(refreshedWorkspaces)
    if (workspace.id === activeWorkspaceIdRef.current) {
      onActiveDeleted()
      const promotedDuplicate = refreshedWorkspaces.find(
        (item) => item.id !== workspace.id && item.path === workspace.path
      )
      const retainedNext = refreshedWorkspaces.some((item) => item.id === nextWorkspaceId)
        ? nextWorkspaceId
        : null
      selectWorkspace(promotedDuplicate?.id ?? retainedNext ?? refreshedWorkspaces[0]?.id ?? null)
    }
  }
}
