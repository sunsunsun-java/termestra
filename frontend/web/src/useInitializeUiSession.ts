import { useEffect, useState } from 'react'

import type { WorkspaceSummary } from '../../src/shared/types.js'
import {
  getActiveWorkspaceId,
  initializeUiSession,
  listWorkspaces,
  saveActiveWorkspaceId,
} from './api.js'
import {
  mergeBootstrapWorkspaces,
  resolveBootstrapActiveWorkspaceId,
} from './bootstrap-workspace-state.js'

export const useInitializeUiSession = (
  workspaces: WorkspaceSummary[] | null,
  activeWorkspaceId: string | null,
  setWorkspaces: (
    value:
      | WorkspaceSummary[]
      | null
      | ((current: WorkspaceSummary[] | null) => WorkspaceSummary[] | null)
  ) => void,
  setActiveWorkspaceId: (value: string | null) => void,
  onError?: (message: string) => void
) => {
  const [pendingSelection, setPendingSelection] = useState<{
    persistedId: string | null
  } | null>(null)

  useEffect(() => {
    let cancelled = false
    const controller = new AbortController()
    void initializeUiSession()
      .then(async () => {
        const [items, persistedId] = await Promise.all([
          listWorkspaces(controller.signal),
          getActiveWorkspaceId(controller.signal).catch(() => null),
        ])
        return { items, persistedId }
      })
      .then(({ items, persistedId }) => {
        if (!cancelled) {
          setWorkspaces((current) => mergeBootstrapWorkspaces(current, items))
          setPendingSelection({ persistedId })
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          // Keep workspaces=null on bootstrap failure so the UI does NOT fall
          // into the empty-workspaces branch (which would render WelcomePane,
          // making "runtime down" indistinguishable from "no workspaces yet").
          setActiveWorkspaceId(null)
          if (onError) {
            onError('Could not reach Termestra runtime. Refresh once the runtime is back up.')
          }
        }
        if (!cancelled && !controller.signal.aborted) {
          console.error('[termestra] initSession.bootstrap failed', error)
        }
      })
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [setActiveWorkspaceId, setWorkspaces, onError])

  useEffect(() => {
    if (!pendingSelection || workspaces === null) return
    const nextActiveWorkspaceId = resolveBootstrapActiveWorkspaceId(
      workspaces,
      pendingSelection.persistedId,
      activeWorkspaceId
    )
    setActiveWorkspaceId(nextActiveWorkspaceId)
    if (pendingSelection.persistedId !== nextActiveWorkspaceId) {
      saveActiveWorkspaceId(nextActiveWorkspaceId).catch((error: unknown) => {
        console.error('[termestra] swallowed:initSession.save', error)
      })
    }
    setPendingSelection(null)
  }, [activeWorkspaceId, pendingSelection, setActiveWorkspaceId, workspaces])
}
