// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { AppProviders } from '../web/src/AppProviders.js'
import { TerminalBottomPanel } from '../web/src/terminal/TerminalBottomPanel.js'
import { TerminalTabs } from '../web/src/terminal/TerminalTabs.js'
import { WorkspaceTerminalPanels } from '../web/src/WorkspaceTerminalPanels.js'

afterEach(cleanup)

describe('terminal shell', () => {
  test('the close shortcut closes the tab that is visibly active', () => {
    const closeTab = vi.fn()
    render(
      <AppProviders>
        <TerminalBottomPanel
          tabs={[
            {
              id: 'worker:alpha',
              kind: 'worker',
              workerId: 'alpha',
              runId: 'run-alpha',
              label: 'Alpha',
            },
          ]}
          activeId={null}
          onSelect={vi.fn()}
          onClose={closeTab}
          onClosePanel={vi.fn()}
          onNewShell={vi.fn()}
          newShellPending={false}
          onStartWorker={vi.fn()}
          startingWorkerId={null}
        />
      </AppProviders>
    )

    fireEvent.keyDown(screen.getByTestId('terminal-bottom-panel'), {
      ctrlKey: true,
      key: 'w',
    })

    expect(closeTab).toHaveBeenCalledWith('worker:alpha')
  })

  test('arrow keys move between terminal tabs', () => {
    const selectTab = vi.fn()
    render(
      <AppProviders>
        <TerminalTabs
          tabs={[
            {
              id: 'worker:alpha',
              kind: 'worker',
              workerId: 'alpha',
              runId: 'run-alpha',
              label: 'Alpha',
            },
            { id: 'shell:one', kind: 'shell', runId: 'one', label: 'Shell' },
          ]}
          activeId="worker:alpha"
          onSelect={selectTab}
          onClose={vi.fn()}
          onClosePanel={vi.fn()}
          onNewShell={vi.fn()}
          newShellPending={false}
        />
      </AppProviders>
    )

    fireEvent.keyDown(screen.getByTestId('terminal-tab-select-worker:alpha'), {
      key: 'ArrowRight',
    })

    expect(selectTab).toHaveBeenCalledWith('shell:one')
  })

  test('a hidden workspace registry remains named for assistive technology', () => {
    const { container } = render(
      <AppProviders>
        <WorkspaceTerminalPanels hidden terminalRuns={[]} workspaceId="workspace-1" />
      </AppProviders>
    )

    const registry = container.querySelector('section')
    expect(registry?.hidden).toBe(true)
    expect(registry?.getAttribute('aria-hidden')).toBe('true')
    expect(registry?.getAttribute('aria-label')).toBe('Terminal panels')
  })
})
