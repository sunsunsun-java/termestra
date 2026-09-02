// @vitest-environment jsdom

import { cleanup, fireEvent, render, renderHook, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { parseTaskMetadata } from '../web/src/tasks/task-meta.js'
import { resolveTerminalShortcut, SHORTCUT_BYTES } from '../web/src/terminal/shortcuts.js'
import { Confirm } from '../web/src/ui/Confirm.js'
import { Toaster } from '../web/src/ui/toast.js'
import { ToastProvider, useToast } from '../web/src/ui/useToast.js'
import {
  __resetBeforeUnloadGuardForTests,
  allowNextUnloadSilently,
  useBeforeUnloadGuard,
} from '../web/src/useBeforeUnloadGuard.js'
import { buildWorkspaceCreateInput } from '../web/src/workspace/workspace-create-input.js'
import { FsSelectionPreview } from '../web/src/workspace/FsSelectionPreview.js'

afterEach(() => {
  cleanup()
  __resetBeforeUnloadGuardForTests()
})

describe('task title metadata', () => {
  test('keeps punctuation inside quoted metadata values and nested file names', () => {
    expect(
      parseTaskMetadata(
        'Publish notes (owner: Ada, note: "retry, then report", report: docs/report (final).md)'
      )
    ).toEqual({
      title: 'Publish notes',
      meta: [
        { kind: 'owner', value: 'Ada' },
        { kind: 'note', value: 'note: retry, then report' },
        { kind: 'path', label: 'report', value: 'docs/report (final).md' },
      ],
    })
  })

  test('recognizes a backslash-delimited file value even when the metadata key is custom', () => {
    expect(parseTaskMetadata('Inspect output (location: C:\\work\\logs\\result.txt)')).toEqual({
      title: 'Inspect output',
      meta: [{ kind: 'path', label: 'location', value: 'C:\\work\\logs\\result.txt' }],
    })
  })

  test('treats an apostrophe in an owner name as text rather than an opening quote', () => {
    expect(parseTaskMetadata("Review changes (owner: O'Neil, status: working)")).toEqual({
      title: 'Review changes',
      meta: [
        { kind: 'owner', value: "O'Neil" },
        { kind: 'status', value: 'working', tone: 'orange' },
      ],
    })
  })
})

describe('terminal keyboard shortcut resolution', () => {
  test('does not intercept a command chord while an IME composition is active', () => {
    const event = new KeyboardEvent('keydown', {
      isComposing: true,
      key: 'Backspace',
      metaKey: true,
    })

    expect(resolveTerminalShortcut(event, { isMac: true })).toEqual({ kind: 'passthrough' })
  })

  test('emits Shift+Enter exactly on keypress and leaves keyup alone', () => {
    const keypress = new KeyboardEvent('keypress', { key: 'Enter', shiftKey: true })
    const keyup = new KeyboardEvent('keyup', { key: 'Enter', shiftKey: true })

    expect(resolveTerminalShortcut(keypress, { isMac: false })).toEqual({
      kind: 'send',
      bytes: SHORTCUT_BYTES.shiftEnter,
    })
    expect(resolveTerminalShortcut(keyup, { isMac: false })).toEqual({ kind: 'passthrough' })
  })

  test('preserves macOS command navigation and editing chords', () => {
    const lineStart = new KeyboardEvent('keydown', { key: 'ArrowLeft', metaKey: true })
    const killToStart = new KeyboardEvent('keydown', { key: 'Backspace', metaKey: true })
    const wordForward = new KeyboardEvent('keydown', { key: 'ArrowRight', altKey: true })

    expect(resolveTerminalShortcut(lineStart, { isMac: true })).toEqual({
      kind: 'send',
      bytes: SHORTCUT_BYTES.lineStart,
    })
    expect(resolveTerminalShortcut(killToStart, { isMac: true })).toEqual({
      kind: 'send',
      bytes: SHORTCUT_BYTES.killToLineStart,
    })
    expect(resolveTerminalShortcut(wordForward, { isMac: true })).toEqual({
      kind: 'send',
      bytes: SHORTCUT_BYTES.wordForward,
    })
  })
})

describe('before-unload guard', () => {
  test('consumes one silent unload globally when more than one component enables the guard', () => {
    const first = renderHook(() => useBeforeUnloadGuard(true))
    const second = renderHook(() => useBeforeUnloadGuard(true))

    allowNextUnloadSilently()
    const silentAttempt = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(silentAttempt)
    expect(silentAttempt.defaultPrevented).toBe(false)

    const guardedAttempt = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(guardedAttempt)
    expect(guardedAttempt.defaultPrevented).toBe(true)

    first.unmount()
    second.unmount()
  })
})

describe('workspace creation input', () => {
  test('normalizes user-entered values at the creation boundary', () => {
    const registrationId = crypto.randomUUID()
    expect(
      buildWorkspaceCreateInput({
        commandPresetId: 'claude',
        commandPresetRevision: 4,
        modelId: '',
        modelMode: 'default',
        name: '  Alpha  ',
        path: '  /work/alpha  ',
        registrationId,
        startupCommand: '   ',
      })
    ).toEqual({
      launch: { type: 'preset', preset_id: 'claude', expected_preset_revision: 4 },
      name: 'Alpha',
      path: '/work/alpha',
      registrationId,
    })

    expect(
      buildWorkspaceCreateInput({
        commandPresetId: 'codex',
        commandPresetRevision: 2,
        modelId: '  model-a  ',
        modelMode: 'explicit',
        name: 'Alpha',
        path: '/work/alpha',
        registrationId,
        startupCommand: '',
      })
    ).toEqual({
      launch: {
        type: 'preset',
        preset_id: 'codex',
        model_id: 'model-a',
        expected_preset_revision: 2,
      },
      name: 'Alpha',
      path: '/work/alpha',
      registrationId,
    })

    expect(
      buildWorkspaceCreateInput({
        commandPresetId: 'codex',
        commandPresetRevision: 2,
        modelId: 'ignored-for-startup',
        modelMode: 'explicit',
        name: 'Alpha',
        path: '/work/alpha',
        registrationId,
        startupCommand: '  codex --resume  ',
      })
    ).toEqual({
      launch: {
        type: 'startup',
        startup_command: 'codex --resume',
        recovery_preset_id: 'codex',
      },
      name: 'Alpha',
      path: '/work/alpha',
      registrationId,
    })
  })
})

describe('confirmation dialog', () => {
  test('announces destructive confirmation as an alert dialog and closes after confirmation', () => {
    const onConfirm = vi.fn()
    const onOpenChange = vi.fn()
    render(
      <Confirm
        confirmKind="danger"
        confirmLabel="Remove"
        description="The local folder is preserved."
        onConfirm={onConfirm}
        onOpenChange={onOpenChange}
        open
        title="Remove workspace"
      />
    )

    expect(screen.getByRole('alertdialog', { name: 'Remove workspace' })).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
    expect(onConfirm).toHaveBeenCalledOnce()
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

const ToastHarness = () => {
  const toast = useToast()
  return (
    <>
      <button type="button" onClick={() => toast.show({ kind: 'warning', message: 'Check it' })}>
        Show warning
      </button>
      <button type="button" onClick={() => toast.show({ kind: 'error', message: 'It failed' })}>
        Show error
      </button>
      <Toaster />
    </>
  )
}

describe('toast announcements', () => {
  test('presents toast cards as list items in severity-appropriate live regions', () => {
    render(
      <ToastProvider>
        <ToastHarness />
      </ToastProvider>
    )
    fireEvent.click(screen.getByRole('button', { name: 'Show warning' }))
    fireEvent.click(screen.getByRole('button', { name: 'Show error' }))

    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(within(screen.getByRole('status')).getByText('Check it')).not.toBeNull()
    expect(within(screen.getByRole('alert')).getByText('It failed')).not.toBeNull()
  })
})

describe('filesystem selection preview', () => {
  test('does not present repository metadata for an invalid directory selection', () => {
    render(
      <FsSelectionPreview
        probe={{
          current_branch: 'main',
          exists: true,
          is_dir: false,
          is_git_repository: true,
          ok: false,
          path: '/work/readme.md',
          suggested_name: 'readme',
        }}
        suggestedName="readme"
        onSuggestedNameChange={() => {}}
      />
    )

    expect(screen.queryByTestId('fs-preview-git-badge')).toBeNull()
    expect((screen.getByRole('textbox', { name: /name/i }) as HTMLInputElement).disabled).toBe(true)
  })
})
