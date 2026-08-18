import type { ReactNode } from 'react'

import type { WorkerRole } from '../../../src/shared/types.js'
import { RoleAvatar } from './RoleAvatar.js'
import { getCliAgentIcon } from './cli-agent-icons.js'

type StatusRing = 'working' | 'idle' | 'stopped' | 'none'

type CliAgentAvatarProps = {
  commandPresetId?: string | undefined
  workerRole: WorkerRole
  size?: number
  statusRing?: StatusRing
}

type CliAgentLogoProps = {
  commandPresetId?: string | undefined
  fallback?: ReactNode
  size?: number
  testId?: string
}

const STATUS_TONES: Record<Exclude<StatusRing, 'none'>, string> = {
  working: 'var(--status-green)',
  idle: 'var(--text-tertiary)',
  stopped: 'var(--status-red)',
}

export const CliAgentLogo = ({
  commandPresetId,
  fallback = null,
  size = 20,
  testId = 'cli-agent-logo',
}: CliAgentLogoProps) => {
  const icon = getCliAgentIcon(commandPresetId)
  if (!icon) return fallback

  return (
    <img
      alt={icon.label}
      className="shrink-0 select-none object-contain"
      data-command-preset={commandPresetId}
      data-testid={testId}
      draggable={false}
      height={size}
      src={icon.src}
      style={{ height: size, width: size }}
      width={size}
    />
  )
}

export const CliAgentAvatar = ({
  commandPresetId,
  size = 32,
  statusRing = 'none',
  workerRole,
}: CliAgentAvatarProps) => {
  const icon = getCliAgentIcon(commandPresetId)
  const ringColor = statusRing === 'none' ? null : STATUS_TONES[statusRing]

  if (!icon) {
    return <RoleAvatar role={workerRole} size={size} statusRing={statusRing} />
  }

  return (
    <span
      aria-label={icon.label}
      className="relative inline-flex shrink-0 items-center justify-center rounded-md border"
      data-command-preset={commandPresetId}
      data-status-ring={statusRing}
      data-testid="cli-agent-avatar"
      role="img"
      style={{
        background: 'var(--bg-1)',
        borderColor: 'var(--border-bright)',
        boxShadow: ringColor ? `0 0 0 2px var(--bg-2), 0 0 0 4px ${ringColor}` : undefined,
        height: size,
        width: size,
      }}
    >
      <img
        alt=""
        aria-hidden
        className="select-none object-contain"
        draggable={false}
        src={icon.src}
        style={{ height: Math.round(size * 0.72), width: Math.round(size * 0.72) }}
      />
    </span>
  )
}
