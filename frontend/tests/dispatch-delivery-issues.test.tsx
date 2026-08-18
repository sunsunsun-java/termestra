// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

const { listDispatchDeliveryIssues, retryDispatchDelivery } = vi.hoisted(() => ({
  listDispatchDeliveryIssues: vi.fn(),
  retryDispatchDelivery: vi.fn(),
}))

vi.mock('../web/src/api.js', () => ({
  listDispatchDeliveryIssues,
  retryDispatchDelivery,
}))

import { useDispatchDeliveryIssues } from '../web/src/worker/useDispatchDeliveryIssues.js'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('dispatch delivery issue visibility', () => {
  test('shows only actionable deliveries and removes one after an explicit retry', async () => {
    listDispatchDeliveryIssues.mockResolvedValue([
      {
        id: 'uncertain-1',
        toAgentId: 'worker-1',
        text: 'task',
        state: 'queued',
        deliveryState: 'uncertain',
        deliveryAttemptCount: 1,
        deliveryError: 'input acknowledgement timed out',
        deliveryNextAttemptAt: null,
        deliveryInputAttempted: true,
      },
    ])
    retryDispatchDelivery.mockResolvedValue(undefined)

    const { result, unmount } = renderHook(() => useDispatchDeliveryIssues('workspace-1'))
    await waitFor(() => expect(result.current.issues.map((item) => item.id)).toEqual(['uncertain-1']))

    await act(async () => result.current.retry('uncertain-1'))

    expect(retryDispatchDelivery).toHaveBeenCalledWith('workspace-1', 'uncertain-1')
    expect(result.current.issues).toEqual([])
    unmount()
  })
})
