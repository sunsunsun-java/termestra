// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { I18nProvider } from '../web/src/i18n.js'
import { WorkspaceAvatar } from '../web/src/sidebar/WorkspaceAvatar.js'
import { AgentCliPicker, RolePicker } from '../web/src/worker/AddWorkerDialogFields.js'
import { CliAgentAvatar, CliAgentLogo } from '../web/src/worker/CliAgentAvatar.js'
import { RenameWorkerDialog } from '../web/src/worker/RenameWorkerDialog.js'

afterEach(cleanup)

describe('CLI agent identity', () => {
  test('uses the selected CLI identity glyph for picker marks', () => {
    const { container } = render(<CliAgentLogo commandPresetId="codex" size={24} />)

    const logo = screen.getByRole('img', { name: 'Codex' })
    expect(logo.tagName).toBe('svg')
    expect(logo.getAttribute('src')).toBeNull()
    expect(container.querySelector('[data-command-preset="codex"]')).toBe(logo)
  })

  test('uses the same selected CLI glyph in the member picker and the created member avatar', () => {
    const presets = [
      {
        args: [],
        available: true,
        command: 'claude',
        displayName: 'Claude Code (CC)',
        id: 'claude',
      },
    ]
    const { rerender } = render(
      <I18nProvider>
        <AgentCliPicker
          commandPresetId="claude"
          commandPresets={presets}
          onPresetChange={() => {}}
        />
      </I18nProvider>
    )

    const pickerLogo = screen.getByRole('img', { name: 'Claude Code' })
    expect(pickerLogo.tagName).toBe('svg')
    expect(pickerLogo.getAttribute('src')).toBeNull()

    rerender(<CliAgentAvatar commandPresetId="claude" workerRole="coder" size={40} />)
    const memberAvatar = screen.getByRole('img', { name: 'Claude Code' })
    expect(memberAvatar.querySelector('svg')).not.toBeNull()
    expect(memberAvatar.querySelector('img')).toBeNull()
  })
})

describe('role selection', () => {
  test('uses role glyphs and reports the selected role through the pressed state', () => {
    const onRoleChange = vi.fn()
    render(
      <I18nProvider>
        <RolePicker workerRole="reviewer" onRoleChange={onRoleChange} />
      </I18nProvider>
    )

    const reviewer = screen.getByRole('button', { name: 'Reviewer' })
    expect(reviewer.getAttribute('aria-pressed')).toBe('true')
    expect(within(reviewer).queryByText('Re')).toBeNull()
    expect(reviewer.querySelector('svg')).not.toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Tester' }))
    expect(onRoleChange).toHaveBeenCalledWith('tester')
  })
})

describe('renaming a team member', () => {
  test('submits a trimmed name through a named form', () => {
    const worker = {
      id: 'reviewer-1',
      name: 'Ada',
      role: 'reviewer' as const,
      status: 'idle' as const,
      pendingTaskCount: 0,
    }
    const onSubmit = vi.fn()
    render(
      <I18nProvider>
        <RenameWorkerDialog worker={worker} onClose={() => {}} onSubmit={onSubmit} />
      </I18nProvider>
    )

    const form = screen.getByRole('form', { name: 'Rename team member' })
    fireEvent.change(within(form).getByRole('textbox', { name: 'Name' }), {
      target: { value: '  Grace  ' },
    })
    fireEvent.submit(form)

    expect(onSubmit).toHaveBeenCalledWith(worker, 'Grace')
  })
})

describe('workspace identity', () => {
  test('announces the workspace name and caps a busy-count badge', () => {
    render(
      <WorkspaceAvatar
        workspaceId="workspace-1"
        name="Payments"
        isActive
        workingCount={12}
      />
    )

    expect(screen.getByRole('img', { name: 'Payments' })).not.toBeNull()
    expect(screen.getByTestId('workspace-avatar-working-count').textContent).toBe('9+')
  })
})
