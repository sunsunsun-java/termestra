import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
} from 'node:fs'
import { basename, join } from 'node:path'
import { performance } from 'node:perf_hooks'
import { gunzipSync } from 'node:zlib'

const MAX_DOWNLOAD_REQUESTS = 96
const DOWNLOAD_DEADLINE_MS = 10 * 60 * 1000
const DOWNLOAD_REQUEST_TIMEOUT_MS = 3 * 60 * 1000
const DOWNLOAD_RETRY_DELAY_MS = 15 * 1000
const MAX_RUNTIME_TARBALL_BYTES = 75_000_000
const MAX_RUNTIME_ARCHIVE_BYTES = 128_000_000

export async function recoverRuntimePackage({
  packageName,
  version,
  platform,
  architecture,
  cliRoot,
  registry = configuredRegistry(),
}) {
  assert.match(packageName, /^@termestra\/runtime-darwin-(?:arm64|x64)$/)
  assert.match(version, /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/)
  const normalizedRegistry = registry.replace(/\/+$/, '')
  const metadata = await readPackageVersion(normalizedRegistry, packageName, version)
  assert.equal(metadata.name, packageName, 'runtime registry metadata has the wrong package name')
  assert.equal(metadata.version, version, 'runtime registry metadata has the wrong version')
  assert.ok(metadata.os?.includes(platform), 'runtime registry metadata does not support this operating system')
  assert.ok(metadata.cpu?.includes(architecture), 'runtime registry metadata does not support this architecture')

  const tarballUrl = new URL(metadata.dist?.tarball)
  assertSafeTarballUrl(tarballUrl)
  const expectedIntegrity = metadata.dist?.integrity
  assert.match(expectedIntegrity ?? '', /^sha512-[A-Za-z0-9+/]+={0,2}$/,
    'runtime registry metadata has no valid SHA-512 integrity')

  const packageDirectoryName = basename(packageName)
  const targetParent = join(cliRoot, '.runtime')
  const target = join(targetParent, packageDirectoryName)
  mkdirSync(targetParent, { recursive: true })
  const workspace = mkdtempSync(join(targetParent, '.runtime-recovery-'))
  const archive = join(workspace, `${packageDirectoryName}-${version}.tgz`)
  try {
    downloadWithResume(tarballUrl.href, archive)
    assertRuntimeArchiveSize(archive)
    assert.equal(fileIntegrity(archive), expectedIntegrity,
      'downloaded runtime tarball does not match npm registry integrity')
    extractPackage(archive, workspace)
    const extracted = join(workspace, 'package')
    const extractedManifest = JSON.parse(readFileSync(join(extracted, 'package.json'), 'utf8'))
    assert.equal(extractedManifest.name, packageName, 'downloaded runtime has the wrong package name')
    assert.equal(extractedManifest.version, version, 'downloaded runtime has the wrong version')
    const java = join(extracted, 'runtime', 'bin', 'java')
    assert.ok(existsSync(java) && (statSync(java).mode & 0o111),
      'downloaded runtime has no executable Java launcher')
    assert.ok(existsSync(join(extracted, 'app', 'termestra.jar')),
      'downloaded runtime has no Termestra application jar')

    rmSync(target, { recursive: true, force: true })
    renameSync(extracted, target)
    return target
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
}

function configuredRegistry() {
  return process.env['npm_config_@termestra:registry']
    ?? process.env.npm_config_registry
    ?? 'https://registry.npmjs.org'
}

async function readPackageVersion(registry, packageName, version) {
  const response = await fetch(
    `${registry}/${encodeURIComponent(packageName)}/${encodeURIComponent(version)}`,
    {
      cache: 'no-store',
      headers: { accept: 'application/json', 'cache-control': 'no-cache' },
      signal: AbortSignal.timeout(30_000),
    },
  )
  if (!response.ok) {
    throw new Error(`runtime metadata request failed (${response.status})`)
  }
  return response.json()
}

function assertSafeTarballUrl(url) {
  const loopback = url.hostname === '127.0.0.1' || url.hostname === 'localhost' || url.hostname === '::1'
  assert.ok(url.protocol === 'https:' || (url.protocol === 'http:' && loopback),
    `runtime tarball URL must use HTTPS: ${url.href}`)
}

export function downloadWithResume(url, archive, {
  runCurl = curlDownload,
  now = monotonicNow,
  sleep = sleepSync,
} = {}) {
  const startedAt = now()
  const deadline = startedAt + DOWNLOAD_DEADLINE_MS
  let lastFailure = 'download did not start'
  let requests = 0
  while (requests < MAX_DOWNLOAD_REQUESTS) {
    const remaining = Math.floor(deadline - now())
    if (remaining <= 0) break
    const sizeBefore = archiveSize(archive)
    const requestTimeout = Math.min(DOWNLOAD_REQUEST_TIMEOUT_MS, remaining)
    requests += 1
    const result = runCurl(url, archive, requestTimeout)
    if (existsSync(archive) && statSync(archive).size > MAX_RUNTIME_TARBALL_BYTES) {
      throw new Error(`runtime tarball exceeds ${MAX_RUNTIME_TARBALL_BYTES} bytes`)
    }
    if (result.status === 0) return
    lastFailure = result.error?.message || result.stderr?.trim() || `curl exited with ${result.status}`
    const retryWindow = Math.floor(deadline - now())
    if (archiveSize(archive) <= sizeBefore && requests < MAX_DOWNLOAD_REQUESTS && retryWindow > 0) {
      sleep(Math.min(DOWNLOAD_RETRY_DELAY_MS, retryWindow))
    }
  }
  throw new Error(
    `runtime download failed after ${requests} resumable requests `
    + `(limit: ${MAX_DOWNLOAD_REQUESTS} requests or ${DOWNLOAD_DEADLINE_MS / 60_000} minutes): ${lastFailure}`,
  )
}

function curlDownload(url, archive, timeout) {
  return spawnSync('/usr/bin/curl', [
    '--continue-at', '-',
    '--fail',
    '--location',
    '--max-filesize', String(MAX_RUNTIME_TARBALL_BYTES),
    '--output', archive,
    '--silent',
    '--show-error',
    url,
  ], {
    encoding: 'utf8',
    stdio: ['ignore', 'ignore', 'pipe'],
    timeout,
  })
}

function archiveSize(archive) {
  return existsSync(archive) ? statSync(archive).size : 0
}

function sleepSync(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds)
}

