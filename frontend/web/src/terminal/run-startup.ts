import type { StartupPhase, TerminalRunSummary } from '../api.js'

export const isRunActive = (run: Pick<TerminalRunSummary, 'status'> | undefined): boolean =>
  run?.status === 'starting' || run?.status === 'running'

export const runStartupPhase = (run: TerminalRunSummary | undefined): StartupPhase | null => {
  if (!run) return null
  if (run.startup_phase === 'failed' || run.status === 'error') return 'failed'
  if (run.status === 'exited' || run.status === 'stopped') return null
  return run.startup_phase ?? (run.status === 'running' ? 'ready' : 'initializing')
}
