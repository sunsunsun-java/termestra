import { useEffect, useState } from 'react'

import type { TeamListItem } from '../../src/shared/types.js'
import { listWorkers } from './api.js'
import {
  createVisiblePagePoller,
  type VisiblePagePollReason,
} from './lib/visible-page-poller.js'
import { mapSettledWithConcurrencyLimit } from './lib/bounded-concurrent-map.js'
import {
  markWorkspacePollFailure,
  markWorkspacePollSuccess,
  planWorkspaceWorkerRefresh,
  workspaceWorkerPollDelay,
} from './lib/workspace-worker-poll-plan.js'

const getRefreshDelay = (
  workspaceIds: readonly string[],
  activeWorkspaceId: string | null
) => workspaceWorkerPollDelay(workspaceIds, activeWorkspaceId)

const areWorkersEqual = (a: TeamListItem[], b: TeamListItem[]): boolean => {
  if (a.length !== b.length) return false
  return a.every((worker, index) => {
    const other = b[index]
    return (
      other !== undefined &&
      worker.commandPresetId === other.commandPresetId &&
      worker.id === other.id &&
      worker.lastPtyLine === other.lastPtyLine &&
      worker.name === other.name &&
      worker.pendingTaskCount === other.pendingTaskCount &&
      worker.role === other.role &&
      worker.status === other.status
    )
  })
}

const areWorkerMapsEqual = (
  a: Record<string, TeamListItem[]>,
  b: Record<string, TeamListItem[]>
): boolean => {
  const aKeys = Object.keys(a)
  const bKeys = Object.keys(b)
  if (aKeys.length !== bKeys.length) return false
  return bKeys.every((workspaceId) => areWorkersEqual(a[workspaceId] ?? [], b[workspaceId] ?? []))
}

export const useWorkspaceWorkers = (
  workspaceIds: readonly string[],
  activeWorkspaceId: string | null
) => {
  const workspaceKey = workspaceIds.join('\0')
  const [workersByWorkspaceId, setWorkersByWorkspaceId] = useState<Record<string, TeamListItem[]>>(
    {}
  )

  useEffect(() => {
    if (!workspaceKey) {
      setWorkersByWorkspaceId({})
      return
    }
    let cancelled = false
    let inFlight = false
    let refreshAllAfterFlight = false
    let requestController: AbortController | null = null
    const ids = workspaceKey.split('\0')
    const health = new Map()
    const loadWorkers = (reason: VisiblePagePollReason) => {
      const forceAll = reason !== 'scheduled'
      if (inFlight) {
        if (forceAll) refreshAllAfterFlight = true
        return
      }
      const now = Date.now()
      const dueIds = planWorkspaceWorkerRefresh({
        activeWorkspaceId,
        health,
        now,
        reason,
        workspaceIds: ids,
      })
      if (dueIds.length === 0) {
        poller.schedule()
        return
      }
      inFlight = true
      const controller = new AbortController()
      requestController = controller
      void mapSettledWithConcurrencyLimit(
        dueIds,
        (workspaceId) => listWorkers(workspaceId, controller.signal),
        controller.signal
      )
        .then((results) => {
          if (cancelled) return
          const completedAt = Date.now()
          for (const result of results) {
            if (result.status === 'fulfilled') {
              markWorkspacePollSuccess(health, result.item, completedAt)
            } else {
              markWorkspacePollFailure(health, result.item, completedAt)
              if (!controller.signal.aborted) {
                console.error('[termestra] workspaceWorkers.list failed', result.reason)
              }
            }
          }
          setWorkersByWorkspaceId((current) => {
            const next: Record<string, TeamListItem[]> = {}
            for (const workspaceId of ids) next[workspaceId] = current[workspaceId] ?? []
            for (const result of results) {
              if (result.status === 'fulfilled') next[result.item] = result.value
            }
            return areWorkerMapsEqual(current, next) ? current : next
          })
        })
        .finally(() => {
          if (requestController === controller) requestController = null
          inFlight = false
          if (refreshAllAfterFlight && document.visibilityState === 'visible') {
            refreshAllAfterFlight = false
            loadWorkers('visible')
          } else {
            poller.schedule()
          }
        })
    }
    const poller = createVisiblePagePoller({
      getDelay: () => getRefreshDelay(ids, activeWorkspaceId),
      load: loadWorkers,
    })
    return () => {
      cancelled = true
      requestController?.abort()
      requestController = null
      poller.dispose()
    }
  }, [activeWorkspaceId, workspaceKey])

  return [workersByWorkspaceId, setWorkersByWorkspaceId] as const
}
