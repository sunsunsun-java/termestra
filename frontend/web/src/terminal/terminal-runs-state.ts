import type { TerminalRunSummary } from '../api.js'

export interface TerminalRunsPollState {
  failureCount: number
  initialized: boolean
  runs: TerminalRunSummary[]
  stale: boolean
}

const MAX_BACKOFF_EXPONENT = 4

export const initialTerminalRunsPollState = (): TerminalRunsPollState => ({
  failureCount: 0,
  initialized: false,
  runs: [],
  stale: false,
})

export const terminalRunsAfterSuccess = (
  previous: Pick<TerminalRunsPollState, 'runs'>,
  runs: TerminalRunSummary[]
): TerminalRunsPollState => ({
  failureCount: 0,
  initialized: true,
  runs,
  stale: false,
})

export const terminalRunsAfterFailure = (
  previous: TerminalRunsPollState
): TerminalRunsPollState => ({
  failureCount: Math.min(previous.failureCount + 1, MAX_BACKOFF_EXPONENT),
  initialized: previous.initialized,
  runs: previous.runs,
  stale: true,
})
