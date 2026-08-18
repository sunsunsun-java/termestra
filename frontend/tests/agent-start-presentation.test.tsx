// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { type ReactNode, useState } from 'react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { ApiRequestError } from '../web/src/api.js'
import { I18nProvider } from '../web/src/i18n.js'
import { UI_LANGUAGE_STORAGE_KEY, type UiLanguage } from '../web/src/uiLanguage.js'
import { presentAgentStartError } from '../web/src/worker/agent-start-error.js'
import { useOrchestratorPaneState } from '../web/src/worker/useOrchestratorPaneState.js'

const api = vi.hoisted(() => ({
  startAgentRun: vi.fn(),
  stopAgentRun: vi.fn(),
}))

vi.mock('../web/src/api.js', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../web/src/api.js')>()),
  startAgentRun: api.startAgentRun,
  stopAgentRun: api.stopAgentRun,
}))

const EN_BUSY =
  'This workspace is temporarily busy. Try starting the Orchestrator again in about 1 second.'
const ZH_BUSY = '此 Workspace 暂时繁忙，请约 1 秒后再次启动 Orchestrator。'
const EN_TIMEOUT =
  'The start request timed out, so its result is unknown. Refresh to confirm the Orchestrator status before deciding whether to try again.'
const ZH_TIMEOUT =
  '启动请求已超时，结果可能未知。请先刷新确认 Orchestrator 状态，再决定是否重试。'

const runtimeBusy = () =>
  new ApiRequestError('Workspace runtime operation is busy', {
    code: 'RUNTIME_OPERATION_BUSY',
    retryAfterMs: 1000,
    retryable: true,
    status: 409,
  })

const timedOut = () =>
  new DOMException('The request exceeded its 60000 ms deadline.', 'TimeoutError')

const deferredStart = () => {
  let reject!: (reason?: unknown) => void
  const promise = new Promise<{ runId: string }>((_resolve, rejectPromise) => {
    reject = rejectPromise
  })
  return { promise, reject }
}

const wrapper = ({ children }: { children: ReactNode }) => (
  <I18nProvider>{children}</I18nProvider>
)

const renderStartState = (language: UiLanguage) => {
  window.localStorage.setItem(UI_LANGUAGE_STORAGE_KEY, language)
  return renderHook(() => {
    const [autostartError, setAutostartError] = useState<string | null>(null)
    return useOrchestratorPaneState({
      autostartError,
      onAfterStart: (result) => setAutostartError(result.error),
      onClearAutostartError: () => setAutostartError(null),
      terminalRuns: [],
      workspaceId: 'workspace-1',
    })
  }, { wrapper })
}

const runningOrchestrator = (runId = 'run-late-success') => ({
  agent_id: 'workspace-1:orchestrator',
  agent_name: 'Orchestrator',
  command: ['codex'],
  exit_code: null,
  pid: 42,
  run_id: runId,
  started_at: new Date().toISOString(),
  status: 'running' as const,
  terminal_input_profile: 'default' as const,
  workspace_id: 'workspace-1',
})

afterEach(() => {
  cleanup()
  api.startAgentRun.mockReset()
  api.stopAgentRun.mockReset()
  window.localStorage.clear()
})

describe('agent start failure presentation', () => {
  test('presents runtime-busy guidance in English and Chinese', () => {
    expect(presentAgentStartError(runtimeBusy(), 'en')).toBe(EN_BUSY)
    expect(presentAgentStartError(runtimeBusy(), 'zh')).toBe(ZH_BUSY)
  })

  test('presents a timeout as an unknown result that must be refreshed before retrying', () => {
    expect(presentAgentStartError(timedOut(), 'en')).toBe(EN_TIMEOUT)
    expect(presentAgentStartError(timedOut(), 'zh')).toBe(ZH_TIMEOUT)
  })

  test('leaves the starting state and shows localized busy guidance after rejection', async () => {
    const start = deferredStart()
    api.startAgentRun.mockReturnValue(start.promise)
    const { result } = renderStartState('zh')

    act(() => result.current.start())
    expect(result.current.state).toEqual({ kind: 'starting' })

    await act(async () => start.reject(runtimeBusy()))
    await waitFor(() => expect(result.current.state).toEqual({ kind: 'failed', error: ZH_BUSY }))
  })

  test('leaves the starting state and explains an English timeout without blind retry advice', async () => {
    const start = deferredStart()
    api.startAgentRun.mockReturnValue(start.promise)
    const { result } = renderStartState('en')

    act(() => result.current.start())
    expect(result.current.state).toEqual({ kind: 'starting' })

    await act(async () => start.reject(timedOut()))
    await waitFor(() =>
      expect(result.current.state).toEqual({ kind: 'failed', error: EN_TIMEOUT })
    )
  })

  test('clears a sticky timeout when polling observes the authoritative run succeed later', async () => {
    window.localStorage.setItem(UI_LANGUAGE_STORAGE_KEY, 'en')
    const clearError = vi.fn()
    const { result, rerender } = renderHook(
      ({ terminalRuns }) =>
        useOrchestratorPaneState({
          autostartError: EN_TIMEOUT,
          onClearAutostartError: clearError,
          terminalRuns,
          workspaceId: 'workspace-1',
        }),
      {
        initialProps: { terminalRuns: [] as ReturnType<typeof runningOrchestrator>[] },
        wrapper,
      }
    )

    expect(result.current.state).toEqual({ kind: 'failed', error: EN_TIMEOUT })
    rerender({ terminalRuns: [runningOrchestrator()] })

    await waitFor(() => expect(clearError).toHaveBeenCalledTimes(1))
    expect(result.current.state).toEqual({ kind: 'running', runId: 'run-late-success' })
  })
})
