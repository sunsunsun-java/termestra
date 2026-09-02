// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import type { CommandPreset } from '../web/src/api.js'
import { I18nProvider } from '../web/src/i18n.js'
import { AddWorkspaceDialog } from '../web/src/workspace/AddWorkspaceDialog.js'
import { WorkspaceCommandPresetSelect } from '../web/src/workspace/WorkspaceCommandPresetSelect.js'

const preset = (id: string, displayName: string, available: boolean): CommandPreset => ({
  args: [],
  available,
  command: id,
  displayName,
  id,
  modelPicker: { allowCustom: false, suggestedModels: [], supported: false },
  revision: 1,
})

const payload = ({ displayName, modelPicker, ...value }: CommandPreset) => ({
  ...value,
  display_name: displayName,
  model_picker: {
    allow_custom: modelPicker.allowCustom,
    suggested_models: modelPicker.suggestedModels,
    supported: modelPicker.supported,
  },
})

const jsonResponse = (body: unknown, status = 200): Response =>
  ({
    json: async () => body,
    ok: status >= 200 && status < 300,
    status,
  }) as Response

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Workspace Orchestrator CLI selection', () => {
  test('lists installed CLIs first and prioritizes Codex within its availability group', () => {
    const presets = [
      preset('qwen', 'Qwen Code', false),
      preset('claude', 'Claude Code (CC)', true),
      preset('opencode', 'OpenCode', false),
      preset('codex', 'Codex', true),
      preset('gemini', 'Gemini', true),
    ]

    render(
      <I18nProvider>
        <WorkspaceCommandPresetSelect
          error={null}
          onChange={() => {}}
          presets={presets}
          value="codex"
        />
      </I18nProvider>
    )

    fireEvent.click(screen.getByTestId('workspace-command-preset'))

    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Codex',
      'Claude Code (CC)',
      'Gemini',
      'Qwen Code (not found)',
      'OpenCode (not found)',
      'Generic command',
    ])
  })

  test('selects Codex by default when creating a Workspace', async () => {
    const presets = [
      preset('claude', 'Claude Code (CC)', true),
      preset('codex', 'Codex', true),
      preset('opencode', 'OpenCode', false),
    ]
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const method = init?.method ?? 'GET'
        const url = new URL(typeof input === 'string' ? input : input.toString(), 'http://localhost')
        if (method === 'GET' && url.pathname === '/api/ui/settings/command-presets') {
          return jsonResponse(presets.map(payload))
        }
        if (method === 'POST' && url.pathname === '/api/fs/pick-folder') {
          return jsonResponse({
            canceled: false,
            error: null,
            path: '/workspace/alpha',
            probe: {
              current_branch: 'main',
              exists: true,
              is_dir: true,
              is_git_repository: true,
              ok: true,
              path: '/workspace/alpha',
              suggested_name: 'alpha',
            },
            supported: true,
          })
        }
        throw new Error(`Unexpected request: ${method} ${url.pathname}`)
      })
    )

    render(<AddWorkspaceDialog onClose={() => {}} onCreate={async () => {}} trigger={1} />)

    const select = await screen.findByTestId('workspace-command-preset')
    expect(select.getAttribute('data-value')).toBe('codex')
    expect(select.textContent).toBe('Codex')
  })
})
