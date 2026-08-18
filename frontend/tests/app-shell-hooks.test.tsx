// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { useShortcutAction } from '../web/src/pwa/use-shortcut-action.js'
import { useAppShortcuts } from '../web/src/useAppShortcuts.js'
import { useFirstRunWizard } from '../web/src/wizard/useFirstRunWizard.js'

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  window.history.replaceState({}, '', '/')
})

describe('app shell hooks', () => {
  test('a manifest shortcut consumes only its action parameter', () => {
    const addWorkspace = vi.fn()
    const tryDemo = vi.fn()
    window.history.replaceState({}, '', '/?theme=dark&action=try-demo#workers')

    renderHook(() =>
      useShortcutAction({
        onAddWorkspace: addWorkspace,
        onTryDemo: tryDemo,
        ready: true,
      })
    )

    expect(tryDemo).toHaveBeenCalledOnce()
    expect(addWorkspace).not.toHaveBeenCalled()
    expect(`${window.location.pathname}${window.location.search}${window.location.hash}`).toBe(
      '/?theme=dark#workers'
    )
  })

  test('temporarily closing first-run guidance survives an empty workspace refresh', async () => {
    const { result, rerender } = renderHook(
      ({ workspaces }) => useFirstRunWizard(workspaces),
      { initialProps: { workspaces: [] } }
    )
    await waitFor(() => expect(result.current.wizardOpen).toBe(true))

    act(() => result.current.closeWizard(false))
    rerender({ workspaces: [] })

    expect(result.current.wizardOpen).toBe(false)
  })

  test('the new-workspace shortcut waits for workspace bootstrap', () => {
    const openDialog = vi.fn()
    const { rerender } = renderHook(
      ({ workspaces }) =>
        useAppShortcuts({
          bootstrapError: null,
          onSelectWorkspace: vi.fn(),
          onTriggerAddDialog: openDialog,
          workspaces,
        }),
      { initialProps: { workspaces: null as [] | null } }
    )

    window.dispatchEvent(
      new KeyboardEvent('keydown', { ctrlKey: true, key: 'n', shiftKey: true })
    )
    expect(openDialog).not.toHaveBeenCalled()

    rerender({ workspaces: [] })
    window.dispatchEvent(
      new KeyboardEvent('keydown', { ctrlKey: true, key: 'n', shiftKey: true })
    )
    expect(openDialog).toHaveBeenCalledOnce()
  })
})
