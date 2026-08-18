export interface WorkspaceCreateInput {
  commandPresetId: string | null
  name: string
  path: string
  startupCommand?: string
}

type WorkspaceCreateDraft = {
  commandPresetId: string
  name: string
  path: string
  startupCommand: string
}

export const buildWorkspaceCreateInput = ({
  commandPresetId,
  name,
  path,
  startupCommand,
}: WorkspaceCreateDraft): WorkspaceCreateInput => {
  const command = startupCommand.trim()
  return {
    commandPresetId: commandPresetId || null,
    name: name.trim(),
    path: path.trim(),
    ...(command ? { startupCommand: command } : {}),
  }
}
