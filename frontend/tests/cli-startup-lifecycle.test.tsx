// @vitest-environment jsdom

import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import type { FormEvent, ReactNode } from 'react'
import { afterEach, describe, expect, test, vi } from 'vitest'
import type { TerminalRunSummary } from '../web/src/api.js'
import { I18nProvider } from '../web/src/i18n.js'
import { WorkerModal } from '../web/src/worker/WorkerModal.js'
import { WorkerCard } from '../web/src/worker/WorkerCard.js'
import { useOrchestratorPaneState } from '../web/src/worker/useOrchestratorPaneState.js'
import { useWorkerActions } from '../web/src/worker/useWorkerActions.js'
import { useWorkerComposer } from '../web/src/worker/useWorkerComposer.js'
import { mergeTerminalRuns } from '../web/src/terminal/useOptimisticTerminalRuns.js'
import { findRunByAgentId, useTerminalRuns } from '../web/src/terminal/useTerminalRuns.js'
import { isRunActive, runStartupPhase } from '../web/src/terminal/run-startup.js'
import { WorkspaceTerminalPanels } from '../web/src/WorkspaceTerminalPanels.js'

const api = vi.hoisted(() => ({ startAgentRun: vi.fn(), createWorker: vi.fn(), listTerminalRuns: vi.fn() }))
vi.mock('../web/src/api.js', async (original) => ({
  ...await original<typeof import('../web/src/api.js')>(),
  ...api,
}))
vi.mock('../web/src/terminal/TerminalView.js', () => ({
  TerminalView: ({ runId, inputEnabled }: { runId: string; inputEnabled: boolean }) =>
    <div data-testid={`pty-${runId}`} data-input-enabled={inputEnabled} />,
}))

const wrapper = ({ children }: { children: ReactNode }) => <I18nProvider>{children}</I18nProvider>
const worker = { id: 'worker-1', name: 'Hermes', role: 'coder' as const, status: 'stopped' as const, pendingTaskCount: 0 }
const run = (phase: NonNullable<TerminalRunSummary['startup_phase']>, id = 'run-1'): TerminalRunSummary => ({
  agent_id: worker.id, agent_name: worker.name, run_id: id,
  status: phase === 'failed' ? 'error' : phase === 'ready' ? 'running' : 'starting',
  startup_phase: phase, startup_message: phase === 'failed' ? 'Initialization timed out' : null,
})

afterEach(() => { cleanup(); vi.clearAllMocks(); vi.useRealTimers() })

