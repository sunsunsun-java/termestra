// @vitest-environment jsdom
import { act, cleanup, render, renderHook } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import type { TerminalRunSummary } from '../web/src/api.js'
import type { TeamListItem, WorkspaceSummary } from '../src/shared/types.js'
import { WorkspaceNotifications } from '../web/src/notifications/WorkspaceNotifications.js'
import { useOrchestratorPaneState } from '../web/src/worker/useOrchestratorPaneState.js'

const mocks = vi.hoisted(() => ({ start: vi.fn(), notify: vi.fn() }))
vi.mock('../web/src/api.js', async (original) => ({ ...await original<typeof import('../web/src/api.js')>(), startAgentRun: mocks.start }))
vi.mock('../web/src/i18n.js', () => ({ useI18n: () => ({ language: 'en', t: (key: string) => key }) }))
vi.mock('../web/src/notifications/NotificationProvider.js', () => ({ useNotifications: () => ({ notify: mocks.notify }) }))
afterEach(() => { cleanup(); vi.clearAllMocks() })

const worker: TeamListItem = { id: 'worker', name: 'Worker', status: 'stopped', role: 'coder', pendingTaskCount: 0 }
const workspace = { id: 'ws', name: 'Workspace' } as WorkspaceSummary
const run: TerminalRunSummary = { agent_id: worker.id, agent_name: worker.name, run_id: 'new', status: 'starting', startup_phase: 'initializing' }
const ready: TerminalRunSummary = { ...run, status: 'running', startup_phase: 'ready' }
const idle: TeamListItem = { ...worker, status: 'idle' }

test('a failed orchestrator retry displays its current request error while retaining old output', async () => {
  mocks.start.mockRejectedValueOnce(new Error('Runtime is busy - please retry')).mockResolvedValueOnce({ runId: 'next' })
  const old: TerminalRunSummary = { ...run, agent_id: 'ws:orchestrator', run_id: 'old', status: 'error', startup_phase: 'failed', startup_message: 'Previous attempt timed out' }
  const { result, rerender } = renderHook(({ terminalRuns }) => {
    const [error, setError] = useState<string | null>(null)
    return useOrchestratorPaneState({ workspaceId: 'ws', terminalRuns, autostartError: error, onClearAutostartError: () => setError(null), onAfterStart: (response) => setError(response.error) })
  }, { initialProps: { terminalRuns: [old] } })
  await act(async () => result.current.restart())
  expect(result.current.state).toEqual({ kind: 'failed', runId: 'old', error: 'Runtime is busy - please retry' })
  await act(async () => result.current.restart())
  expect(result.current.state).toMatchObject({ kind: 'starting', runId: 'next' })
  rerender({ terminalRuns: [{ ...old, run_id: 'next', startup_message: 'New run failed' }] })
  expect(result.current.state).toEqual({ kind: 'failed', runId: 'next', error: 'New run failed' })
})

test.each(['team-first', 'runs-first'])('startup success is announced once with %s polling order', (order) => {
  const view = render(<WorkspaceNotifications workspace={workspace} workers={[worker]} terminalRuns={[run]} />)
  view.rerender(<WorkspaceNotifications workspace={workspace} workers={[order === 'team-first' ? idle : worker]} terminalRuns={[order === 'team-first' ? run : ready]} />)
  expect(mocks.notify).not.toHaveBeenCalled()
  view.rerender(<WorkspaceNotifications workspace={workspace} workers={[idle]} terminalRuns={[ready]} />)
  expect(mocks.notify).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ kind: 'success', brief: 'notifications.workerStarted.brief' }))
  view.rerender(<WorkspaceNotifications workspace={workspace} workers={[{ ...idle }]} terminalRuns={[{ ...ready }]} />)
  expect(mocks.notify).toHaveBeenCalledOnce()
})

test('a deferred start that fails never emits a success or stopped notification', () => {
  const view = render(<WorkspaceNotifications workspace={workspace} workers={[worker]} terminalRuns={[run]} />)
  view.rerender(<WorkspaceNotifications workspace={workspace} workers={[idle]} terminalRuns={[run]} />)
  view.rerender(<WorkspaceNotifications workspace={workspace} workers={[worker]} terminalRuns={[{ ...run, status: 'error', startup_phase: 'failed' }]} />)
  expect(mocks.notify).not.toHaveBeenCalled()
})

test('deletion or workspace switching clears deferred startup feedback', () => {
  const view = render(<WorkspaceNotifications workspace={workspace} workers={[worker]} terminalRuns={[run]} />)
  view.rerender(<WorkspaceNotifications workspace={workspace} workers={[idle]} terminalRuns={[run]} />)
  view.rerender(<WorkspaceNotifications workspace={workspace} workers={[]} terminalRuns={[]} />)
  view.rerender(<WorkspaceNotifications workspace={workspace} workers={[idle]} terminalRuns={[ready]} />)
  view.rerender(<WorkspaceNotifications workspace={{ ...workspace, id: 'other' }} workers={[worker]} terminalRuns={[run]} />)
  view.rerender(<WorkspaceNotifications workspace={workspace} workers={[idle]} terminalRuns={[ready]} />)
  expect(mocks.notify).not.toHaveBeenCalled()
})
