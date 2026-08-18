// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { useTasksFile } from '../web/src/tasks/useTasksFile.js'

const deferred = <T,>() => {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, reject, resolve }
}

const jsonResponse = (body: unknown): Response =>
  ({
    json: async () => body,
    ok: true,
    status: 200,
  }) as Response

class TasksSocketStub {
  static instances: TasksSocketStub[] = []

  onclose: ((event: CloseEvent) => unknown) | null = null
  onerror: ((event: Event) => unknown) | null = null
  onmessage: ((event: MessageEvent) => unknown) | null = null

  constructor(_url: string) {
    TasksSocketStub.instances.push(this)
  }

  close() {}

  snapshot(content: string, revision: string) {
    this.onmessage?.({
      data: JSON.stringify({ content, revision, type: 'tasks-snapshot' }),
    } as MessageEvent)
  }
}

afterEach(() => {
  cleanup()
  TasksSocketStub.instances = []
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Tasks save acknowledgement ordering', () => {
  test('an older save acknowledgement cannot overwrite a newer unsaved editor value', async () => {
    const firstResponse = deferred<Response>()
    const secondResponse = deferred<Response>()
    const requests: Array<{ content: string; revision?: string }> = []
    vi.stubGlobal('WebSocket', TasksSocketStub)
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method !== 'PUT') throw new Error('Unexpected non-save request')
        requests.push(JSON.parse(String(init.body)))
        return requests.length === 1 ? firstResponse.promise : secondResponse.promise
      })
    )

    const { result } = renderHook(() => useTasksFile('workspace-a'))
    act(() => TasksSocketStub.instances[0]?.snapshot('initial', 'r0'))
    expect(result.current.content).toBe('initial')

    let firstSave!: Promise<unknown>
    act(() => {
      result.current.onChange('submitted A')
      firstSave = result.current.onSave()
    })
    await waitFor(() => expect(requests).toEqual([{ content: 'submitted A', revision: 'r0' }]))

    act(() => result.current.onChange('unsaved B'))
    await act(async () => {
      firstResponse.resolve(jsonResponse({ content: 'submitted A', revision: 'r1' }))
      await firstSave
    })

    expect(result.current.content).toBe('unsaved B')

    let secondSave!: Promise<unknown>
    act(() => {
      secondSave = result.current.onSave()
    })
    await waitFor(() =>
      expect(requests).toEqual([
        { content: 'submitted A', revision: 'r0' },
        { content: 'unsaved B', revision: 'r1' },
      ])
    )
    await act(async () => {
      secondResponse.resolve(jsonResponse({ content: 'unsaved B', revision: 'r2' }))
      await secondSave
    })
    expect(result.current.content).toBe('unsaved B')
  })

  test('a late active acknowledgement cannot clear a divergent remote conflict', async () => {
    const response = deferred<Response>()
    const requests: Array<{ content: string; revision?: string }> = []
    vi.stubGlobal('WebSocket', TasksSocketStub)
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method !== 'PUT') throw new Error('Unexpected non-save request')
        requests.push(JSON.parse(String(init.body)))
        return response.promise
      })
    )

    const { result } = renderHook(() => useTasksFile('workspace-a'))
    act(() => TasksSocketStub.instances[0]?.snapshot('server S', 'r0'))
    let activeSave!: Promise<unknown>
    act(() => {
      result.current.onChange('local A')
      activeSave = result.current.onSave()
    })
    await waitFor(() => expect(requests).toEqual([{ content: 'local A', revision: 'r0' }]))

    act(() => TasksSocketStub.instances[0]?.snapshot('local A', 'r1'))
    act(() => TasksSocketStub.instances[0]?.snapshot('remote D', 'r2'))
    expect(result.current.content).toBe('local A')
    expect(result.current.hasConflict).toBe(true)

    await act(async () => {
      response.resolve(jsonResponse({ content: 'local A', revision: 'r1' }))
      await activeSave
    })

    expect(result.current.content).toBe('local A')
    expect(result.current.hasConflict).toBe(true)
    act(() => result.current.onReload())
    expect(result.current.content).toBe('remote D')
  })

  test('reloading a divergent remote value discards a pending local save', async () => {
    const activeResponse = deferred<Response>()
    const requests: Array<{ content: string; revision?: string }> = []
    vi.stubGlobal('WebSocket', TasksSocketStub)
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method !== 'PUT') throw new Error('Unexpected non-save request')
        requests.push(JSON.parse(String(init.body)))
        if (requests.length > 1) throw new Error('A superseded pending save reached the server')
        return activeResponse.promise
      })
    )

    const { result } = renderHook(() => useTasksFile('workspace-a'))
    act(() => TasksSocketStub.instances[0]?.snapshot('server S', 'r0'))
    let activeSave!: Promise<unknown>
    let pendingSave!: Promise<unknown>
    act(() => {
      result.current.onChange('local A')
      activeSave = result.current.onSave()
      result.current.onChange('pending B')
      pendingSave = result.current.onSave()
    })
    await waitFor(() => expect(requests).toEqual([{ content: 'local A', revision: 'r0' }]))

    act(() => TasksSocketStub.instances[0]?.snapshot('remote D', 'r2'))
    expect(result.current.hasConflict).toBe(true)
    act(() => result.current.onReload())
    expect(result.current.content).toBe('remote D')
    expect(result.current.hasConflict).toBe(false)
    await expect(pendingSave).resolves.toBeUndefined()

    await act(async () => {
      activeResponse.resolve(jsonResponse({ content: 'local A', revision: 'r1' }))
      await activeSave
    })
    await new Promise((resolve) => setImmediate(resolve))

    expect(requests).toEqual([{ content: 'local A', revision: 'r0' }])
    expect(result.current.content).toBe('remote D')
    expect(result.current.hasConflict).toBe(false)
  })
})
