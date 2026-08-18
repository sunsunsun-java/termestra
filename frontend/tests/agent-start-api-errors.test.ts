// @vitest-environment jsdom

import { afterEach, describe, expect, test, vi } from 'vitest'

import { ApiRequestError, startAgentRun } from '../web/src/api.js'

const jsonResponse = (body: unknown, status: number): Response =>
  new Response(JSON.stringify(body), {
    headers: { 'content-type': 'application/json' },
    status,
  })

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('agent start API failures', () => {
  test('preserves a retryable runtime-busy response as a typed API error', async () => {
    const request = vi.fn(async () =>
      jsonResponse(
        {
          error: 'Workspace runtime operation is busy',
          error_code: 'RUNTIME_OPERATION_BUSY',
          retry_after_ms: 1000,
          retryable: true,
        },
        409
      )
    )
    vi.stubGlobal('fetch', request)

    let thrown: unknown
    try {
      await startAgentRun('workspace-1', 'workspace-1:orchestrator')
    } catch (error) {
      thrown = error
    }

    expect(thrown).toBeInstanceOf(ApiRequestError)
    expect(thrown).toMatchObject({
      code: 'RUNTIME_OPERATION_BUSY',
      message: 'Workspace runtime operation is busy',
      retryAfterMs: 1000,
      retryable: true,
      status: 409,
    })
    expect(request).toHaveBeenCalledWith(
      '/api/workspaces/workspace-1/agents/workspace-1:orchestrator/start',
      expect.objectContaining({ method: 'POST' })
    )
  })
})
