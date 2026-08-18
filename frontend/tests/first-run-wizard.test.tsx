// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { AppProviders } from '../web/src/AppProviders.js'
import { FirstRunWizard } from '../web/src/wizard/FirstRunWizard.js'

afterEach(cleanup)

describe('first-run guidance', () => {
  test('lets the user go back from every later step, including the final choice', () => {
    render(
      <AppProviders>
        <FirstRunWizard open onAddWorkspace={vi.fn()} onClose={vi.fn()} onTryDemo={vi.fn()} />
      </AppProviders>
    )

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Get started')).not.toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Back' }))
    expect(screen.getByText('How it works')).not.toBeNull()
  })
})
