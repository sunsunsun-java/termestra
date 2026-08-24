// @vitest-environment jsdom

import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

vi.mock('../web/src/terminal/TerminalView.js', () => ({
  TerminalView: ({
    bookmarksEnabled,
    runId,
  }: {
    bookmarksEnabled?: boolean
    runId: string
  }) => (
    <div
      data-bookmarks-enabled={bookmarksEnabled ? 'true' : 'false'}
      data-testid={`terminal-view-${runId}`}
    />
  ),
}))

import { WorkspaceTerminalPanels } from '../web/src/WorkspaceTerminalPanels.js'

afterEach(cleanup)

describe('workspace terminal bookmark scope', () => {
  test('enables input bookmarks only for the workspace Orchestrator', () => {
    render(
      <WorkspaceTerminalPanels
        terminalRuns={[
          {
            agent_id: 'workspace-1:orchestrator',
            agent_name: 'Orchestrator',
            run_id: 'run-orchestrator',
            status: 'running',
          },
          {
            agent_id: 'worker-1',
            agent_name: 'Coder',
            run_id: 'run-worker',
            status: 'running',
          },
          {
            agent_id: 'workspace-1:shell',
            agent_name: 'Shell',
            run_id: 'run-shell',
            status: 'running',
          },
        ]}
        workspaceId="workspace-1"
      />
    )

    expect(
      screen.getByTestId('terminal-view-run-orchestrator').getAttribute('data-bookmarks-enabled')
    ).toBe('true')
    expect(
      screen.getByTestId('terminal-view-run-worker').getAttribute('data-bookmarks-enabled')
    ).toBe('false')
    expect(
      screen.getByTestId('terminal-view-run-shell').getAttribute('data-bookmarks-enabled')
    ).toBe('false')
  })
})
