// @vitest-environment jsdom

import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import type { FsProbeResponse, PickFolderResponse } from '../web/src/api.js'
import { useWorkspaceCreate } from '../web/src/useWorkspaceCreate.js'
import { AddWorkspaceDialog } from '../web/src/workspace/AddWorkspaceDialog.js'

const PICKED_PATH = '/workspace/alpha'

const probe: FsProbeResponse = {
  current_branch: 'main',
  exists: true,
  is_dir: true,
  is_git_repository: true,
  ok: true,
  path: PICKED_PATH,
  suggested_name: 'alpha',
}

const pickResult: PickFolderResponse = {
  canceled: false,
  error: null,
  path: PICKED_PATH,
  probe,
  supported: true,
}

const commandPresets = [
  {
    args: [],
    available: true,
    command: 'codex',
    display_name: 'Codex',
    id: 'codex',
  },
]

const jsonResponse = (body: unknown, status = 200): Response =>
  ({
    json: async () => body,
    ok: status >= 200 && status < 300,
    status,
  }) as Response

const stubPickerRequests = () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      const url = new URL(typeof input === 'string' ? input : input.toString(), 'http://localhost')
      if (method === 'GET' && url.pathname === '/api/ui/settings/command-presets') {
        return jsonResponse(commandPresets)
      }
      if (method === 'POST' && url.pathname === '/api/fs/pick-folder') {
        return jsonResponse(pickResult)
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`)
    })
  )
}

const deferred = <T,>() => {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, reject, resolve }
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Workspace creation feedback', () => {
  test('locks the dialog immediately and collapses rapid double-clicks into one create request', async () => {
    stubPickerRequests()
    const request = deferred<void>()
    const onCreate = vi.fn(() => request.promise)

    render(<AddWorkspaceDialog onClose={() => {}} onCreate={onCreate} trigger={1} />)

    const dialog = await screen.findByTestId('confirm-workspace-dialog')
    const createButton = screen.getByTestId('confirm-workspace-create') as HTMLButtonElement

    fireEvent.click(createButton)
    fireEvent.click(createButton)

    await waitFor(() => expect(onCreate).toHaveBeenCalledTimes(1))
    expect(createButton.disabled).toBe(true)
    expect(createButton.textContent).toContain('Creating')
    expect(dialog.getAttribute('aria-busy')).toBe('true')
    expect((screen.getByRole('button', { name: 'Cancel' }) as HTMLButtonElement).disabled).toBe(true)

    await act(async () => request.resolve())
    await waitFor(() => expect(screen.queryByTestId('confirm-workspace-dialog')).toBeNull())
  })

  test('shows the failure, releases the lock, and permits an explicit retry', async () => {
    stubPickerRequests()
    const onCreate = vi
      .fn<() => Promise<void>>()
      .mockRejectedValueOnce(new Error('Orchestrator startup failed'))
      .mockResolvedValueOnce()

    render(<AddWorkspaceDialog onClose={() => {}} onCreate={onCreate} trigger={1} />)

    const createButton = (await screen.findByTestId(
      'confirm-workspace-create'
    )) as HTMLButtonElement
    fireEvent.click(createButton)

    const error = await screen.findByTestId('workspace-create-error')
    expect(error.textContent).toContain('Orchestrator startup failed')
    expect(createButton.disabled).toBe(false)
    expect(createButton.textContent).toContain('Create Workspace')

    fireEvent.click(createButton)
    await waitFor(() => expect(onCreate).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByTestId('confirm-workspace-dialog')).toBeNull())
  })

  test('reports a canonical-path replay as existing without clearing its orchestrator state', async () => {
    const existingWorkspace = {
      id: 'workspace-existing',
      name: 'Existing Alpha',
      path: PICKED_PATH,
    }
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse(
          {
            ...existingWorkspace,
            orchestrator_start: { error: null, ok: false, run_id: null },
          },
          200
        )
      )
    )
    const onWorkspaceCreated = vi.fn()
    const onWorkspaceExisting = vi.fn()

    const { result } = renderHook(() =>
      useWorkspaceCreate({ onWorkspaceCreated, onWorkspaceExisting })
    )
    act(() => {
      result.current.recordOrchestratorResult(existingWorkspace.id, {
        error: 'Previous startup failed',
        ok: false,
        run_id: null,
      })
    })

    let response: Awaited<ReturnType<typeof result.current.createNewWorkspace>> | undefined
    await act(async () => {
      response = await result.current.createNewWorkspace({
        commandPresetId: 'codex',
        name: 'Requested rename',
        path: PICKED_PATH,
      })
    })

    expect(response?.created).toBe(false)
    expect(onWorkspaceCreated).toHaveBeenCalledWith(existingWorkspace)
    expect(onWorkspaceExisting).toHaveBeenCalledWith(existingWorkspace)
    expect(result.current.orchestratorAutostartErrors[existingWorkspace.id]).toBe(
      'Previous startup failed'
    )
  })
})
