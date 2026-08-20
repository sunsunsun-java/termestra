import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { gzipSync } from 'node:zlib'

import { assertSafeRuntimeArchive } from '../npm/cli/bin/runtime-recovery.mjs'

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
