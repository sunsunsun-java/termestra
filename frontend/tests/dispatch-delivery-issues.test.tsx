// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor, render, screen, fireEvent, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

const { listDispatchDeliveryIssues, retryDispatchDelivery, listReportDeliveryIssues, retryReportDelivery } = vi.hoisted(() => ({
  listReportDeliveryIssues: vi.fn().mockResolvedValue([]),
  retryReportDelivery: vi.fn(),
  listDispatchDeliveryIssues: vi.fn(),
  retryDispatchDelivery: vi.fn(),
}))

vi.mock('../web/src/api.js', () => ({
  listReportDeliveryIssues,
  retryReportDelivery,
  listDispatchDeliveryIssues,
  retryDispatchDelivery,
}))

import { WorkersPane } from '../web/src/worker/WorkersPane.js'

import { useDispatchDeliveryIssues } from '../web/src/worker/useDispatchDeliveryIssues.js'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('dispatch delivery issue visibility', () => {
  test('shows failed reports and requires explicit confirmation before retrying uncertain notification', async () => {
    listDispatchDeliveryIssues.mockResolvedValue([])
    listReportDeliveryIssues.mockResolvedValue([{ id: 'report-1', toAgentId: 'worker-1', kind: 'report', deliveryState: 'uncertain', deliveryError: 'unknown outcome' }])
    retryReportDelivery.mockResolvedValue(undefined)
    const { result } = renderHook(() => useDispatchDeliveryIssues('workspace-1'))
    await waitFor(() => expect(result.current.issues).toHaveLength(1))
    await act(async () => result.current.retry('report-1'))
    expect(retryReportDelivery).not.toHaveBeenCalled()
    await act(async () => result.current.retry('report-1', true))
    expect(retryReportDelivery).toHaveBeenCalledWith('workspace-1', 'report-1', true)
    expect(result.current.issues).toEqual([])
    expect(retryDispatchDelivery).not.toHaveBeenCalled()
    listReportDeliveryIssues.mockResolvedValue([])
  })

  test('renders an explicit confirmation before replaying an uncertain report', async () => {
    listDispatchDeliveryIssues.mockResolvedValue([])
    listReportDeliveryIssues.mockResolvedValue([{ id: 'report-1', toAgentId: 'worker-1', kind: 'report', deliveryState: 'uncertain', deliveryError: 'unknown outcome' }])
    retryReportDelivery.mockResolvedValue(undefined)
    render(<WorkersPane workspaceId="workspace-1" workers={[{ id: 'worker-1', name: 'Alice', role: 'coder', status: 'idle', pendingTaskCount: 0 }]}
      terminalRuns={[]} startingWorkerId={null} onAddWorkerClick={() => {}} onDeleteWorker={() => {}}
      onOpenShellTerminal={() => {}} onOpenWorker={() => {}} onStartWorker={() => {}} onRenameWorker={async () => ({ error: null })} />)
    await screen.findByText(/Report notification/)
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(retryReportDelivery).not.toHaveBeenCalled()
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }))
    expect(retryReportDelivery).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(retryReportDelivery).toHaveBeenCalledWith('workspace-1', 'report-1', true))
    listReportDeliveryIssues.mockResolvedValue([])
  })

  test('shows only actionable deliveries and removes one after an explicit retry', async () => {
    listDispatchDeliveryIssues.mockResolvedValue([
      {
        id: 'uncertain-1',
        toAgentId: 'worker-1',
        text: 'task',
        state: 'queued',
        deliveryState: 'uncertain',
        deliveryAttemptCount: 1,
        deliveryError: 'input acknowledgement timed out',
        deliveryNextAttemptAt: null,
        deliveryInputAttempted: true,
      },
    ])
    retryDispatchDelivery.mockResolvedValue(undefined)

    const { result, unmount } = renderHook(() => useDispatchDeliveryIssues('workspace-1'))
    await waitFor(() => expect(result.current.issues.map((item) => item.id)).toEqual(['uncertain-1']))

    await act(async () => result.current.retry('uncertain-1'))

    expect(retryDispatchDelivery).toHaveBeenCalledWith('workspace-1', 'uncertain-1')
    expect(result.current.issues).toEqual([])
    unmount()
  })
})
