// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

const api = vi.hoisted(() => ({ getVersionInfo: vi.fn() }))

vi.mock('../web/src/api.js', () => ({ getVersionInfo: api.getVersionInfo }))

import { useVersionInfo } from '../web/src/useVersionInfo.js'

const versionInfo = {
  currentVersion: '0.1.0',
  installHint: 'npm install -g termestra',
  latestVersion: '0.2.0',
  packageName: 'termestra',
  releaseUrl: 'https://example.test/releases/0.2.0',
  updateAvailable: true,
}

const deferred = <T,>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => {
    resolve = complete
  })
  return { promise, resolve }
}

afterEach(() => {
  cleanup()
  api.getVersionInfo.mockReset()
})

describe('version information', () => {
  test('shares one in-flight request across concurrent consumers', async () => {
    const request = deferred<typeof versionInfo>()
    api.getVersionInfo.mockReturnValue(request.promise)

    const first = renderHook(() => useVersionInfo())
    const second = renderHook(() => useVersionInfo())

    expect(api.getVersionInfo).toHaveBeenCalledTimes(1)
    await act(async () => request.resolve(versionInfo))
    await waitFor(() => expect(first.result.current).toEqual(versionInfo))
    expect(second.result.current).toEqual(versionInfo)
  })
})
