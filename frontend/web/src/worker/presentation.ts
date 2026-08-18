import type { AgentStatus, TeamListItem, WorkerRole } from '../../../src/shared/types.js'

export type WorkerStatusKind = AgentStatus
export type WorkerRuntimeStatusKind = Extract<WorkerStatusKind, 'working' | 'stopped'>

export interface WorkerStatusPresentation {
  kind: WorkerStatusKind
  dotClass: string
  tone: string
}

const STATUS_VISUALS = {
  idle: {
    dotClass: 'status-dot status-dot--idle',
    tone: 'var(--text-tertiary)',
  },
  stopped: {
    dotClass: 'status-dot status-dot--stopped',
    tone: 'var(--status-red)',
  },
  working: {
    dotClass: 'status-dot status-dot--working',
    tone: 'var(--status-green)',
  },
} as const satisfies Record<AgentStatus, Omit<WorkerStatusPresentation, 'kind'>>

export const presentWorkerStatus = (worker: Pick<TeamListItem, 'status'>): WorkerStatusPresentation =>
  ({ kind: worker.status, ...STATUS_VISUALS[worker.status] })

export const presentRuntimeStatus = (
  hasRun: boolean
): WorkerStatusPresentation & { kind: WorkerRuntimeStatusKind } => {
  const kind = hasRun ? 'working' : 'stopped'
  return { kind, ...STATUS_VISUALS[kind] }
}

export type RoleTranslationKey = `role.${WorkerRole}`
export type StatusTranslationKey = 'common.idle' | 'common.running' | 'common.stopped'

export const roleTranslationKey = (role: WorkerRole): RoleTranslationKey => `role.${role}`

export const statusTranslationKey = (status: WorkerStatusKind): StatusTranslationKey => {
  if (status === 'working') return 'common.running'
  return status === 'idle' ? 'common.idle' : 'common.stopped'
}
