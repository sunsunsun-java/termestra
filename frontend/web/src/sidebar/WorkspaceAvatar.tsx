import type { ReactNode } from 'react'

import { Avatar } from '../ui/Avatar.js'
import { deriveInitial, pickWorkspaceColor } from './derive-workspace-color.js'

type WorkspaceAvatarProps = {
  workspaceId: string
  name: string
  isActive: boolean
  working?: boolean
  workingCount?: number
  size?: number
}

const workingDecoration = (working: boolean, workingCount: number | undefined): ReactNode => {
  if (workingCount !== undefined && workingCount > 1) {
    return (
      <span
        aria-hidden
        className="absolute flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-xs font-medium leading-none tabular-nums"
        data-testid="workspace-avatar-working-count"
        style={{
          right: -4,
          bottom: -4,
          background: 'var(--status-green)',
          boxShadow: '0 0 0 2px var(--bg-1)',
          color: '#0a1f0a',
        }}
      >
        {workingCount > 9 ? '9+' : workingCount}
      </span>
    )
  }

  return working ? (
    <span
      aria-hidden
      className="status-dot status-dot--working absolute"
      style={{ right: -2, bottom: -2, boxShadow: '0 0 0 2px var(--bg-1)' }}
    />
  ) : null
}

/** A deterministic, local-only workspace mark; no uploaded or copied artwork required. */
export const WorkspaceAvatar = ({
  isActive,
  name,
  size = 32,
  working = false,
  workingCount,
  workspaceId,
}: WorkspaceAvatarProps) => {
  const palette = pickWorkspaceColor(workspaceId)

  return (
    <Avatar
      color={palette.token}
      data={{
        active: isActive ? 'true' : undefined,
        'color-label': palette.label,
        'workspace-id': workspaceId,
      }}
      decoration={workingDecoration(working, workingCount)}
      fontRatio={0.45}
      label={name}
      ringColor={isActive ? palette.token : null}
      size={size}
      testId="workspace-avatar"
    >
      {deriveInitial(name)}
    </Avatar>
  )
}
