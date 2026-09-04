// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import type { FormEvent, ReactNode } from 'react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { I18nProvider } from '../web/src/i18n.js'
import { useWorkerComposer } from '../web/src/worker/useWorkerComposer.js'

const launchOptions = {
  orchestrator: {
    preset_id: 'codex',
    model_id: 'orchestrator-model',
    revision: 7,
    inheritable: true,
  },
  presets: [{
    args: [],
    available: true,
    command: 'codex',
    display_name: 'Codex',
    id: 'codex',
    model_picker: { allow_custom: true, suggested_models: [], supported: true },
    revision: 3,
  }],
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('worker composer model lifecycle', () => {
  test('reopening after cancellation cannot submit a stale explicit model', async () => {
    let launchOptionRequests = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = input.toString()
      if (path.endsWith('/models')) {
        return new Response(JSON.stringify({ models: ['gpt-a'] }), { status: 200 })
      }
      if (path.endsWith('/agent-launch-options')) {
        launchOptionRequests += 1
        if (launchOptionRequests > 1) throw new Error('launch options unavailable')
        return new Response(JSON.stringify(launchOptions), { status: 200 })
      }
      if (path === '/api/ui/settings/role-templates') {
        return new Response(JSON.stringify([]), { status: 200 })
      }
      throw new Error(`Unexpected request: ${path}`)
    }))
    const createWorker = vi.fn(async () => ({ error: null, runId: null }))
    const wrapper = ({ children }: { children: ReactNode }) => (
      <I18nProvider>{children}</I18nProvider>
    )
    const { result, rerender } = renderHook(
      ({ open }) => useWorkerComposer({ createWorker, open, scopeKey: 'workspace', workers: [] }),
      { initialProps: { open: true }, wrapper }
    )
    await waitFor(() => expect(result.current.availableModels).toEqual(['gpt-a']))

    act(() => result.current.setModelSelection('explicit', 'gpt-a'))
    rerender({ open: false })
    await waitFor(() => expect(result.current.modelMode).toBe('default'))
    rerender({ open: true })
    await waitFor(() => expect(launchOptionRequests).toBe(2))

    act(() => result.current.submit(
      { preventDefault: vi.fn() } as unknown as FormEvent<HTMLFormElement>,
      vi.fn()
    ))
    await waitFor(() => expect(createWorker).toHaveBeenCalledOnce())
    expect(createWorker.mock.calls[0]?.[0].launch).toEqual({
      type: 'preset',
      preset_id: 'codex',
      expected_preset_revision: 3,
    })
  })
})
