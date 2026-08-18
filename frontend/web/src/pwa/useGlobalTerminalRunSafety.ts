import { useCallback, useEffect, useRef, useState } from 'react'

import { listTerminalRuns } from '../api.js'
import { createVisiblePagePoller } from '../lib/visible-page-poller.js'
import {
  type GlobalTerminalRunSnapshot,
  probeGlobalTerminalRuns,
} from './global-terminal-run-safety.js'

const REFRESH_INTERVAL_MS = 5_000
const NOT_READY: GlobalTerminalRunSnapshot = { ready: false, runs: [] }

interface StoredSnapshot {
  snapshot: GlobalTerminalRunSnapshot
  workspaceKey: string
}

interface DesiredProbe {
  enabled: boolean
  scopeKey: string
}

export interface GlobalTerminalRunSafety {
  snapshot: GlobalTerminalRunSnapshot
  verifyNow: () => Promise<GlobalTerminalRunSnapshot>
}

/**
 * Starts the all-workspace safety probe only while an update is waiting. The
 * periodic path is visible-page-only and single-flight; verifyNow aborts any
 * older observation and makes a fresh check immediately before reload.
 */
export const useGlobalTerminalRunSafety = (
  enabled: boolean,
  workspaceIds: readonly string[]
): GlobalTerminalRunSafety => {
  const workspaceKey = workspaceIds.join('\0')
  const activationRef = useRef({ enabled: false, generation: 0 })
  if (activationRef.current.enabled !== enabled) {
    activationRef.current = {
      enabled,
      generation: activationRef.current.generation + (enabled ? 1 : 0),
    }
  }
  const scopeKey = `${activationRef.current.generation}:${workspaceKey}`
  const desiredRef = useRef<DesiredProbe>({ enabled, scopeKey })
  desiredRef.current = { enabled, scopeKey }
  const verifyRef = useRef<() => Promise<GlobalTerminalRunSnapshot>>(async () => NOT_READY)
  const [stored, setStored] = useState<StoredSnapshot>({
    snapshot: NOT_READY,
    workspaceKey: '',
  })

  useEffect(() => {
    if (!enabled) {
      verifyRef.current = async () => NOT_READY
      return
    }

    const ids = workspaceKey ? workspaceKey.split('\0') : []
    let disposed = false
    let activeController: AbortController | null = null
    let activeProbe: Promise<GlobalTerminalRunSnapshot> | null = null

    const publish = (snapshot: GlobalTerminalRunSnapshot) => {
      if (
        disposed ||
        desiredRef.current.enabled !== true ||
        desiredRef.current.scopeKey !== scopeKey
      ) {
        return
      }
      setStored({ snapshot, workspaceKey: scopeKey })
      if (snapshot.failureWorkspaceId) {
        console.error(
          `[termestra] update safety terminal probe failed for workspace ${snapshot.failureWorkspaceId}`,
          snapshot.failure
        )
      }
    }

    const startProbe = (): Promise<GlobalTerminalRunSnapshot> => {
      if (activeProbe) return activeProbe
      const controller = new AbortController()
      activeController = controller
      const probe = probeGlobalTerminalRuns(ids, listTerminalRuns, controller.signal)
      activeProbe = probe
      void probe
        .then((snapshot) => {
          publish(snapshot)
        })
        .finally(() => {
          if (activeController === controller) activeController = null
          if (activeProbe === probe) activeProbe = null
        })
      return probe
    }

    const poller = createVisiblePagePoller({
      getDelay: () => REFRESH_INTERVAL_MS,
      load: () => {
        void startProbe().finally(() => poller.schedule())
      },
    })

    verifyRef.current = async () => {
      const previousProbe = activeProbe
      if (previousProbe) {
        activeController?.abort()
        await previousProbe
        if (activeProbe === previousProbe) activeProbe = null
      }
      if (
        disposed ||
        desiredRef.current.enabled !== true ||
        desiredRef.current.scopeKey !== scopeKey
      ) {
        return NOT_READY
      }
      const snapshot = await startProbe()
      if (
        desiredRef.current.enabled !== true ||
        desiredRef.current.scopeKey !== scopeKey
      ) {
        return NOT_READY
      }
      return snapshot
    }

    return () => {
      disposed = true
      activeController?.abort()
      activeController = null
      verifyRef.current = async () => NOT_READY
      poller.dispose()
    }
  }, [enabled, scopeKey, workspaceKey])

  const verifyNow = useCallback(() => verifyRef.current(), [])
  const snapshot =
    enabled && stored.workspaceKey === scopeKey ? stored.snapshot : NOT_READY
  return { snapshot, verifyNow }
}
