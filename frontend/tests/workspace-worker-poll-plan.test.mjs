import assert from 'node:assert/strict'
import test from 'node:test'

import {
  ACTIVE_WORKSPACE_REFRESH_INTERVAL_MS,
  INACTIVE_WORKSPACE_REFRESH_INTERVAL_MS,
  planWorkspaceWorkerRefresh,
  workspaceWorkerPollDelay,
} from '../web/src/lib/workspace-worker-poll-plan.ts'

const ids = ['workspace-a', 'workspace-b', 'workspace-c']

test('refreshes every workspace on initial load and visibility restoration', () => {
  const inputIds = ['workspace-b', 'workspace-c', 'workspace-a', 'workspace-b']
  const lastAttemptAt = new Map(inputIds.map((id) => [id, 999]))
  for (const reason of ['initial', 'visible']) {
    assert.deepEqual(
      planWorkspaceWorkerRefresh({
        activeWorkspaceId: 'workspace-a',
        lastAttemptAt,
        now: 1000,
        reason,
        workspaceIds: inputIds,
      }),
      ids,
      'the active workspace must be claimed first and duplicate ids removed'
    )
  }
})

test('prioritizes the active workspace when every scheduled workspace is due', () => {
  assert.deepEqual(
    planWorkspaceWorkerRefresh({
      activeWorkspaceId: 'workspace-a',
      lastAttemptAt: new Map(ids.map((id) => [id, 1000])),
      now: 1000 + INACTIVE_WORKSPACE_REFRESH_INTERVAL_MS,
      reason: 'scheduled',
      workspaceIds: ['workspace-b', 'workspace-c', 'workspace-a'],
    }),
    ids
  )
})

test('paces active and inactive workspaces independently on scheduled ticks', () => {
  const lastAttemptAt = new Map(ids.map((id) => [id, 1000]))
  assert.deepEqual(
    planWorkspaceWorkerRefresh({
      activeWorkspaceId: 'workspace-a',
      lastAttemptAt,
      now: 1000 + ACTIVE_WORKSPACE_REFRESH_INTERVAL_MS - 1,
      reason: 'scheduled',
      workspaceIds: ids,
    }),
    []
  )
  assert.deepEqual(
    planWorkspaceWorkerRefresh({
      activeWorkspaceId: 'workspace-a',
      lastAttemptAt,
      now: 1000 + ACTIVE_WORKSPACE_REFRESH_INTERVAL_MS,
      reason: 'scheduled',
      workspaceIds: ids,
    }),
    ['workspace-a']
  )
  assert.deepEqual(
    planWorkspaceWorkerRefresh({
      activeWorkspaceId: 'workspace-a',
      lastAttemptAt,
      now: 1000 + INACTIVE_WORKSPACE_REFRESH_INTERVAL_MS,
      reason: 'scheduled',
      workspaceIds: ids,
    }),
    ids
  )
})

test('uses the fast timer only when the active workspace is part of the poll set', () => {
  assert.equal(workspaceWorkerPollDelay(ids, 'workspace-a'), ACTIVE_WORKSPACE_REFRESH_INTERVAL_MS)
  assert.equal(workspaceWorkerPollDelay(ids, 'workspace-z'), INACTIVE_WORKSPACE_REFRESH_INTERVAL_MS)
  assert.equal(workspaceWorkerPollDelay(ids, null), INACTIVE_WORKSPACE_REFRESH_INTERVAL_MS)
})