function monotonicNow() {
  return performance.now()
}

function fileIntegrity(path) {
  return `sha512-${createHash('sha512').update(readFileSync(path)).digest('base64')}`
}

function extractPackage(archive, workspace) {
  assertSafeRuntimeArchive(archive)
  const extraction = spawnSync('/usr/bin/tar', ['-xzf', archive, '-C', workspace], { encoding: 'utf8' })
  assert.ifError(extraction.error)
  assert.equal(extraction.status, 0, `could not extract runtime tarball: ${extraction.stderr}`)
}

export function assertSafeRuntimeArchive(archive, {
  maxTarballBytes = MAX_RUNTIME_TARBALL_BYTES,
  maxArchiveBytes = MAX_RUNTIME_ARCHIVE_BYTES,
} = {}) {
  assertCapacity(maxTarballBytes, 'runtime tarball capacity')
  assertCapacity(maxArchiveBytes, 'runtime archive capacity')
  assertRuntimeArchiveSize(archive, maxTarballBytes)
  let tar
  try {
    tar = gunzipSync(readFileSync(archive), { maxOutputLength: maxArchiveBytes })
  } catch (error) {
    if (error?.code === 'ERR_BUFFER_TOO_LARGE') {
      throw new Error(`runtime tarball expands beyond ${maxArchiveBytes} bytes`, { cause: error })
    }
    throw error
  }
  let entries = 0
  for (let offset = 0; offset + 512 <= tar.length;) {
    const header = tar.subarray(offset, offset + 512)
    if (header.every(byte => byte === 0)) break

    const name = tarString(header, 0, 100)
    const prefix = tarString(header, 345, 155)
    const fullName = prefix ? `${prefix}/${name}` : name
    const type = String.fromCharCode(header[156] || 0)
    assert.ok(type === '\0' || type === '0' || type === '5',
      `runtime tarball contains a link or unsupported entry type: ${fullName}`)
    assert.ok(fullName === 'package' || fullName.startsWith('package/'),
      `runtime tarball contains a path outside package/: ${fullName}`)
    assert.ok(!fullName.startsWith('/') && !fullName.split('/').includes('..'),
      `runtime tarball contains an unsafe path: ${fullName}`)

    const rawSize = tarString(header, 124, 12).trim()
    assert.match(rawSize, /^[0-7]*$/, `runtime tarball contains an invalid size: ${fullName}`)
    const size = rawSize === '' ? 0 : Number.parseInt(rawSize, 8)
    assert.ok(Number.isSafeInteger(size), `runtime tarball entry is too large: ${fullName}`)
    const nextOffset = offset + 512 + Math.ceil(size / 512) * 512
    assert.ok(nextOffset <= tar.length, `runtime tarball entry is truncated: ${fullName}`)
    offset = nextOffset
    entries += 1
  }
  assert.ok(entries > 0, 'runtime tarball is empty')
}

function assertRuntimeArchiveSize(archive, maxBytes = MAX_RUNTIME_TARBALL_BYTES) {
  assert.ok(statSync(archive).size <= maxBytes,
    `runtime tarball exceeds ${maxBytes} bytes`)
}

function assertCapacity(value, name) {
  assert.ok(Number.isSafeInteger(value) && value > 0, `${name} must be a positive integer`)
}

function tarString(buffer, start, length) {
  return buffer.subarray(start, start + length).toString('utf8').replace(/\0.*$/, '')
}
