// @vitest-environment jsdom
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import type { FormEvent, ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { I18nProvider } from '../web/src/i18n.js'
import { useWorkerComposer } from '../web/src/worker/useWorkerComposer.js'

const body = '完整指令'.repeat(10_000) + 'FINAL INSTRUCTION'
const role = (id: string, description = body) => ({
  id, name: id, role_type: 'custom', description, is_builtin: false,
})
const reply = (value: unknown) => new Response(JSON.stringify(value), { status: 200 })
const submitEvent = () => ({ preventDefault: vi.fn() }) as unknown as FormEvent<HTMLFormElement>
const wrapper = ({ children }: { children: ReactNode }) => <I18nProvider>{children}</I18nProvider>
const deferred = () => {
  let resolve!: (value: Response) => void
  const promise = new Promise<Response>((done) => { resolve = done })
  return { promise, resolve }
}

function setup(detail: (path: string, init?: RequestInit) => Promise<Response>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = input.toString()
    if (path === '/api/ui/settings/role-templates') return reply([role('a', body.slice(0, 4096)), role('b', 'second summary')])
    if (path.endsWith('/agent-launch-options')) return reply({ orchestrator: null, presets: [] })
    return detail(path, init)
  })
  vi.stubGlobal('fetch', fetchMock)
  const createWorker = vi.fn(async (_input: unknown) => ({ error: null, runId: null }))
  const hook = renderHook(({ open, scopeKey }) => useWorkerComposer({ createWorker, open, scopeKey, workers: [] }),
    { initialProps: { open: true, scopeKey: 'workspace' }, wrapper })
  return { ...hook, fetchMock, createWorker }
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

test('loads full instructions only on selection, blocks premature submit, and resets to the full default', async () => {
  const pending = deferred()
  const { result, fetchMock, createWorker } = setup(async () => pending.promise)
  await waitFor(() => expect(result.current.customTemplates).toHaveLength(2))
  expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/role-templates/a'))).toBe(false)
  act(() => result.current.selectTemplate('a'))
  expect(result.current.roleDescription).toBe('')
  act(() => result.current.submit(submitEvent(), vi.fn()))
  expect(createWorker).not.toHaveBeenCalled()
  await act(async () => pending.resolve(reply(role('a'))))
  await waitFor(() => expect(result.current.roleDescription).toBe(body))
  expect(result.current.customTemplates[0]?.description.length).toBe(4096)
  act(() => result.current.setRoleDescription('edited'))
  act(() => result.current.resetRoleDescription())
  expect(result.current.roleDescription).toBe(body)
  act(() => result.current.submit(submitEvent(), vi.fn()))
  await waitFor(() => expect(createWorker).toHaveBeenCalledOnce())
  expect(createWorker.mock.calls[0]?.[0]).toMatchObject({ roleDescription: body })
})

test('saving imported full instructions retains a bounded list row and a full reset default', async () => {
  let savedDescription = ''
  const { result } = setup(async (path, init) => {
    expect(path).toBe('/api/settings/role-templates')
    savedDescription = JSON.parse(init?.body as string).description
    return reply(role('saved', savedDescription))
  })
  await waitFor(() => expect(result.current.customTemplates).toHaveLength(2))
  act(() => result.current.applyMarketplaceImport({ name: 'Imported', description: body }))
  await act(async () => result.current.saveAsTemplate('Saved'))
  expect(savedDescription).toBe(body)
  expect(result.current.customTemplates.find(({ id }) => id === 'saved')?.description.length).toBe(4096)
  act(() => result.current.setRoleDescription('edited'))
  act(() => result.current.resetRoleDescription())
  expect(result.current.roleDescription).toBe(body)
})

test('switching templates aborts old detail and preserves user edits while the new detail loads', async () => {
  const a = deferred(), b = deferred()
  let firstSignal: AbortSignal | null | undefined
  const { result } = setup(async (path, init) => {
    if (path.endsWith('/a')) { firstSignal = init?.signal; return a.promise }
    return b.promise
  })
  await waitFor(() => expect(result.current.customTemplates).toHaveLength(2))
  act(() => result.current.selectTemplate('a'))
  await waitFor(() => expect(firstSignal).toBeDefined())
  act(() => result.current.selectTemplate('b'))
  expect(firstSignal?.aborted).toBe(true)
  act(() => result.current.setRoleDescription('my edits'))
  await act(async () => { a.resolve(reply(role('a'))); b.resolve(reply(role('b', body + ' B'))) })
  await waitFor(() => expect(result.current.templateBusy).toBe(false))
  expect(result.current.roleDescription).toBe('my edits')
  act(() => result.current.resetRoleDescription())
  expect(result.current.roleDescription).toBe(body + ' B')
})

test('closing a pending detail clears busy state and reopening retries the full detail', async () => {
  const pending = deferred()
  let requests = 0
  const { result, rerender } = setup(async () => ++requests === 1 ? pending.promise : reply(role('a')))
  await waitFor(() => expect(result.current.customTemplates).toHaveLength(2))
  act(() => result.current.selectTemplate('a'))
  await waitFor(() => expect(requests).toBe(1))
  rerender({ open: false, scopeKey: 'workspace' })
  await waitFor(() => expect(result.current.templateBusy).toBe(false))
  rerender({ open: true, scopeKey: 'workspace' })
  await waitFor(() => expect(result.current.roleDescription).toBe(body))
  expect(requests).toBe(2)
  await act(async () => pending.resolve(reply(role('a', 'stale'))))
  expect(result.current.roleDescription).toBe(body)
})

test('failed detail cannot submit its summary, reset retries, and an ordinary empty custom description stays valid', async () => {
  let requests = 0
  const { result, createWorker } = setup(async () => {
    if (++requests === 1) return new Response(JSON.stringify({ error: 'missing role' }), { status: 404 })
    return reply(role('a'))
  })
  await waitFor(() => expect(result.current.customTemplates).toHaveLength(2))
  act(() => result.current.selectTemplate('a'))
  await waitFor(() => expect(result.current.createWorkerError).toBe('missing role'))
  act(() => result.current.submit(submitEvent(), vi.fn()))
  expect(createWorker).not.toHaveBeenCalled()
  act(() => result.current.resetRoleDescription())
  await waitFor(() => expect(result.current.roleDescription).toBe(body))
  act(() => result.current.selectTemplate(null))
  act(() => result.current.setRoleDescription(''))
  act(() => result.current.submit(submitEvent(), vi.fn()))
  await waitFor(() => expect(createWorker).toHaveBeenCalledOnce())
  expect(createWorker.mock.calls[0]?.[0]).toMatchObject({ roleDescription: '' })
})
