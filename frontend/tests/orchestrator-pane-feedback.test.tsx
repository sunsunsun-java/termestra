// @vitest-environment jsdom

import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { I18nProvider } from '../web/src/i18n.js'
import { UI_LANGUAGE_STORAGE_KEY } from '../web/src/uiLanguage.js'
import { OrchestratorPane } from '../web/src/worker/OrchestratorPane.js'

const renderPane = (
  state: Parameters<typeof OrchestratorPane>[0]['state'],
  callbacks = {
    onRemoveWorkspace: vi.fn(),
    onRestart: vi.fn(),
    onStart: vi.fn(),
  }
) => {
  window.localStorage.setItem(UI_LANGUAGE_STORAGE_KEY, 'en')
  return {
    callbacks,
    ...render(
      <I18nProvider>
        <OrchestratorPane state={state} {...callbacks} />
      </I18nProvider>
    ),
  }
}

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  window.localStorage.clear()
})

describe('OrchestratorPane startup feedback', () => {
  test('announces the startup wait and updates its wording without inventing a completed phase', () => {
    vi.useFakeTimers()
    renderPane({ kind: 'starting' })

    const status = screen.getByRole('status', { name: 'Starting Orchestrator' })
    expect(status.getAttribute('aria-live')).toBe('polite')
    expect(within(status).getByText('Requesting a local CLI session…')).not.toBeNull()
    expect(
      within(status).getByText('Some CLIs need a little time before they accept input.')
    ).not.toBeNull()

    act(() => vi.advanceTimersByTime(4_000))

    expect(
      within(status).getByText('Waiting for the CLI to become ready for input…')
    ).not.toBeNull()
    expect(within(status).queryByText(/started|ready$/i)).toBeNull()
  })
})

describe('OrchestratorPane startup failure', () => {
  test('keeps long diagnostics readable and exposes distinct recovery actions', () => {
    const error = `CLI startup failed:\n${'connection negotiation did not complete '.repeat(24)}`
    const { callbacks } = renderPane({ kind: 'failed', error })

    const alert = screen.getByRole('alert', { name: 'Orchestrator failed to start' })
    const details = within(alert).getByRole('region', { name: 'Error details' })
    expect(details.textContent).toContain(error)
    expect(within(details).getByRole('button', { name: 'Copy error message' })).not.toBeNull()

    fireEvent.click(within(alert).getByRole('button', { name: 'Retry' }))
    fireEvent.click(within(alert).getByRole('button', { name: 'Remove workspace' }))
    expect(callbacks.onRestart).toHaveBeenCalledOnce()
    expect(callbacks.onRemoveWorkspace).toHaveBeenCalledOnce()
  })
})
