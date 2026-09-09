import { useCallback, useEffect, useState } from 'react'

import {
  listDispatchDeliveryIssues,
  retryDispatchDelivery,
  listReportDeliveryIssues,
  retryReportDelivery,
  type DeliveryIssue,
} from '../api.js'
import { createVisiblePagePoller } from '../lib/visible-page-poller.js'

export const useDispatchDeliveryIssues = (workspaceId: string) => {
  const [issues, setIssues] = useState<DeliveryIssue[]>([])
  const [retryingIds, setRetryingIds] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(
    async (signal?: AbortSignal) => {
      const [dispatches, reports] = await Promise.all([
        listDispatchDeliveryIssues(workspaceId, signal), listReportDeliveryIssues(workspaceId, signal),
      ])
      return [...dispatches.map((item) => ({ ...item, kind: 'dispatch' as const })), ...reports]
    },
    [workspaceId]
  )

  useEffect(() => {
    if (!workspaceId) {
      setIssues([])
      return
    }
    setIssues([])
    setError(null)
    let disposed = false
    let controller: AbortController | null = null
    let consecutiveFailures = 0
    const poller = createVisiblePagePoller({
      getDelay: () => Math.min(30_000, 2_000 * 2 ** consecutiveFailures),
      load: () => {
        controller?.abort()
        const request = new AbortController()
        controller = request
        void refresh(request.signal)
          .then((nextIssues) => {
            if (!disposed) setIssues(nextIssues)
            consecutiveFailures = 0
          })
          .catch((failure) => {
            if (!disposed && !request.signal.aborted) {
              consecutiveFailures = Math.min(consecutiveFailures + 1, 4)
              console.error('[termestra] dispatch delivery status failed', failure)
            }
          })
          .finally(() => {
            if (!disposed) poller.schedule()
          })
      },
    })
    return () => {
      disposed = true
      controller?.abort()
      poller.dispose()
    }
  }, [refresh, workspaceId])

  const retry = useCallback(
    async (dispatchId: string, confirmUncertain = false) => {
      const issue = issues.find((item) => item.id === dispatchId)
      if (issue?.kind === 'report' && issue.deliveryState === 'uncertain' && !confirmUncertain) return
      setRetryingIds((current) => new Set(current).add(dispatchId))
      setError(null)
      try {
        if (issue?.kind === 'report') await retryReportDelivery(workspaceId, dispatchId, confirmUncertain)
        else await retryDispatchDelivery(workspaceId, dispatchId)
        setIssues((current) => current.filter((item) => item.id !== dispatchId))
      } catch (failure) {
        setError(failure instanceof Error ? failure.message : String(failure))
      } finally {
        setRetryingIds((current) => {
          const next = new Set(current)
          next.delete(dispatchId)
          return next
        })
      }
    },
    [workspaceId, issues]
  )

  return { error, issues, retry, retryingIds }
}
