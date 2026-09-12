import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, expect, test } from 'vitest'
import type { TeamListItem } from '../src/shared/types.js'
import type { TerminalRunSummary } from '../web/src/api.js'
import { useTerminalPanelTabs } from '../web/src/terminal/useTerminalPanelTabs.js'

const workers: TeamListItem[] = [{ id: 'alice', name: 'Alice', role: 'coder', status: 'idle', pendingTaskCount: 0 }]
const terminalRuns: TerminalRunSummary[] = [{ agent_id: 'ws:shell', agent_name: 'Shell', run_id: 'shell-run', status: 'running' }]
const key = 'termestra.terminal-panel.tabs.ws'
const expected = ['shell:shell-run', 'worker:alice']
const unloaded = { workspaceId: 'ws', workers: [] as TeamListItem[], terminalRuns: [] as TerminalRunSummary[], workersLoaded: false, terminalRunsLoaded: false }
afterEach(() => { cleanup(); localStorage.clear() })

for (const first of ['workers', 'terminalRuns'] as const) {
  test(`preserves cached tabs when ${first} loads before the other projection`, () => {
    localStorage.setItem(key, JSON.stringify(expected))
    localStorage.setItem('termestra.terminal-panel.active.ws', 'shell:shell-run')
    const view = renderHook(useTerminalPanelTabs, { initialProps: unloaded })
    expect(JSON.parse(localStorage.getItem(key)!)).toEqual(expected)
    view.rerender({ ...unloaded, workers: first === 'workers' ? workers : [], terminalRuns: first === 'terminalRuns' ? terminalRuns : [], workersLoaded: first === 'workers', terminalRunsLoaded: first === 'terminalRuns' })
    expect(JSON.parse(localStorage.getItem(key)!)).toEqual(expected)
    expect(view.result.current.activeId).toBe('shell:shell-run')
    view.rerender({ ...unloaded, workers, terminalRuns, workersLoaded: true, terminalRunsLoaded: true })
    expect(view.result.current.tabs.map(tab => tab.id)).toEqual(expected)
    expect(JSON.parse(localStorage.getItem(key)!)).toEqual(expected)
  })
}

test('confirmed empty projections remove dead tabs without waiting for a nonempty result', () => {
  localStorage.setItem(key, JSON.stringify(expected))
  const view = renderHook(useTerminalPanelTabs, { initialProps: unloaded })
  view.rerender({ ...unloaded, workersLoaded: true })
  expect(JSON.parse(localStorage.getItem(key)!)).toEqual(['shell:shell-run'])
  view.rerender({ ...unloaded, workersLoaded: true, terminalRunsLoaded: true })
  expect(JSON.parse(localStorage.getItem(key)!)).toEqual([])
  expect(view.result.current.activeId).toBeNull()
})

test('switching workspaces preserves each stored list and selection while new runs load', () => {
  localStorage.setItem(key, JSON.stringify(expected))
  const otherKey = 'termestra.terminal-panel.tabs.other'
  localStorage.setItem(otherKey, JSON.stringify(['shell:other-shell']))
  localStorage.setItem('termestra.terminal-panel.active.other', 'shell:other-shell')
  const view = renderHook(useTerminalPanelTabs, { initialProps: { ...unloaded, workers, terminalRuns, workersLoaded: true, terminalRunsLoaded: true } })
  act(() => view.result.current.setActive('worker:alice'))
  view.rerender({ ...unloaded, workspaceId: 'other', workersLoaded: true })
  expect(JSON.parse(localStorage.getItem(key)!)).toEqual(expected)
  expect(JSON.parse(localStorage.getItem(otherKey)!)).toEqual(['shell:other-shell'])
  expect(view.result.current.activeId).toBe('shell:other-shell')
  view.rerender({ ...unloaded, workers, terminalRuns, workersLoaded: true, terminalRunsLoaded: true })
  expect(view.result.current.activeId).toBe('worker:alice')
})

test('explicitly opened tabs persist while their projection is still loading', () => {
  const view = renderHook(useTerminalPanelTabs, { initialProps: unloaded })
  act(() => view.result.current.openShellTab('new-shell'))
  expect(JSON.parse(localStorage.getItem(key)!)).toEqual(['shell:new-shell'])
  expect(view.result.current.activeId).toBe('shell:new-shell')
})
