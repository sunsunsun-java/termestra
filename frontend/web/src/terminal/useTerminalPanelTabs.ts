import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import type { TeamListItem } from '../../../src/shared/types.js'
import type { StartupPhase, TerminalRunSummary } from '../api.js'
import { isWorkspaceShellRun } from '../api.js'
import {
  appendBoundedTerminalTab,
  sanitizeTerminalTabIds,
} from './terminal-tab-state.js'
import { runStartupPhase } from './run-startup.js'
import { findRunByAgentId } from './useTerminalRuns.js'

export type TerminalTab =
  | { id: string; kind: 'worker'; workerId: string; runId: string | null; label: string; startupPhase?: StartupPhase | null; startupMessage?: string | null | undefined }
  | { id: string; kind: 'shell'; runId: string; label: string }

const tabsKey = (workspaceId: string) => `termestra.terminal-panel.tabs.${workspaceId}`
const activeKey = (workspaceId: string) => `termestra.terminal-panel.active.${workspaceId}`

const workerTabId = (workerId: string) => `worker:${workerId}`
const shellTabId = (runId: string) => `shell:${runId}`

const readStoredIds = (key: string): string[] => {
  try {
    const raw = window.localStorage.getItem(key)
    if (!raw) return []
    if (raw.length > 40_000) return []
    const parsed = JSON.parse(raw)
    return sanitizeTerminalTabIds(parsed)
  } catch {
    return []
  }
}

const readStoredActive = (key: string): string => {
  try {
    const raw = window.localStorage.getItem(key) ?? ''
    if (raw.length > 512) return ''
    return sanitizeTerminalTabIds([raw])[0] ?? ''
  } catch {
    return ''
  }
}

const writeStored = (key: string, value: string): void => {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // ignored
  }
}

type Params = {
  workspaceId: string
  workers: TeamListItem[]
  terminalRuns: TerminalRunSummary[]
  workersLoaded: boolean
  terminalRunsLoaded: boolean
}

/**
 * Owns the bottom-panel tab list + active tab per workspace.
 *
 * The stored state is just an ordered list of tab ids (e.g. `worker:abc` /
 * `shell:run-x`) — display data is re-derived each render from `workers` /
 * `terminalRuns` so a deleted worker or a stopped shell automatically drops
 * its tab. Persistence is per-workspace; switching workspaces swaps the
 * loaded list without touching localStorage for the others.
 */
export const useTerminalPanelTabs = ({
  workspaceId,
  workers,
  terminalRuns,
  workersLoaded,
  terminalRunsLoaded,
}: Params) => {
  const [stateWorkspaceId, setStateWorkspaceId] = useState(workspaceId)
  const ownsState = stateWorkspaceId === workspaceId
  const [orderedIds, setOrderedIds] = useState<string[]>(() => readStoredIds(tabsKey(workspaceId)))
  const [activeId, setActiveIdRaw] = useState<string | null>(() => {
    const stored = readStoredActive(activeKey(workspaceId))
    return stored.length > 0 ? stored : null
  })
  // Latest-orderedIds ref so callbacks can compute next state synchronously
  // (avoids nested setState-in-updater patterns flagged by the reviewer).
  const orderedIdsRef = useRef(orderedIds)
  orderedIdsRef.current = orderedIds
  // Reload from localStorage when switching workspaces.
  useEffect(() => {
    if (stateWorkspaceId === workspaceId) return
    setStateWorkspaceId(workspaceId)
    setOrderedIds(readStoredIds(tabsKey(workspaceId)))
    const stored = readStoredActive(activeKey(workspaceId))
    setActiveIdRaw(stored.length > 0 ? stored : null)
  }, [stateWorkspaceId, workspaceId])

  // Persist only the state loaded for this workspace. Projections may still
  // be loading; their absence is not evidence that a saved tab was deleted.
  useEffect(() => {
    if (!ownsState) return
    writeStored(tabsKey(workspaceId), JSON.stringify(orderedIds))
  }, [orderedIds, ownsState, workspaceId])

  useEffect(() => {
    if (!ownsState) return
    writeStored(activeKey(workspaceId), activeId ?? '')
  }, [activeId, ownsState, workspaceId])

  const workerById = useMemo(() => new Map(workers.map((w) => [w.id, w] as const)), [workers])
  const shellRunById = useMemo(() => {
    const map = new Map<string, TerminalRunSummary>()
    for (const run of terminalRuns) {
      if (isWorkspaceShellRun(run, workspaceId)) map.set(run.run_id, run)
    }
    return map
  }, [terminalRuns, workspaceId])

  const tabs = useMemo<TerminalTab[]>(() => {
    const out: TerminalTab[] = []
    for (const id of orderedIds) {
      if (id.startsWith('worker:')) {
        const workerId = id.slice('worker:'.length)
        const worker = workerById.get(workerId)
        if (!worker) continue
        const run = findRunByAgentId(terminalRuns, worker.id)
        out.push({
          id,
          kind: 'worker',
          workerId,
          runId: run?.run_id ?? null,
          startupPhase: runStartupPhase(run),
          startupMessage: run?.startup_message,
          label: worker.name,
        })
      } else if (id.startsWith('shell:')) {
        const runId = id.slice('shell:'.length)
        const run = shellRunById.get(runId)
        if (!run) continue
        out.push({ id, kind: 'shell', runId, label: run.agent_name })
      }
    }
    return out
  }, [orderedIds, workerById, shellRunById, terminalRuns])

  // The two projections load independently. Only a successful snapshot of
  // the corresponding collection can prove a tab is gone, including an empty
  // snapshot. Never infer readiness from the other collection's contents.
  useEffect(() => {
    if (!ownsState) return
    setOrderedIds((current) => {
      const next = current.filter((id) => {
        if (id.startsWith('worker:')) {
          return !workersLoaded || workerById.has(id.slice('worker:'.length))
        }
        if (id.startsWith('shell:')) {
          return !terminalRunsLoaded || shellRunById.has(id.slice('shell:'.length))
        }
        return false
      })
      return next.length === current.length ? current : next
    })
  }, [ownsState, workerById, shellRunById, workersLoaded, terminalRunsLoaded])

  useEffect(() => {
    if (!ownsState) return
    if (activeId?.startsWith('worker:') && !workersLoaded) return
    if (activeId?.startsWith('shell:') && !terminalRunsLoaded) return
    if (activeId && tabs.some((tab) => tab.id === activeId)) return
    setActiveIdRaw(tabs[0]?.id ?? null)
  }, [activeId, ownsState, tabs, workersLoaded, terminalRunsLoaded])

  const openWorkerTab = useCallback((workerId: string) => {
    const id = workerTabId(workerId)
    setOrderedIds((current) => appendBoundedTerminalTab(current, id))
    setActiveIdRaw(id)
  }, [])

  const openShellTab = useCallback((runId: string) => {
    const id = shellTabId(runId)
    setOrderedIds((current) => appendBoundedTerminalTab(current, id))
    setActiveIdRaw(id)
  }, [])

  const closeTab = useCallback((tabId: string) => {
    const before = orderedIdsRef.current
    const next = before.filter((id) => id !== tabId)
    if (next.length === before.length) return
    setOrderedIds(next)
    setActiveIdRaw((activeNow) => {
      if (activeNow !== tabId) return activeNow
      const idx = before.indexOf(tabId)
      return next[idx] ?? next[idx - 1] ?? next[0] ?? null
    })
  }, [])

  const setActive = useCallback((tabId: string) => setActiveIdRaw(tabId), [])

  return { tabs, activeId, openWorkerTab, openShellTab, closeTab, setActive }
}
