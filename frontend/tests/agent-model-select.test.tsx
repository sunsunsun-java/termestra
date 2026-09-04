// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import {
  ApiRequestError,
  createWorker,
  createWorkspace,
  getWorkerLaunchOptions,
  getWorkerModels,
  type CommandPreset,
} from '../web/src/api.js'
import { I18nProvider } from '../web/src/i18n.js'
import { AgentModelSelect } from '../web/src/launch/AgentModelSelect.js'

const preset: CommandPreset = {
  args: [],
  available: true,
  command: 'codex',
  displayName: 'Codex',
  id: 'codex',
  modelPicker: { allowCustom: true, suggestedModels: ['model-a'], supported: true },
  revision: 3,
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('agent model selection', () => {
  test('loads the workspace-scoped worker options without retaining removed default metadata', async () => {
    const request = vi.fn(async () =>
      new Response(
        JSON.stringify({
          orchestrator: {
            preset_id: 'codex',
            model_id: 'model-parent',
            revision: 7,
            inheritable: true,
          },
          presets: [
            {
              args: [],
              available: true,
              command: 'codex',
              display_name: 'Codex',
              id: 'codex',
              model_picker: {
                allow_custom: true,
                suggested_models: ['model-a'],
                supported: true,
              },
              revision: 3,
            },
          ],
        }),
        { headers: { 'content-type': 'application/json' }, status: 200 }
      )
    )
    vi.stubGlobal('fetch', request)

    const options = await getWorkerLaunchOptions('workspace / one')

    expect(request.mock.calls[0]?.[0]).toBe(
      '/api/ui/workspaces/workspace%20%2F%20one/agent-launch-options'
    )
    expect(options).toEqual({
      orchestrator: {
        presetId: 'codex',
        modelId: 'model-parent',
        revision: 7,
        inheritable: true,
      },
      presets: [preset],
    })
  })

  test('offers inheritance and initializes explicit selection from preset suggestions', () => {
    const onChange = vi.fn()
    render(
      <I18nProvider>
        <AgentModelSelect
          inheritLabel="Inherit Orchestrator · model-parent"
          mode="inherit"
          modelId=""
          onChange={onChange}
          preset={preset}
        />
      </I18nProvider>
    )

    const mode = screen.getByRole('combobox')
    expect(screen.getByRole('option', { name: 'Inherit Orchestrator · model-parent' })).toBeTruthy()
    fireEvent.change(mode, { target: { value: 'explicit' } })
    expect(onChange).toHaveBeenCalledWith('explicit', 'model-a')
  })

  test('loads a bounded model list for the selected workspace CLI', async () => {
    const request = vi.fn(async () => new Response(JSON.stringify({ models: ['gpt-a', 'gpt-b'] }), {
      headers: { 'content-type': 'application/json' }, status: 200,
    }))
    vi.stubGlobal('fetch', request)

    await expect(getWorkerModels('workspace / one', 'custom/codex')).resolves.toEqual(['gpt-a', 'gpt-b'])
    expect(request.mock.calls[0]?.[0]).toBe(
      '/api/ui/workspaces/workspace%20%2F%20one/agent-launch-options/custom%2Fcodex/models'
    )
  })

  test('aborts model discovery when the caller cancels it', async () => {
    const request = vi.fn((_input: RequestInfo | URL, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(init.signal?.reason), { once: true })
      })
    )
    vi.stubGlobal('fetch', request)
    const controller = new AbortController()

    const models = getWorkerModels('workspace', 'codex', controller.signal)
    await vi.waitFor(() => expect(request).toHaveBeenCalledOnce())
    controller.abort()

    await expect(models).rejects.toMatchObject({ name: 'AbortError' })
  })

  test('hides structured model selection for unsupported CLIs', () => {
    render(
      <I18nProvider>
        <AgentModelSelect
          mode="default"
          modelId=""
          onChange={vi.fn()}
          preset={{ ...preset, modelPicker: { allowCustom: false, suggestedModels: [], supported: false } }}
        />
      </I18nProvider>
    )

    expect(screen.queryByRole('option', { name: 'Choose a model' })).toBeNull()
    expect(screen.getByText('This CLI uses its default model.')).toBeTruthy()
  })

  test('retains stable revision conflict codes at both create boundaries', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        error: 'preset changed', error_code: 'COMMAND_PRESET_CHANGED',
      }), { headers: { 'content-type': 'application/json' }, status: 409 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        error: 'orchestrator changed', error_code: 'ORCHESTRATOR_LAUNCH_CHANGED',
      }), { headers: { 'content-type': 'application/json' }, status: 409 }))
    vi.stubGlobal('fetch', request)

    const workspaceFailure = await createWorkspace({ name: 'one', path: '/tmp/one' })
      .catch((error: unknown) => error)
    const workerFailure = await createWorker('workspace', {
      name: 'worker', role: 'coder',
      launch: { type: 'inherit_orchestrator', expected_source_revision: 1 },
    }).catch((error: unknown) => error)

    expect(workspaceFailure).toBeInstanceOf(ApiRequestError)
    expect(workspaceFailure).toMatchObject({ code: 'COMMAND_PRESET_CHANGED', status: 409 })
    expect(workerFailure).toBeInstanceOf(ApiRequestError)
    expect(workerFailure).toMatchObject({ code: 'ORCHESTRATOR_LAUNCH_CHANGED', status: 409 })
  })
})