describe('CLI startup lifecycle', () => {
  test('waiting and failure preserve the same terminal while retry stays available only after failure', () => {
    const onStart = vi.fn()
    const onStop = vi.fn()
    const { rerender } = render(<WorkerModal worker={worker} run={run('waiting_for_user')} starting={false} onStart={onStart} onStop={onStop} onClose={vi.fn()} />, { wrapper })
    expect(screen.getByText('Waiting for your confirmation in the terminal')).not.toBeNull()
    expect(document.getElementById('worker-pty-run-1')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Stop' }))
    expect(onStop).toHaveBeenCalledOnce()
    rerender(<WorkerModal worker={worker} run={run('ready')} starting={false} onStart={onStart} onClose={vi.fn()} />)
    expect(screen.getByText('Ready to receive tasks')).not.toBeNull()
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull()
    rerender(<WorkerModal worker={worker} run={run('failed')} starting={false} onStart={onStart} onClose={vi.fn()} />)
    expect(screen.getByRole('alert').textContent).toContain('Initialization timed out')
    expect(document.getElementById('worker-pty-run-1')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(onStart).toHaveBeenCalledWith(worker)
  })

  test('a new attempt or another worker cannot inherit the previous run error', () => {
    const props = { worker, starting: false, onStart: vi.fn(), onClose: vi.fn() }
    const { rerender } = render(<WorkerModal {...props} run={run('failed')} />, { wrapper })
    expect(screen.getByRole('alert')).not.toBeNull()
    rerender(<WorkerModal {...props} run={run('initializing', 'run-2')} />)
    expect(screen.queryByRole('alert')).toBeNull()
    expect(document.getElementById('worker-pty-run-2')).not.toBeNull()
    rerender(<WorkerModal {...props} worker={{ ...worker, id: 'other', name: 'Pi' }} />)
    expect(screen.queryByText('Initialization timed out')).toBeNull()
  })

  test('retained failed runs enable restart and keep terminal input disabled', () => {
    const failed = run('failed')
    render(<><WorkerCard worker={worker} hasRun={isRunActive(failed)} startupPhase={runStartupPhase(failed)} onAction={vi.fn()} onClick={vi.fn()} />
      <WorkspaceTerminalPanels workspaceId="workspace" terminalRuns={[failed, { ...run('waiting_for_user', 'waiting'), agent_id: 'other' }]} /></>, { wrapper })
    expect(screen.getByTestId('worker-card-start-worker-1')).not.toBeNull()
    expect(screen.getByTestId('pty-run-1').getAttribute('data-input-enabled')).toBe('false')
    expect(screen.getByTestId('pty-waiting').getAttribute('data-input-enabled')).toBe('true')
  })

  test('a process exiting successfully before startup finishes still presents startup failure', () => {
    const exited = { ...run('failed'), status: 'exited' }
    expect(runStartupPhase(exited)).toBe('failed')
    expect(isRunActive(exited)).toBe(false)
    render(<WorkerModal worker={worker} run={exited} starting={false} onStart={vi.fn()} onClose={vi.fn()} />, { wrapper })
    expect(screen.getByRole('alert').textContent).toContain('Initialization timed out')
    expect(screen.getByRole('button', { name: 'Retry' })).not.toBeNull()
  })

  test('a new optimistic attempt replaces old failure, but its authoritative failure replaces optimism', () => {
    const old = run('failed', 'old')
    const pending = run('initializing', 'new')
    expect(mergeTerminalRuns([old], [pending])).toEqual([pending])
    expect(mergeTerminalRuns([run('failed', 'new')], [pending])).toEqual([run('failed', 'new')])
    expect(findRunByAgentId([old, pending], worker.id)).toEqual(pending)
  })

  test('polling publishes phase changes even while run id and starting status stay unchanged', async () => {
    api.listTerminalRuns.mockResolvedValueOnce([run('initializing')]).mockResolvedValue([run('waiting_for_user')])
    const { result } = renderHook(() => useTerminalRuns('workspace'))
    await waitFor(() => expect(result.current.runs[0]?.startup_phase).toBe('initializing'))
    await waitFor(() => expect(result.current.runs[0]?.startup_phase).toBe('waiting_for_user'), { timeout: 2000 })
  })

  test('orchestrator waits for authoritative readiness and permits retry of a retained failure', async () => {
    const orch = (phase: NonNullable<TerminalRunSummary['startup_phase']>, id = 'run-1') => ({ ...run(phase, id), agent_id: 'workspace:orchestrator' })
    const { result, rerender } = renderHook(({ terminalRuns }) => useOrchestratorPaneState({ workspaceId: 'workspace', terminalRuns, autostartError: null, onClearAutostartError: vi.fn() }), { initialProps: { terminalRuns: [orch('waiting_for_user')] }, wrapper })
    expect(result.current.state).toMatchObject({ kind: 'starting', runId: 'run-1', phase: 'waiting_for_user' })
    rerender({ terminalRuns: [orch('ready')] })
    expect(result.current.state).toEqual({ kind: 'running', runId: 'run-1' })
    rerender({ terminalRuns: [orch('failed')] })
    expect(result.current.state).toMatchObject({ kind: 'failed', runId: 'run-1' })
    api.startAgentRun.mockResolvedValue({ runId: 'run-2' })
    await act(async () => result.current.start())
    expect(api.startAgentRun).toHaveBeenCalledWith('workspace', 'workspace:orchestrator')
    expect(result.current.state).toMatchObject({ kind: 'starting', runId: 'run-2' })
    rerender({ terminalRuns: [orch('waiting_for_user', 'run-2')] })
    expect(result.current.state).toMatchObject({ kind: 'starting', phase: 'waiting_for_user', runId: 'run-2' })
  })

  test('creation returns the saved member even if launching failed, without an optimistic live run', async () => {
    api.createWorker.mockResolvedValue({ worker, agentStart: { ok: false, error: 'CLI could not start', runId: 'failed' } })
    const started = vi.fn()
    const setWorkers = vi.fn()
    const { result } = renderHook(() => useWorkerActions({ activeWorkspaceId: 'workspace', setWorkersByWorkspaceId: setWorkers, onWorkerRunStarted: started }))
    let created
    await act(async () => { created = await result.current.createWorker({ commandPresetId: 'hermes', name: worker.name, role: worker.role, roleDescription: 'Coder', launch: { type: 'preset', preset_id: 'hermes' } }) })
    expect(created).toEqual({ worker, error: 'CLI could not start', runId: 'failed' })
    expect(setWorkers.mock.calls[0]?.[0]({})).toEqual({ workspace: [worker] })
    expect(started).not.toHaveBeenCalled()
  })

  test('composer hands partial success to the member terminal instead of keeping a stale dialog error', async () => {
    const outcome = { worker, runId: 'failed', error: 'CLI could not start' }
    const onSaved = vi.fn()
    const { result } = renderHook(() => useWorkerComposer({ createWorker: vi.fn(async () => outcome), open: false, scopeKey: 'workspace', workers: [] }), { wrapper })
    await act(async () => result.current.submit({ preventDefault: vi.fn() } as unknown as FormEvent<HTMLFormElement>, onSaved))
    expect(onSaved).toHaveBeenCalledWith(outcome)
    expect(result.current.createWorkerError).toBeNull()
  })
})
