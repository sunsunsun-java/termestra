import assert from 'node:assert/strict'
import test from 'node:test'

import {
  markWorkspacePollFailure,
  markWorkspacePollSuccess,
  planWorkspaceWorkerRefresh,
} from '../web/src/lib/workspace-worker-poll-plan.ts'
import {
  terminalRunsAfterFailure,
  terminalRunsAfterSuccess,
} from '../web/src/terminal/terminal-runs-state.ts'

test('terminal polling failures preserve the last-known-good runs and mark them stale', () => {
  const previous = terminalRunsAfterSuccess([], [
    { agent_id: 'a', agent_name: 'A', run_id: 'r', status: 'running' },
  ])
  const failed = terminalRunsAfterFailure(previous)

  assert.strictEqual(failed.runs, previous.runs)
  assert.equal(failed.stale, true)
  assert.equal(failed.failureCount, 1)
})

test('workspace worker backoff is independent per workspace', () => {
  const health = new Map()
  markWorkspacePollSuccess(health, 'workspace-a', 1000)
  markWorkspacePollFailure(health, 'workspace-b', 1000)
  markWorkspacePollFailure(health, 'workspace-b', 1000)

  assert.deepEqual(
    planWorkspaceWorkerRefresh({
      activeWorkspaceId: 'workspace-a',
      health,
      now: 1500,
      reason: 'scheduled',
      workspaceIds: ['workspace-a', 'workspace-b'],
    }),
    ['workspace-a'],
    'a failing workspace must not slow the healthy active workspace or retry at its base rate'
  )
})
