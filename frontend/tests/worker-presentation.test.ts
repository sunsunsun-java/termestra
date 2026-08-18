import { describe, expect, test } from 'vitest'

import {
  presentRuntimeStatus,
  presentWorkerStatus,
  roleTranslationKey,
  statusTranslationKey,
} from '../web/src/worker/presentation.js'

describe('worker presentation policy', () => {
  test('maps the public three-state protocol to visual treatments', () => {
    expect(
      presentWorkerStatus({
        id: 'worker-1',
        name: 'probe',
        pendingTaskCount: 2,
        role: 'tester',
        status: 'working',
      })
    ).toEqual({
      dotClass: 'status-dot status-dot--working',
      kind: 'working',
      tone: 'var(--status-green)',
    })
    expect(presentRuntimeStatus(false).kind).toBe('stopped')
  })

  test('exposes translation keys instead of embedding English labels', () => {
    expect(roleTranslationKey('reviewer')).toBe('role.reviewer')
    expect(statusTranslationKey('working')).toBe('common.running')
    expect(statusTranslationKey('idle')).toBe('common.idle')
  })
})
