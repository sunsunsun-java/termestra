#!/usr/bin/env node
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'
import { pathToFileURL } from 'node:url'
import { gunzipSync } from 'node:zlib'

const DEFAULT_VERIFICATION_TIMEOUT_MS = 5 * 60 * 1000
const DEFAULT_VERIFICATION_RETRY_DELAY_MS = 5 * 1000

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
  timeoutMs = DEFAULT_VERIFICATION_TIMEOUT_MS,
  retryDelayMs = DEFAULT_VERIFICATION_RETRY_DELAY_MS,
  clock = Date.now,
  wait = sleep,
}) {
  assert.ok(Number.isSafeInteger(timeoutMs) && timeoutMs > 0, 'verification timeout must be a positive integer')
  assert.ok(Number.isSafeInteger(retryDelayMs) && retryDelayMs > 0, 'verification retry delay must be a positive integer')

  const startedAt = clock()
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
        if (distTags?.[distTag] === version) return publishedIntegrity
        lastObservation = `dist-tag ${distTag} points to ${distTags?.[distTag] ?? 'nothing'}`
      } catch (error) {
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
