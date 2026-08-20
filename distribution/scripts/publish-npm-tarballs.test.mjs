import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import test from 'node:test'

import { packageTarballMatches, verifyPublishedPackage } from './publish-npm-tarballs.mjs'

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
      return versionReads === 1 ? undefined : {
        dist: {
          integrity: 'sha512-release',
          tarball: 'https://registry.example/runtime-test-0.1.0.tgz',
        },
      }
    },
    readDistTags: async () => {
      distTagReads++
      return distTagReads === 1 ? undefined : { latest: '0.1.0' }
    },
    readTarball: async () => true,
    timeoutMs: 100,
    retryDelayMs: 10,
    clock: () => now,
    wait: async delayMs => { now += delayMs },
  })

  assert.equal(versionReads, 3)
  assert.equal(distTagReads, 2)
  assert.equal(now, 20)
})

test('retries while published tarball is still unavailable', async () => {
  let now = 0
  let tarballReads = 0

  await verifyPublishedPackage({
    name: '@termestra/runtime-test',
    version: '0.1.0',
    integrity: 'sha512-release',
    distTag: 'latest',
    readVersion: async () => ({
      dist: {
        integrity: 'sha512-release',
        tarball: 'https://registry.example/runtime-test-0.1.0.tgz',
      },
    }),
    readDistTags: async () => ({ latest: '0.1.0' }),
    readTarball: async (tarballUrl, expectedIntegrity) => {
      assert.equal(tarballUrl, 'https://registry.example/runtime-test-0.1.0.tgz')
      assert.equal(expectedIntegrity, 'sha512-release')
      tarballReads++
      return tarballReads > 1
    },
    timeoutMs: 100,
    retryDelayMs: 10,
    clock: () => now,
    wait: async delayMs => { now += delayMs },
  })

  assert.equal(tarballReads, 2)
  assert.equal(now, 10)
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
      readTarball: async () => true,
      timeoutMs: 100,
      retryDelayMs: 10,
    }),
    /published with different bytes/,
  )
})

test('accepts only the exact recorded integrity for an earlier partial publication', async () => {
  const publishedIntegrity = await verifyPublishedPackage({
    name: '@termestra/runtime-test',
    version: '0.1.0',
    integrity: 'sha512-current',
    acceptedIntegrity: 'sha512-earlier',
    distTag: 'latest',
    readVersion: async () => ({
      dist: {
        integrity: 'sha512-earlier',
        tarball: 'https://registry.example/runtime-test-0.1.0.tgz',
      },
    }),
    readDistTags: async () => ({ latest: '0.1.0' }),
    readTarball: async () => true,
    timeoutMs: 100,
    retryDelayMs: 10,
  })

  assert.equal(publishedIntegrity, 'sha512-earlier')
})

test('downloads the real tarball body with origin, integrity, status, and timeout checks', async () => {
  const body = Buffer.from('complete npm tarball bytes')
  const integrity = `sha512-${createHash('sha512').update(body).digest('base64')}`
  const server = createServer((request, response) => {
    if (request.url === '/ok.tgz') {
      response.writeHead(200, { 'content-type': 'application/octet-stream' })
      response.end(body)
      return
    }
    if (request.url === '/hang.tgz') {
      response.writeHead(200, { 'content-type': 'application/octet-stream' })
      response.flushHeaders()
      request.once('close', () => response.destroy())
      return
    }
    response.writeHead(404).end()
  })
  const address = await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => resolve(server.address()))
  })
  assert.ok(address && typeof address === 'object')
  const registry = `http://127.0.0.1:${address.port}`
  try {
    assert.equal(await packageTarballMatches(registry, `${registry}/ok.tgz`, integrity), true)
    assert.equal(await packageTarballMatches(registry, `${registry}/ok.tgz`, 'sha512-wrong'), false)
    assert.equal(await packageTarballMatches(registry, `${registry}/missing.tgz`, integrity), false)
    await assert.rejects(
      packageTarballMatches(registry, `http://localhost:${address.port}/ok.tgz`, integrity),
      /configured registry origin/,
    )
    await assert.rejects(
      packageTarballMatches(registry, `${registry}/hang.tgz`, integrity, 50),
      error => error?.name === 'TimeoutError' || error?.name === 'AbortError',
    )
  } finally {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
  }
})
