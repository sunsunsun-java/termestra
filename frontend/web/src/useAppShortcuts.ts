import { useMemo } from 'react'

import type { WorkspaceSummary } from '../../src/shared/types.js'
import { useGlobalShortcuts } from './useGlobalShortcuts.js'

type UseAppShortcutsOptions = {
  bootstrapError: string | null
  onSelectWorkspace: (workspaceId: string) => void
  onTriggerAddDialog: () => void
  workspaces: WorkspaceSummary[] | null
}

const MAX_NUMBERED_WORKSPACES = 9

export const useAppShortcuts = ({
  bootstrapError,
  onSelectWorkspace,
  onTriggerAddDialog,
  workspaces,
}: UseAppShortcutsOptions): void => {
  const workspaceShortcuts = useMemo(
    () =>
      (workspaces ?? []).slice(0, MAX_NUMBERED_WORKSPACES).map((workspace, index) => ({
        handler: () => onSelectWorkspace(workspace.id),
        key: String(index + 1),
        mod: true,
      })),
    [onSelectWorkspace, workspaces]
  )

  const shortcuts = useMemo(
    () => [
      {
        handler: () => {
          if (workspaces !== null && bootstrapError === null) onTriggerAddDialog()
        },
        key: 'n',
        mod: true,
        shift: true,
      },
      ...workspaceShortcuts,
    ],
    [bootstrapError, onTriggerAddDialog, workspaces, workspaceShortcuts]
  )

  useGlobalShortcuts(shortcuts)
}
