import assert from 'node:assert/strict'
import test from 'node:test'

import { verifyPublishedPackage } from './publish-npm-tarballs.mjs'

test('retries until both the published version and dist-tag are visible', async () => {
  let now = 0
  let versionReads = 0
  let distTagReads = 0

  await verifyPublishedPackage({
    name: '@termestra/runtime-test',
    version: '0.1.0',
    integrity: 'sha512-release',
    distTag: 'latest',
    readVersion: async () => {
      versionReads++
      return versionReads === 1 ? undefined : { dist: { integrity: 'sha512-release' } }
    },
    readDistTags: async () => {
      distTagReads++
      return distTagReads === 1 ? undefined : { latest: '0.1.0' }
    },
    timeoutMs: 100,
    retryDelayMs: 10,
    clock: () => now,
    wait: async delayMs => { now += delayMs },
  })

  assert.equal(versionReads, 3)
  assert.equal(distTagReads, 2)
  assert.equal(now, 20)
})

test('rejects an already-published version with different bytes', async () => {
  await assert.rejects(
    verifyPublishedPackage({
      name: '@termestra/runtime-test',
      version: '0.1.0',
      integrity: 'sha512-release',
      distTag: 'latest',
      readVersion: async () => ({ dist: { integrity: 'sha512-other' } }),
      readDistTags: async () => ({ latest: '0.1.0' }),
      timeoutMs: 100,
      retryDelayMs: 10,
    }),
    /published with different bytes/,
  )
})
