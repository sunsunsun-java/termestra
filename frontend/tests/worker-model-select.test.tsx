// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { I18nProvider } from '../web/src/i18n.js'
import { WorkerModelSelect } from '../web/src/worker/WorkerModelSelect.js'

afterEach(cleanup)

describe('worker model selection', () => {
  test('shows the CLI default and discovered models in one dropdown', () => {
    const onChange = vi.fn()
    render(
      <I18nProvider>
        <WorkerModelSelect modelId="" models={['gpt-a', 'gpt-b']} onChange={onChange} />
      </I18nProvider>
    )

    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Use CLI default model', 'gpt-a', 'gpt-b',
    ])
    fireEvent.change(screen.getByRole('combobox', { name: 'Model' }), { target: { value: 'gpt-b' } })
    expect(onChange).toHaveBeenCalledWith('explicit', 'gpt-b')
    fireEvent.change(screen.getByRole('combobox', { name: 'Model' }), { target: { value: '' } })
    expect(onChange).toHaveBeenCalledWith('default', '')
  })

  test('shows only the CLI default when discovery is unsupported or fails', () => {
    render(
      <I18nProvider>
        <WorkerModelSelect modelId="" models={[]} onChange={vi.fn()} />
      </I18nProvider>
    )

    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.getByTestId('worker-model-default').textContent).toBe('Use CLI default model')
  })
})
