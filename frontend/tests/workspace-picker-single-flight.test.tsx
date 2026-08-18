// @vitest-environment jsdom

import { StrictMode, useState } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import type { PickFolderResponse } from '../web/src/api.js'
import { AddWorkspaceDialog } from '../web/src/workspace/AddWorkspaceDialog.js'

const jsonResponse = (body: unknown, status = 200): Response =>
  ({
    json: async () => body,
    ok: status >= 200 && status < 300,
    status,
  }) as Response

const deferred = <T,>() => {
  let resolve!: (value: T | PromiseLike<T>) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

const Harness = () => {
  const [trigger, setTrigger] = useState(0)
  return (
    <>
      <button type="button" onClick={() => setTrigger((current) => current + 1)}>
        Add workspace
      </button>
      {trigger > 0 ? (
        <AddWorkspaceDialog trigger={trigger} onClose={() => {}} onCreate={async () => {}} />
      ) : null}
    </>
  )
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Native workspace folder picker', () => {
  test('one user request opens one picker while React StrictMode replays effects', async () => {
    const firstPicker = deferred<Response>()
    let pickerRequestCount = 0
    let firstPickerSettled = false

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const method = init?.method ?? 'GET'
        const url = new URL(typeof input === 'string' ? input : input.toString(), 'http://localhost')
        if (method === 'GET' && url.pathname === '/api/ui/settings/command-presets') {
          return jsonResponse([
            {
              args: [],
              available: true,
              command: 'codex',
              display_name: 'Codex',
              id: 'codex',
            },
          ])
        }
        if (method === 'POST' && url.pathname === '/api/fs/pick-folder') {
          pickerRequestCount += 1
          if (pickerRequestCount === 1) return firstPicker.promise
          if (firstPickerSettled) {
            return jsonResponse({
              canceled: true,
              error: null,
              path: null,
              probe: null,
              supported: true,
            } satisfies PickFolderResponse)
          }
          const alreadyOpen: PickFolderResponse = {
            canceled: false,
            error: 'A folder picker is already open.',
            path: null,
            probe: null,
            supported: true,
          }
          return jsonResponse(alreadyOpen)
        }
        throw new Error(`Unexpected request: ${method} ${url.pathname}`)
      })
    )

    render(
      <StrictMode>
        <Harness />
      </StrictMode>
    )
    fireEvent.click(screen.getByRole('button', { name: 'Add workspace' }))

    await screen.findByTestId('add-workspace-picking')
    await waitFor(() => expect(pickerRequestCount).toBeGreaterThanOrEqual(1))
    await act(async () => Promise.resolve())
    expect.soft(pickerRequestCount).toBe(1)
    expect.soft(screen.queryByTestId('add-workspace-error')).toBeNull()
    expect.soft(screen.queryByText('A folder picker is already open.')).toBeNull()

    await act(async () => {
      firstPickerSettled = true
      firstPicker.resolve(
        jsonResponse({
          canceled: true,
          error: null,
          path: null,
          probe: null,
          supported: true,
        } satisfies PickFolderResponse)
      )
    })
    await waitFor(() => expect(screen.queryByTestId('add-workspace-picking')).toBeNull())

    fireEvent.click(screen.getByRole('button', { name: 'Add workspace' }))

    await waitFor(() => expect(pickerRequestCount).toBe(2))
    expect(screen.queryByTestId('add-workspace-error')).toBeNull()
  })
})
