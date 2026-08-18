import type { TeamListItem } from '../../../src/shared/types.js'
import type { TerminalRunSummary } from '../api.js'

/**
 * A page reload is safe only after the complete all-workspace terminal
 * projection and every cached workspace roster agree that no process remains
 * active.
 */
export const isServiceWorkerReloadSafe = (
  terminalRuns: readonly TerminalRunSummary[],
  terminalRunsReady: boolean,
  workersByWorkspaceId: Readonly<Record<string, readonly TeamListItem[]>>,
  workspaceIds: readonly string[]
): boolean =>
  terminalRunsReady &&
  workspaceIds.every((workspaceId) => Object.hasOwn(workersByWorkspaceId, workspaceId)) &&
  terminalRuns.every((run) => run.status === 'stopped') &&
  Object.values(workersByWorkspaceId).every((workers) =>
    workers.every((worker) => worker.status === 'stopped')
  )
