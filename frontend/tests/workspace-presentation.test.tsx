// @vitest-environment jsdom

import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test } from 'vitest'

import { deriveInitial, pickWorkspaceColor } from '../web/src/sidebar/derive-workspace-color.js'
import { EmptyState } from '../web/src/ui/EmptyState.js'
import { buildBreadcrumbs } from '../web/src/workspace/path-breadcrumbs.js'

afterEach(cleanup)

describe('workspace presentation', () => {
  test('keeps a complete Unicode grapheme as the workspace avatar initial', () => {
    expect(deriveInitial('  👩🏽‍💻 tools')).toBe('👩🏽‍💻')
    expect(deriveInitial('  termestra')).toBe('T')
    expect(deriveInitial('   ')).toBe('?')
  })

  test('derives a stable non-error color from workspace identity', () => {
    const first = pickWorkspaceColor('workspace-17')
    expect(pickWorkspaceColor('workspace-17')).toEqual(first)
    expect(first.token).not.toContain('status-red')
  })

  test('keeps backslash-delimited breadcrumb paths internally consistent', () => {
    expect(buildBreadcrumbs('C:\\Users\\sun\\Termestra', 'C:\\Users\\sun')).toEqual([
      { label: '~ (sun)', path: 'C:\\Users\\sun' },
      { label: 'Termestra', path: 'C:\\Users\\sun\\Termestra' },
    ])
  })

  test('announces an empty state as a named region', () => {
    render(<EmptyState title="No workers" description="Add a team member to begin." />)

    expect(screen.getByRole('region', { name: 'No workers' })).not.toBeNull()
  })
})
