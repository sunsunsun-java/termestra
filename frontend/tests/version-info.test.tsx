// @vitest-environment jsdom

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { act, cleanup, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

const api = vi.hoisted(() => ({ getVersionInfo: vi.fn() }))

vi.mock('../web/src/api.js', () => ({ getVersionInfo: api.getVersionInfo }))

import { Topbar } from '../web/src/layout/Topbar.js'
import { APP_VERSION, useVersionInfo } from '../web/src/useVersionInfo.js'

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
  test('keeps the bundled fallback version aligned with the Maven release', () => {
    const rootPom = readFileSync(resolve(process.cwd(), '../pom.xml'), 'utf8')
    const sourceVersion = rootPom.match(
      /<artifactId>termestra-parent<\/artifactId>\s*<version>([^<]+)<\/version>/
    )?.[1]

    expect(sourceVersion).toBeDefined()
    expect(APP_VERSION).toBe(sourceVersion?.replace(/-SNAPSHOT$/, ''))
  })

  test('shows the running runtime version in the topbar', () => {
    render(
      <Topbar
        hideActions
        version="0.1.0"
        versionInfo={{
          ...versionInfo,
          currentVersion: '0.1.8',
          latestVersion: '0.1.8',
          updateAvailable: false,
        }}
      />
    )

    expect(screen.getByText('v0.1.8')).toBeTruthy()
  })

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
