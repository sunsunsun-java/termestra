import assert from 'node:assert/strict'
import test from 'node:test'

import {
  mergeBootstrapWorkspaces,
  resolveBootstrapActiveWorkspaceId,
} from '../web/src/bootstrap-workspace-state.ts'

const workspace = (id, path = `/${id}`) => ({ id, name: id, path })

test('bootstrap preserves a current selection made while the initial request was pending', () => {
  const workspaces = [workspace('persisted'), workspace('newly-created')]

  assert.equal(
    resolveBootstrapActiveWorkspaceId(workspaces, 'persisted', 'newly-created'),
    'newly-created'
  )
})

test('bootstrap falls back from an invalid current selection to persisted and then first', () => {
  const workspaces = [workspace('first'), workspace('persisted')]

  assert.equal(resolveBootstrapActiveWorkspaceId(workspaces, 'persisted', 'gone'), 'persisted')
  assert.equal(resolveBootstrapActiveWorkspaceId(workspaces, 'also-gone', 'gone'), 'first')
  assert.equal(resolveBootstrapActiveWorkspaceId([], 'persisted', 'current'), null)
})

test('bootstrap merge retains a workspace created before the initial list arrives', () => {
  assert.deepEqual(
    mergeBootstrapWorkspaces(
      [workspace('newly-created')],
      [workspace('existing'), workspace('newly-created', '/canonical')]
    ),
    [workspace('newly-created', '/canonical'), workspace('existing')]
  )
})
