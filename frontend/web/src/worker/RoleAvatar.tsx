import { Braces, Code2, FlaskConical, Network, ScanSearch, type LucideIcon } from 'lucide-react'

import type { WorkerRole } from '../../../src/shared/types.js'
import { Avatar } from '../ui/Avatar.js'

type TeamRole = WorkerRole | 'orchestrator'
type StatusRing = 'working' | 'idle' | 'stopped' | 'none'

type RoleAvatarProps = {
  role: TeamRole
  size?: number
  statusRing?: StatusRing
}

type RoleMark = {
  Icon: LucideIcon
  tone: string
}

const ROLE_MARKS: Record<TeamRole, RoleMark> = {
  orchestrator: { Icon: Network, tone: 'var(--accent)' },
  coder: { Icon: Code2, tone: 'var(--status-blue)' },
  reviewer: { Icon: ScanSearch, tone: 'var(--status-purple)' },
  tester: { Icon: FlaskConical, tone: 'var(--status-orange)' },
  custom: { Icon: Braces, tone: 'var(--text-secondary)' },
}

const STATUS_TONES: Record<Exclude<StatusRing, 'none'>, string> = {
  working: 'var(--status-green)',
  idle: 'var(--text-tertiary)',
  stopped: 'var(--status-red)',
}

export const RoleAvatar = ({ role, size = 32, statusRing = 'none' }: RoleAvatarProps) => {
  const { Icon, tone } = ROLE_MARKS[role]
  const ringColor = statusRing === 'none' ? null : STATUS_TONES[statusRing]

  return (
    <Avatar
      color={tone}
      data={{ role, 'status-ring': statusRing }}
      ringColor={ringColor}
      ringSurface="var(--bg-2)"
      size={size}
      testId="role-avatar"
    >
      <Icon aria-hidden size={Math.max(12, Math.round(size * 0.52))} strokeWidth={1.8} />
    </Avatar>
  )
}
