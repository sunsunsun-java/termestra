import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'

import { listTerminalRuns, type TerminalRunSummary } from '../web/src/api.js'
import { I18nProvider } from '../web/src/i18n.js'
import { useTerminalRuns } from '../web/src/terminal/useTerminalRuns.js'
import { OrchestratorPane } from '../web/src/worker/OrchestratorPane.js'
import { useOrchestratorPaneState } from '../web/src/worker/useOrchestratorPaneState.js'

const noop = () => {}
const run: TerminalRunSummary = {
  agent_id: 'workspace:orchestrator', agent_name: 'Orchestrator', run_id: 'run-1',
  status: 'starting', terminal_input_profile: 'default',
  startup_phase: 'waiting_for_user', startup_message: 'Complete the CLI confirmation to continue.',
}

const serve = (payload: unknown) => {
  const request = vi.fn(async () => new Response(JSON.stringify(payload), {
    headers: { 'content-type': 'application/json' }, status: 200,
  }))
  vi.stubGlobal('fetch', request)
  return request
}

const PolledOrchestrator = () => {
  const { runs } = useTerminalRuns('workspace')
  const { state } = useOrchestratorPaneState({
    workspaceId: 'workspace', terminalRuns: runs, autostartError: null,
    onClearAutostartError: noop,
  })
  return <OrchestratorPane state={state} onRemoveWorkspace={noop} onStart={noop} onRestart={noop} />
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

test('terminal summary preserves startup evidence while excluding detail fields', async () => {
  const request = serve([{ ...run, output: 'terminal history', prompt: 'startup prompt' }])
  expect(await listTerminalRuns('workspace')).toEqual([run])
  expect(request).toHaveBeenCalledWith('/api/ui/workspaces/workspace/runs', expect.anything())
})

test.each([
  { ...run, copy: 'Waiting for your confirmation in the terminal', role: 'status' },
  {
    ...run, status: 'exited', startup_phase: 'failed' as const,
    startup_message: 'Process exited before startup completed.',
    copy: 'Startup failed — terminal output is retained below', role: 'alert',
  },
])('polled $startup_phase evidence reaches the Orchestrator pane', async ({ copy, role, ...summary }) => {
  serve([summary])
  render(<I18nProvider><PolledOrchestrator /></I18nProvider>)
  await waitFor(() => expect(screen.getByRole(role).textContent).toContain(copy))
  expect(screen.getByRole(role).textContent).toContain(summary.startup_message)
  expect(document.getElementById('orch-pty-run-1')).not.toBeNull()
})
