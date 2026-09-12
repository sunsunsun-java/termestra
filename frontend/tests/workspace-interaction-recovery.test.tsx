import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
const mocks = vi.hoisted(() => ({ browseFs: vi.fn(), probeFs: vi.fn(), applyTeamScenario: vi.fn(), listWorkers: vi.fn(), show: vi.fn() }))
vi.mock('../web/src/api.js', () => ({ ...mocks, listDispatchDeliveryIssues: async () => [], listReportDeliveryIssues: async () => [] }))
vi.mock('../web/src/ui/useToast.js', () => ({ useToast: () => ({ show: mocks.show }) }))
import { useFsBrowser } from '../web/src/workspace/useFsBrowser.js'
import { WorkersPane } from '../web/src/worker/WorkersPane.js'
import { useWorkspaceWorkers } from '../web/src/useWorkspaceWorkers.js'
afterEach(() => { cleanup(); vi.clearAllMocks(); vi.restoreAllMocks() })

test('same directory second selection preserves its valid probe', async () => {
  mocks.browseFs.mockResolvedValue({ ok: true, current_path: '/root', root_path: '/root', entries: [], parent_path: null, error: null, truncated: false })
  mocks.probeFs.mockImplementation(async (path) => ({ path, ok: true, is_dir: true, suggested_name: 'child' }))
  const { result } = renderHook(() => useFsBrowser(true))
  await waitFor(() => expect(result.current.probe?.path).toBe('/root'))
  act(() => result.current.selectEntry('/root/child'))
  await waitFor(() => expect(result.current.probe?.path).toBe('/root/child'))
  act(() => result.current.selectEntry('/root/child'))
  await act(async () => { await Promise.resolve() })
  expect(result.current.probe?.path).toBe('/root/child')
})

test('scenario failure remains visible after poll discovers first member', async () => {
  let reject!: (reason: Error) => void
  mocks.applyTeamScenario.mockReturnValue(new Promise((_resolve, failure) => { reject = failure }))
  const props = { workspaceId: 'ws', terminalRuns: [], startingWorkerId: null, onAddWorkerClick: () => {}, onDeleteWorker: () => {}, onOpenShellTerminal: () => {}, onOpenWorker: () => {}, onStartWorker: () => {}, onRenameWorker: async () => ({ error: null }) }
  const view = render(<WorkersPane {...props} workers={[]} />)
  fireEvent.click(screen.getByTestId('scenario-card-build_review_test'))
  fireEvent.click(screen.getByTestId('scenario-goal-apply'))
  expect(mocks.applyTeamScenario).toHaveBeenCalledOnce()
  view.rerender(<WorkersPane {...props} workers={[{ id: 'worker', name: 'Alice', role: 'coder', status: 'stopped', pendingTaskCount: 0 }]} />)
  await act(async () => reject(new Error('Failed to hand the scenario goal to Orchestrator')))
  expect(screen.getByRole('alert').textContent).toBe('Failed to hand the scenario goal to Orchestrator')
  expect(screen.getByTestId('scenario-goal-dialog')).not.toBeNull()
})


test('reselecting a directory while its probe is in flight does not cancel it', async () => {
  let complete!: (value: { path: string; ok: boolean; is_dir: boolean }) => void
  let signal!: AbortSignal
  mocks.browseFs.mockResolvedValue({ ok: true, current_path: '/root', root_path: '/root', entries: [], parent_path: null, error: null, truncated: false })
  mocks.probeFs.mockImplementation((path, requestSignal) => {
    if (path === '/root') return Promise.resolve({ path, ok: true, is_dir: true })
    signal = requestSignal
    return new Promise((resolve) => { complete = resolve })
  })
  const { result } = renderHook(() => useFsBrowser(true))
  await waitFor(() => expect(result.current.probe?.path).toBe('/root'))
  act(() => result.current.selectEntry('/root/child'))
  act(() => result.current.selectEntry('/root/child'))
  expect(signal.aborted).toBe(false)
  await act(async () => complete({ path: '/root/child', ok: true, is_dir: true }))
  expect(result.current.probe?.path).toBe('/root/child')
  expect(mocks.probeFs).toHaveBeenCalledTimes(2)
})

test('only successful worker snapshots mark the workspace loaded, including an empty team', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  mocks.listWorkers.mockImplementation(async (id) => {
    if (id === 'failed') throw new Error('offline')
    return []
  })
  const view = renderHook(() => useWorkspaceWorkers(['empty', 'failed'], 'empty'))
  expect(view.result.current[2].size).toBe(0)
  await waitFor(() => expect(view.result.current[2].has('empty')).toBe(true))
  expect(view.result.current[2].has('failed')).toBe(false)
  expect(view.result.current[0].empty).toEqual([])
})

test('scenario success after roster refresh closes the dialog and reports completion', async () => {
  let complete!: (value: unknown) => void
  mocks.applyTeamScenario.mockReturnValue(new Promise((resolve) => { complete = resolve }))
  const props = { workspaceId: 'ws', terminalRuns: [], startingWorkerId: null, onAddWorkerClick: () => {}, onDeleteWorker: () => {}, onOpenShellTerminal: () => {}, onOpenWorker: () => {}, onStartWorker: () => {}, onRenameWorker: async () => ({ error: null }) }
  const view = render(<WorkersPane {...props} workers={[]} />)
  fireEvent.click(screen.getByTestId('scenario-card-build_review_test'))
  fireEvent.click(screen.getByTestId('scenario-goal-apply'))
  view.rerender(<WorkersPane {...props} workers={[{ id: 'worker', name: 'Alice', role: 'coder', status: 'idle', pendingTaskCount: 0 }]} />)
  expect(screen.getByTestId('scenario-goal-dialog')).not.toBeNull()
  expect(screen.queryByTestId('scenario-team-cards')).toBeNull()
  await act(async () => complete({ createdWorkers: [], injected: true }))
  expect(mocks.show).toHaveBeenCalledWith(expect.objectContaining({ kind: 'success' }))
  expect(screen.queryByTestId('scenario-goal-dialog')).toBeNull()
})
