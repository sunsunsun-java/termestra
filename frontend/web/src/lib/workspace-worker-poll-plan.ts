import type { VisiblePagePollReason } from './visible-page-poller.js'

export const ACTIVE_WORKSPACE_REFRESH_INTERVAL_MS = 500
export const INACTIVE_WORKSPACE_REFRESH_INTERVAL_MS = 5000

interface WorkspaceWorkerPollPlanInput {
  activeWorkspaceId: string | null
  health?: ReadonlyMap<string, WorkspacePollHealth>
  lastAttemptAt?: ReadonlyMap<string, number>
  now: number
  reason: VisiblePagePollReason
  workspaceIds: readonly string[]
}

interface WorkspacePollHealth {
  failureCount: number
  lastAttemptAt: number
}

const MAX_BACKOFF_EXPONENT = 4

const prioritizeActiveWorkspace = (
  workspaceIds: string[],
  activeWorkspaceId: string | null
): string[] => {
  if (activeWorkspaceId === null) return workspaceIds
  const activeIndex = workspaceIds.indexOf(activeWorkspaceId)
  if (activeIndex <= 0) return workspaceIds
  return [
    activeWorkspaceId,
    ...workspaceIds.slice(0, activeIndex),
    ...workspaceIds.slice(activeIndex + 1),
  ]
}

export const markWorkspacePollSuccess = (
  health: Map<string, WorkspacePollHealth>,
  workspaceId: string,
  attemptedAt: number
): void => {
  health.set(workspaceId, { failureCount: 0, lastAttemptAt: attemptedAt })
}

export const markWorkspacePollFailure = (
  health: Map<string, WorkspacePollHealth>,
  workspaceId: string,
  attemptedAt: number
): void => {
  const previous = health.get(workspaceId)
  health.set(workspaceId, {
    failureCount: Math.min((previous?.failureCount ?? 0) + 1, MAX_BACKOFF_EXPONENT),
    lastAttemptAt: attemptedAt,
  })
}

/**
 * Selects the smallest workspace set needed for a worker-status refresh.
 *
 * Initial load and visibility restoration deliberately refresh every workspace
 * so the sidebar never presents stale status after a suspended/background tab.
 * Scheduled ticks keep the active workspace responsive while pacing inactive
 * sidebar summaries at one tenth of that rate.
 */
export const planWorkspaceWorkerRefresh = ({
  activeWorkspaceId,
  health,
  lastAttemptAt,
  now,
  reason,
  workspaceIds,
}: WorkspaceWorkerPollPlanInput): string[] => {
  const uniqueIds = Array.from(new Set(workspaceIds))
  if (reason !== 'scheduled') return prioritizeActiveWorkspace(uniqueIds, activeWorkspaceId)

  return prioritizeActiveWorkspace(
    uniqueIds.filter((workspaceId) => {
      const workspaceHealth = health?.get(workspaceId)
      const previousAttempt = workspaceHealth?.lastAttemptAt ?? lastAttemptAt?.get(workspaceId)
      if (previousAttempt === undefined) return true
      const baseInterval =
        workspaceId === activeWorkspaceId
          ? ACTIVE_WORKSPACE_REFRESH_INTERVAL_MS
          : INACTIVE_WORKSPACE_REFRESH_INTERVAL_MS
      const interval = Math.min(
        baseInterval * 2 ** (workspaceHealth?.failureCount ?? 0),
        INACTIVE_WORKSPACE_REFRESH_INTERVAL_MS
      )
      return now - previousAttempt >= interval
    }),
    activeWorkspaceId
  )
}

export const workspaceWorkerPollDelay = (
  workspaceIds: readonly string[],
  activeWorkspaceId: string | null
): number =>
  activeWorkspaceId !== null && workspaceIds.includes(activeWorkspaceId)
    ? ACTIVE_WORKSPACE_REFRESH_INTERVAL_MS
    : INACTIVE_WORKSPACE_REFRESH_INTERVAL_MS
