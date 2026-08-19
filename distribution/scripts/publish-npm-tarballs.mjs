#!/usr/bin/env node
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { gunzipSync } from 'node:zlib'

const [distTag, ...tarballs] = process.argv.slice(2)
if (!distTag || tarballs.length === 0) {
  console.error('Usage: node publish-npm-tarballs.mjs <dist-tag> <package.tgz>...')
  process.exit(2)
}

assert.match(distTag, /^[a-z][a-z0-9._-]*$/, `Invalid npm dist-tag: ${distTag}`)
const registry = (process.env.TERMESTRA_NPM_REGISTRY ?? 'https://registry.npmjs.org').replace(/\/+$/, '')
const npmCommand = process.platform === 'win32' ? process.env.ComSpec ?? 'cmd.exe' : 'npm'
const withProvenance = process.env.TERMESTRA_NPM_PUBLISH_MODE === 'bootstrap-token'

for (const tarball of tarballs) {
  const manifest = packageManifest(tarball)
  assert.equal(manifest.publishConfig?.access, 'public', `${tarball} must declare public npm access`)
  assert.equal(manifest.publishConfig?.registry, registry, `${tarball} must declare ${registry} as its publish registry`)
  assert.ok(manifest.repository?.url, `${tarball} must declare repository.url for provenance`)
  const integrity = tarballIntegrity(tarball)
  const existing = await packageVersion(manifest.name, manifest.version)

  if (existing) {
    assert.equal(existing.dist?.integrity, integrity, `${manifest.name}@${manifest.version} is already published with different bytes; use a new version`)
    const packageDocument = await packageMetadata(manifest.name)
    assert.equal(packageDocument['dist-tags']?.[distTag], manifest.version, `${manifest.name}@${manifest.version} exists but is not tagged ${distTag}; do not change tags with this release workflow`)
    console.log(`Verified already-published ${manifest.name}@${manifest.version}`)
    continue
  }

  const arguments_ = ['publish', tarball, '--access', 'public', '--tag', distTag]
  if (withProvenance) arguments_.push('--provenance')
  const npmArguments = process.platform === 'win32'
    ? ['/d', '/s', '/c', 'npm.cmd', ...arguments_]
    : arguments_
  const result = spawnSync(npmCommand, npmArguments, { stdio: 'inherit', env: process.env })
  assert.ifError(result.error)
  assert.equal(result.status, 0, `npm publish failed for ${tarball}`)

  const published = await packageVersion(manifest.name, manifest.version)
  assert.ok(published, `npm did not expose ${manifest.name}@${manifest.version} after publishing`)
  assert.equal(published.dist?.integrity, integrity, `${manifest.name}@${manifest.version} registry integrity differs from the release tarball`)
  const packageDocument = await packageMetadata(manifest.name)
  assert.equal(packageDocument['dist-tags']?.[distTag], manifest.version, `${manifest.name}@${manifest.version} was not tagged ${distTag}`)
  console.log(`Published ${manifest.name}@${manifest.version}`)
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

async function packageVersion(name, version) {
  return fetchJson(`${registry}/${encodeURIComponent(name)}/${encodeURIComponent(version)}`, true)
}

async function packageMetadata(name) {
  return fetchJson(`${registry}/${encodeURIComponent(name)}`, false)
}

async function fetchJson(url, allowNotFound) {
  const response = await fetch(url, { headers: { accept: 'application/json' } })
  if (allowNotFound && response.status === 404) return undefined
  if (!response.ok) throw new Error(`npm registry request failed (${response.status}): ${url}`)
  return response.json()
}
