#!/usr/bin/env node
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'
import { pathToFileURL } from 'node:url'
import { gunzipSync } from 'node:zlib'

const DEFAULT_VERIFICATION_TIMEOUT_MS = 10 * 60 * 1000
const DEFAULT_VERIFICATION_RETRY_DELAY_MS = 5 * 1000
const DEFAULT_TARBALL_RETRY_DELAY_MS = 1000
const MAX_TARBALL_REQUESTS = 48

class TarballRequestCapacityError extends Error {
  constructor(tarballUrl, cause) {
    super(`npm tarball download exceeded ${MAX_TARBALL_REQUESTS} requests: ${tarballUrl}`, { cause })
    this.name = 'TarballRequestCapacityError'
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main(process.argv.slice(2))
}

async function main(arguments_) {
  const [distTag, ...tarballs] = arguments_
  if (!distTag || tarballs.length === 0) {
    console.error('Usage: node publish-npm-tarballs.mjs <dist-tag> <package.tgz>...')
    process.exit(2)
  }

  assert.match(distTag, /^[a-z][a-z0-9._-]*$/, `Invalid npm dist-tag: ${distTag}`)
  const registry = (process.env.TERMESTRA_NPM_REGISTRY ?? 'https://registry.npmjs.org').replace(/\/+$/, '')
  const withProvenance = process.env.TERMESTRA_NPM_PUBLISH_MODE === 'bootstrap-token'
  const acceptedExistingIntegrities = acceptedExistingIntegritiesFromEnvironment()
  const verificationTimeoutMs = durationFromEnvironment(
    'TERMESTRA_NPM_VERIFICATION_TIMEOUT_MS', DEFAULT_VERIFICATION_TIMEOUT_MS)
  const verificationRetryDelayMs = durationFromEnvironment(
    'TERMESTRA_NPM_VERIFICATION_RETRY_DELAY_MS', DEFAULT_VERIFICATION_RETRY_DELAY_MS)

  for (const tarball of tarballs) {
    const manifest = packageManifest(tarball)
    assert.equal(manifest.publishConfig?.access, 'public', `${tarball} must declare public npm access`)
    assert.equal(manifest.publishConfig?.registry, registry, `${tarball} must declare ${registry} as its publish registry`)
    assert.ok(manifest.repository?.url, `${tarball} must declare repository.url for provenance`)
    const integrity = tarballIntegrity(tarball)
    const existing = await packageVersion(registry, manifest.name, manifest.version)

    if (!existing) {
      const publishArguments = ['publish', tarball, '--access', 'public', '--tag', distTag]
      if (withProvenance) publishArguments.push('--provenance')
      const result = spawnSync('npm', publishArguments, { stdio: 'inherit', env: process.env })
      assert.ifError(result.error)
      assert.equal(result.status, 0, `npm publish failed for ${tarball}`)
    }

    const publishedIntegrity = await verifyPublishedPackage({
      name: manifest.name,
      version: manifest.version,
      integrity,
      acceptedIntegrity: acceptedExistingIntegrities[`${manifest.name}@${manifest.version}`],
      distTag,
      readVersion: () => packageVersion(registry, manifest.name, manifest.version),
      readDistTags: () => packageDistTags(registry, manifest.name),
      readTarball: (tarballUrl, publishedIntegrity, timeoutMs, requestBudget) =>
        packageTarballMatches(registry, tarballUrl, publishedIntegrity, timeoutMs, { requestBudget }),
      timeoutMs: verificationTimeoutMs,
      retryDelayMs: verificationRetryDelayMs,
    })
    if (publishedIntegrity === integrity) {
      console.log(`${existing ? 'Verified already-published' : 'Published'} ${manifest.name}@${manifest.version}`)
    } else {
      console.log(`Verified accepted earlier publication ${manifest.name}@${manifest.version}`)
    }
  }
}

export async function verifyPublishedPackage({
  name,
  version,
  integrity,
  acceptedIntegrity,
  distTag,
  readVersion,
  readDistTags,
  readTarball,
  timeoutMs = DEFAULT_VERIFICATION_TIMEOUT_MS,
  retryDelayMs = DEFAULT_VERIFICATION_RETRY_DELAY_MS,
  clock = Date.now,
  wait = sleep,
}) {
  assert.ok(Number.isSafeInteger(timeoutMs) && timeoutMs > 0, 'verification timeout must be a positive integer')
  assert.ok(Number.isSafeInteger(retryDelayMs) && retryDelayMs > 0, 'verification retry delay must be a positive integer')
  assert.equal(typeof readTarball, 'function', 'readTarball must be a function')

  const startedAt = clock()
  const tarballRequestBudget = { requests: 0 }
  let lastObservation = 'the version is not visible'
  while (true) {
    let published
    try {
      published = await readVersion()
      if (!published) lastObservation = 'the version is not visible'
    } catch (error) {
      lastObservation = error instanceof Error ? error.message : String(error)
    }

    if (published) {
      const publishedIntegrity = published.dist?.integrity
      if (publishedIntegrity !== integrity) {
        assert.equal(publishedIntegrity, acceptedIntegrity,
          `${name}@${version} is published with different bytes; use a new version or record the exact earlier publication for recovery`)
      }
      try {
        const distTags = await readDistTags()
        if (distTags?.[distTag] === version) {
          const tarballUrl = published.dist?.tarball
          if (!tarballUrl) {
            lastObservation = 'the version metadata has no tarball URL'
          } else {
            const remainingMs = timeoutMs - Math.max(0, clock() - startedAt)
            if (remainingMs <= 0) {
              lastObservation = `tarball verification did not start before the ${timeoutMs} ms deadline`
            } else if (await readTarball(
              tarballUrl,
              publishedIntegrity,
              remainingMs,
              tarballRequestBudget,
            )) {
              return publishedIntegrity
            } else {
              lastObservation = `tarball is not fully downloadable with published integrity: ${tarballUrl}`
              if (tarballRequestBudget.requests >= MAX_TARBALL_REQUESTS) {
                throw new TarballRequestCapacityError(tarballUrl)
              }
            }
          }
        } else {
          lastObservation = `dist-tag ${distTag} points to ${distTags?.[distTag] ?? 'nothing'}`
        }
      } catch (error) {
        if (error instanceof TarballRequestCapacityError) throw error
        lastObservation = error instanceof Error ? error.message : String(error)
      }
    }

    const elapsedMs = Math.max(0, clock() - startedAt)
    if (elapsedMs >= timeoutMs) {
      throw new Error(`${name}@${version} was not consistently exposed by npm within ${timeoutMs} ms: ${lastObservation}`)
    }
    await wait(Math.min(retryDelayMs, timeoutMs - elapsedMs))
  }
}

function packageManifest(tarball) {
  const archive = gunzipSync(readFileSync(tarball))
  for (let offset = 0; offset + 512 <= archive.length;) {
    const header = archive.subarray(offset, offset + 512)
    if (header.every(byte => byte === 0)) break
    const name = tarString(header, 0, 100)
    const size = tarSize(header)
    const bodyStart = offset + 512
    if (name === 'package/package.json') return JSON.parse(archive.subarray(bodyStart, bodyStart + size).toString('utf8'))
    offset = bodyStart + Math.ceil(size / 512) * 512
  }
  throw new Error(`${tarball} does not contain package/package.json`)
}

function tarString(buffer, start, length) {
  return buffer.subarray(start, start + length).toString('utf8').replace(/\0.*$/, '')
}

function tarSize(header) {
  const raw = tarString(header, 124, 12).trim()
  return raw === '' ? 0 : Number.parseInt(raw, 8)
}

function tarballIntegrity(tarball) {
  return `sha512-${createHash('sha512').update(readFileSync(tarball)).digest('base64')}`
}

async function packageVersion(registry, name, version) {
  return fetchJson(`${registry}/${encodeURIComponent(name)}/${encodeURIComponent(version)}`, true)
}

async function packageDistTags(registry, name) {
  return fetchJson(`${registry}/-/package/${encodeURIComponent(name)}/dist-tags`, true)
}

export async function packageTarballMatches(
  registry,
  tarballUrl,
  expectedIntegrity,
  timeoutMs = 2 * 60 * 1000,
  {
    clock = Date.now,
    wait = sleep,
    retryDelayMs = DEFAULT_TARBALL_RETRY_DELAY_MS,
    requestBudget = { requests: 0 },
  } = {},
) {
  assert.ok(Number.isSafeInteger(timeoutMs) && timeoutMs > 0, 'tarball timeout must be a positive integer')
  assert.ok(Number.isSafeInteger(retryDelayMs) && retryDelayMs > 0,
    'tarball retry delay must be a positive integer')
  assert.ok(
    requestBudget && typeof requestBudget === 'object'
      && Number.isSafeInteger(requestBudget.requests) && requestBudget.requests >= 0,
    'tarball request budget must track a non-negative safe integer',
  )
  const registryOrigin = new URL(registry).origin
  const parsedTarballUrl = new URL(tarballUrl)
  assert.equal(parsedTarballUrl.origin, registryOrigin,
    `npm tarball URL must use the configured registry origin: ${tarballUrl}`)
  const deadline = clock() + timeoutMs
  let digest = createHash('sha512')
  let offset = 0
  let totalSize
  let lastError

  for (let attempt = 0; attempt < MAX_TARBALL_REQUESTS; attempt++) {
    if (requestBudget.requests >= MAX_TARBALL_REQUESTS) {
      throw new TarballRequestCapacityError(tarballUrl, lastError)
    }
    const remainingMs = deadline - clock()
    if (remainingMs <= 0) throw lastError ?? new Error(`npm tarball download timed out: ${tarballUrl}`)
    requestBudget.requests++
    const requestOffset = offset
    const headers = {
      accept: 'application/octet-stream',
      'accept-encoding': 'identity',
      'cache-control': 'no-cache',
    }
    if (requestOffset > 0) headers.range = `bytes=${requestOffset}-`

    let response
    try {
      response = await fetch(parsedTarballUrl, {
        cache: 'no-store',
        headers,
        signal: AbortSignal.timeout(Math.max(1, remainingMs)),
      })
    } catch (error) {
      lastError = error
      await waitForTarballRetry({ deadline, clock, wait, retryDelayMs, requestBudget, tarballUrl, error })
      continue
    }
    assert.equal(new URL(response.url).origin, registryOrigin,
      `npm tarball redirect must stay on the configured registry origin: ${response.url}`)
    if (!response.body) return false

    if (requestOffset === 0) {
      if (response.status !== 200) return false
      totalSize = contentLength(response)
    } else if (response.status === 200) {
      // A registry or CDN may ignore Range. Restart the digest so a full 200 response can still
      // be validated without ever combining overlapping bytes.
      digest = createHash('sha512')
      offset = 0
      totalSize = contentLength(response)
    } else if (response.status === 206) {
      const range = parsedContentRange(response.headers.get('content-range'))
      if (!range || range.start !== requestOffset || range.end >= range.total) return false
      const length = contentLength(response)
      if (length !== undefined && length !== range.end - range.start + 1) return false
      if (totalSize !== undefined && totalSize !== range.total) return false
      totalSize = range.total
    } else {
      return false
    }

    const beforeRead = offset
    try {
      for await (const chunk of response.body) {
        digest.update(chunk)
        offset += chunk.length
        if (totalSize !== undefined && offset > totalSize) return false
      }
    } catch (error) {
      lastError = error
      if (offset === beforeRead) {
        await waitForTarballRetry({ deadline, clock, wait, retryDelayMs, requestBudget, tarballUrl, error })
      } else if (clock() >= deadline) {
        throw error
      }
      continue
    }

    if (totalSize !== undefined && offset < totalSize) continue
    if (totalSize !== undefined && offset !== totalSize) return false
    return `sha512-${digest.digest('base64')}` === expectedIntegrity
  }
  throw new TarballRequestCapacityError(tarballUrl, lastError)
}

async function waitForTarballRetry({ deadline, clock, wait, retryDelayMs, requestBudget, tarballUrl, error }) {
  const remainingMs = deadline - clock()
  if (remainingMs <= 0) throw error
  if (requestBudget.requests >= MAX_TARBALL_REQUESTS) {
    throw new TarballRequestCapacityError(tarballUrl, error)
  }
  await wait(Math.min(retryDelayMs, remainingMs))
}

function contentLength(response) {
  const value = response.headers.get('content-length')
  if (value === null || !/^\d+$/.test(value)) return undefined
  const length = Number(value)
  return Number.isSafeInteger(length) ? length : undefined
}

function parsedContentRange(value) {
  const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(value ?? '')
  if (!match) return undefined
  const [, rawStart, rawEnd, rawTotal] = match
  const start = Number(rawStart)
  const end = Number(rawEnd)
  const total = Number(rawTotal)
  if (![start, end, total].every(Number.isSafeInteger) || start < 0 || end < start || total <= 0) return undefined
  return { start, end, total }
}

async function fetchJson(url, allowNotFound) {
  const response = await fetch(url, {
    cache: 'no-store',
    headers: { accept: 'application/json', 'cache-control': 'no-cache' },
  })
  if (allowNotFound && response.status === 404) return undefined
  if (!response.ok) throw new Error(`npm registry request failed (${response.status}): ${url}`)
  return response.json()
}

function durationFromEnvironment(name, fallback) {
  const value = process.env[name]
  if (value === undefined || value === '') return fallback
  const duration = Number(value)
  assert.ok(Number.isSafeInteger(duration) && duration > 0, `${name} must be a positive integer`)
  return duration
}

function acceptedExistingIntegritiesFromEnvironment() {
  const value = process.env.TERMESTRA_NPM_ACCEPTED_EXISTING_INTEGRITIES
  if (value === undefined || value === '') return {}
  const entries = JSON.parse(value)
  assert.ok(entries && typeof entries === 'object' && !Array.isArray(entries),
    'TERMESTRA_NPM_ACCEPTED_EXISTING_INTEGRITIES must be a JSON object')
  for (const [packageVersion, integrity] of Object.entries(entries)) {
    assert.match(packageVersion, /^@[^/]+\/.+@\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/,
      `Invalid accepted npm package version: ${packageVersion}`)
    assert.match(integrity, /^sha512-[A-Za-z0-9+/]+={0,2}$/,
      `Invalid accepted npm integrity for ${packageVersion}`)
  }
  return entries
}
