// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test } from 'vitest'

import { AppProviders } from '../web/src/AppProviders.js'
import { OpenWorkspaceButton } from '../web/src/workspace/OpenWorkspaceButton.js'

afterEach(() => {
  cleanup()
  window.localStorage.clear()
})

describe('workspace launch target menu', () => {
  test('uses a compact split button with a polished, persistent target menu', () => {
    Object.defineProperty(window.navigator, 'userAgent', {
      configurable: true,
      value: 'Mozilla/5.0 (Macintosh; Intel Mac OS X)',
    })

    render(
      <AppProviders>
        <OpenWorkspaceButton workspace={{ id: 'workspace-1', name: 'Termestra', path: '/tmp' }} />
      </AppProviders>
    )

    const openButton = screen.getByTestId('topbar-open-workspace')
    expect(openButton.querySelector('img')).toBeNull()
    expect(screen.getByTestId('open-target-mark-finder').querySelector('svg')).not.toBeNull()

    fireEvent.click(screen.getByTestId('topbar-open-workspace-chevron'))
    const menu = screen.getByTestId('topbar-open-workspace-menu')
    expect(menu.querySelectorAll('[data-testid^="open-target-mark-"]')).toHaveLength(7)
    expect(menu.querySelectorAll('img')).toHaveLength(0)
    expect(screen.getAllByRole('menuitemradio').length).toBe(7)
    expect(
      screen
        .getByRole('menuitemradio', { name: 'IntelliJ IDEA' })
        .querySelector('[data-testid="open-target-mark-intellij-idea"] svg')
    ).not.toBeNull()
    expect(screen.getByRole('menuitemradio', { name: 'Finder' }).getAttribute('aria-checked')).toBe(
      'true'
    )

    fireEvent.click(screen.getByRole('menuitemradio', { name: 'VS Code' }))

    expect(screen.queryByRole('menu')).toBeNull()
    expect(screen.getByTestId('open-target-mark-vscode').querySelector('svg')).not.toBeNull()
    expect(openButton.querySelector('img')).toBeNull()
    expect(window.localStorage.getItem('termestra.openTarget.preferred')).toBe('vscode')
  })
})
