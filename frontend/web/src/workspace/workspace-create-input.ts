import type { WorkspaceRevisionSelectionPayload } from '../api.js'

export interface WorkspaceCreateInput {
  commandPresetId: string | null
  name: string
  path: string
  registrationId: string
  revisionSelection: WorkspaceRevisionSelectionPayload
  startupCommand?: string
}

type WorkspaceCreateDraft = {
  commandPresetId: string
  name: string
  path: string
  registrationId: string
  revisionSelection: WorkspaceRevisionSelectionPayload
  startupCommand: string
}

export const buildWorkspaceCreateInput = ({
  commandPresetId,
  name,
  path,
  registrationId,
  revisionSelection,
  startupCommand,
}: WorkspaceCreateDraft): WorkspaceCreateInput => {
  const command = startupCommand.trim()
  return {
    commandPresetId: commandPresetId || null,
    name: name.trim(),
    path: path.trim(),
    registrationId,
    revisionSelection,
    ...(command ? { startupCommand: command } : {}),
  }
}
