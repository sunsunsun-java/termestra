import assert from 'node:assert/strict'
import { win32 } from 'node:path'
import test from 'node:test'

import { normalizeRepositoryPath } from './verify-brand-boundary.mjs'

test('normalizes Windows relative paths before matching the repository allowlists', () => {
  const root = 'C:\\termestra'
  for (const expectedPath of [
    'docs/governance/licensing-review.md',
    'scripts/verify-brand-boundary.mjs',
  ]) {
    const windowsPath = win32.relative(root, win32.resolve(root, expectedPath))
    assert.equal(normalizeRepositoryPath(windowsPath), expectedPath)
  }
})
