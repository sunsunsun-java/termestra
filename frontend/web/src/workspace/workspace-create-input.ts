import type { AgentLaunchInput, WorkspaceRevisionSelectionPayload } from '../api.js'

export interface WorkspaceCreateInput {
  launch: AgentLaunchInput
  name: string
  path: string
  registrationId: string
  revisionSelection: WorkspaceRevisionSelectionPayload
}

type WorkspaceCreateDraft = {
  commandPresetId: string
  commandPresetRevision: number | undefined
  modelId: string
  modelMode: 'default' | 'explicit'
  name: string
  path: string
  registrationId: string
  revisionSelection: WorkspaceRevisionSelectionPayload
  startupCommand: string
}

export const buildWorkspaceCreateInput = ({
  commandPresetId,
  commandPresetRevision,
  modelId,
  modelMode,
  name,
  path,
  registrationId,
  revisionSelection,
  startupCommand,
}: WorkspaceCreateDraft): WorkspaceCreateInput => {
  const command = startupCommand.trim()
  const launch: AgentLaunchInput = command
    ? {
        type: 'startup',
        startup_command: command,
        ...(commandPresetId ? { recovery_preset_id: commandPresetId } : {}),
      }
    : {
        type: 'preset',
        preset_id: commandPresetId,
        ...(modelMode === 'explicit' && modelId.trim() ? { model_id: modelId.trim() } : {}),
        ...(commandPresetRevision === undefined
          ? {}
          : { expected_preset_revision: commandPresetRevision }),
      }
  return {
    launch,
    name: name.trim(),
    path: path.trim(),
    registrationId,
    revisionSelection,
  }
}
