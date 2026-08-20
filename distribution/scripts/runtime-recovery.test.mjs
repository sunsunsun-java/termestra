import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { gzipSync } from 'node:zlib'

import {
  assertSafeRuntimeArchive,
  downloadWithResume,
} from '../npm/cli/bin/runtime-recovery.mjs'

test('continues resumable recovery after more than 24 interrupted downloads with progress', () => {
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-runtime-download-test-'))
  const archive = join(workspace, 'runtime.tgz')
  let attempts = 0
  const delays = []
  try {
    assert.doesNotThrow(() => downloadWithResume('https://registry.npmjs.org/runtime.tgz', archive, {
      runCurl: () => {
        attempts += 1
        writeFileSync(archive, Buffer.alloc(attempts))
        return attempts === 25
          ? { status: 0, stderr: '' }
          : { status: 18, stderr: 'curl: (18) transfer closed with outstanding read data remaining' }
      },
      sleep: milliseconds => delays.push(milliseconds),
    }))
    assert.equal(attempts, 25)
    assert.deepEqual(delays, [], 'forward progress should resume immediately')
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
})

test('backs off after interrupted downloads that make no progress', () => {
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-runtime-download-test-'))
  const archive = join(workspace, 'runtime.tgz')
  let attempts = 0
  let clock = 0
  const delays = []
  try {
    downloadWithResume('https://registry.npmjs.org/runtime.tgz', archive, {
      runCurl: () => {
        attempts += 1
        if (attempts === 3) writeFileSync(archive, 'complete')
        return attempts === 3
          ? { status: 0, stderr: '' }
          : { status: 35, stderr: 'curl: (35) SSL_ERROR_SYSCALL' }
      },
      now: () => clock,
      sleep: milliseconds => {
        delays.push(milliseconds)
        clock += milliseconds
      },
    })
    assert.deepEqual(delays, [15_000, 15_000])
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
})

test('bounds interrupted downloads by one overall deadline', () => {
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-runtime-download-test-'))
  const archive = join(workspace, 'runtime.tgz')
  let requests = 0
  let clock = 0
  try {
    assert.throws(() => downloadWithResume('https://registry.npmjs.org/runtime.tgz', archive, {
      runCurl: () => {
        requests += 1
        return { status: 35, stderr: 'curl: (35) SSL_ERROR_SYSCALL' }
      },
      now: () => clock,
      sleep: milliseconds => { clock += milliseconds },
    }), /limit: 96 requests or 10 minutes/)
    assert.equal(requests, 40)
    assert.equal(clock, 10 * 60 * 1000)
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
})

test('bounds rapid interrupted downloads that keep making progress', () => {
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-runtime-download-test-'))
  const archive = join(workspace, 'runtime.tgz')
  let requests = 0
  try {
    assert.throws(() => downloadWithResume('https://registry.npmjs.org/runtime.tgz', archive, {
      runCurl: () => {
        requests += 1
        writeFileSync(archive, Buffer.alloc(requests))
        return { status: 18, stderr: 'curl: (18) transfer interrupted' }
      },
      sleep: () => assert.fail('forward progress must not be delayed'),
    }), /failed after 96 resumable requests/)
    assert.equal(requests, 96)
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
})

test('shrinks the curl timeout to the remaining overall deadline', () => {
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-runtime-download-test-'))
  const archive = join(workspace, 'runtime.tgz')
  let clock = 0
  const requestTimeouts = []
  try {
    assert.throws(() => downloadWithResume('https://registry.npmjs.org/runtime.tgz', archive, {
      runCurl: (_url, _archive, timeout) => {
        requestTimeouts.push(timeout)
        clock += timeout
        writeFileSync(archive, Buffer.alloc(requestTimeouts.length))
        return { status: 18, stderr: 'curl: (18) transfer interrupted' }
      },
      now: () => clock,
      sleep: () => assert.fail('forward progress must not be delayed'),
    }), /10 minutes/)
    assert.deepEqual(requestTimeouts, [180_000, 180_000, 180_000, 60_000])
    assert.equal(clock, 10 * 60 * 1000)
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
})

test('passes an integer curl timeout when the monotonic clock has fractional milliseconds', () => {
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-runtime-download-test-'))
  const archive = join(workspace, 'runtime.tgz')
  let clock = 0
  let requestTimeout
  try {
    downloadWithResume('https://registry.npmjs.org/runtime.tgz', archive, {
      runCurl: (_url, _archive, timeout) => {
        requestTimeout = timeout
        writeFileSync(archive, 'complete')
        return { status: 0, stderr: '' }
      },
      now: () => {
        clock += 0.25
        return clock
      },
    })
    assert.equal(requestTimeout, 180_000)
    assert.ok(Number.isInteger(requestTimeout))
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
})

test('accepts regular files and directories rooted below package/', () => {
  withArchive([
    { name: 'package/', type: '5' },
    { name: 'package/runtime/bin/java', body: 'java' },
  ], archive => assert.doesNotThrow(() => assertSafeRuntimeArchive(archive)))
})

test('rejects a compressed runtime tarball above its byte capacity', () => {
  withArchive([{ name: 'package/file', body: 'runtime' }], archive => {
    assert.throws(
      () => assertSafeRuntimeArchive(archive, { maxTarballBytes: 1 }),
      /runtime tarball exceeds 1 bytes/,
    )
  })
})

test('rejects a runtime tarball whose expanded archive exceeds its byte capacity', () => {
  withArchive([{ name: 'package/file', body: 'runtime' }], archive => {
    assert.throws(
      () => assertSafeRuntimeArchive(archive, { maxArchiveBytes: 512 }),
      /runtime tarball expands beyond 512 bytes/,
    )
  })
})

for (const unsafe of [
  { name: 'package/../escape', type: '0', reason: /unsafe path/ },
  { name: '/absolute', type: '0', reason: /outside package/ },
  { name: 'package/symlink', type: '2', reason: /link or unsupported entry type/ },
  { name: 'package/hardlink', type: '1', reason: /link or unsupported entry type/ },
  { name: 'package/fifo', type: '6', reason: /link or unsupported entry type/ },
]) {
  test(`rejects unsafe runtime archive entry ${unsafe.name} (type ${unsafe.type})`, () => {
    withArchive([unsafe], archive => {
      assert.throws(() => assertSafeRuntimeArchive(archive), unsafe.reason)
    })
  })
}

function withArchive(entries, assertion) {
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-runtime-archive-test-'))
  const archive = join(workspace, 'runtime.tgz')
  try {
    writeFileSync(archive, gzipSync(tar(entries)))
    assertion(archive)
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
}

function tar(entries) {
  const blocks = []
  for (const entry of entries) {
    const body = Buffer.from(entry.body ?? '')
    const header = Buffer.alloc(512)
    writeString(header, 0, 100, entry.name)
    writeOctal(header, 100, 8, entry.type === '5' ? 0o755 : 0o644)
    writeOctal(header, 108, 8, 0)
    writeOctal(header, 116, 8, 0)
    writeOctal(header, 124, 12, body.length)
    writeOctal(header, 136, 12, 0)
    header.fill(0x20, 148, 156)
    header[156] = (entry.type ?? '0').charCodeAt(0)
    writeString(header, 257, 6, 'ustar')
    writeString(header, 263, 2, '00')
    writeOctal(header, 148, 8, header.reduce((sum, byte) => sum + byte, 0))
    blocks.push(header, body, Buffer.alloc((512 - (body.length % 512)) % 512))
  }
  blocks.push(Buffer.alloc(1024))
  return Buffer.concat(blocks)
}

function writeString(buffer, offset, length, value) {
  const encoded = Buffer.from(value)
  assert.ok(encoded.length <= length, `test tar value is too long: ${value}`)
  encoded.copy(buffer, offset)
}

function writeOctal(buffer, offset, length, value) {
  const encoded = value.toString(8).padStart(length - 2, '0') + '\0 '
  buffer.write(encoded, offset, length, 'ascii')
}
