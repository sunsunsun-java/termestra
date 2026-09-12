import assert from 'node:assert/strict'
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { createServer } from 'node:http'
import { createHash } from 'node:crypto'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath, pathToFileURL } from 'node:url'
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
    writeOctal(header, 100, 8, entry.mode ?? (entry.type === '5' ? 0o755 : 0o644))
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


test('recovers an interrupted runtime when the server ignores Range, retaining integrity checks', async () => {
  const packageName = '@termestra/runtime-darwin-arm64'
  const version = '1.0.0'
  const manifest = { name: packageName, version, os: ['darwin'], cpu: ['arm64'] }
  const bytes = gzipSync(tar([
    { name: 'package/package.json', body: JSON.stringify(manifest) },
    { name: 'package/runtime/bin/java', body: '#!/bin/sh\nexit 0\n', mode: 0o755 },
    { name: 'package/app/termestra.jar', body: 'application fixture' },
  ]))
  const cutoff = Math.floor(bytes.length / 2)
  const ranges = []
  let integrity = `sha512-${createHash('sha512').update(bytes).digest('base64')}`
  let registry
  const server = createServer((request, response) => {
    if (request.url !== '/runtime.tgz') {
      response.writeHead(200, { 'content-type': 'application/json' })
      response.end(JSON.stringify({ ...manifest, dist: { tarball: `${registry}/runtime.tgz`, integrity } }))
      return
    }
    ranges.push(request.headers.range)
    response.writeHead(200, { 'content-length': bytes.length })
    if (ranges.length === 1) {
      response.write(bytes.subarray(0, cutoff))
      setTimeout(() => response.destroy(), 20)
    } else response.end(bytes)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  registry = `http://127.0.0.1:${server.address().port}`
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-runtime-range-test-'))
  const module = new URL('../npm/cli/bin/runtime-recovery.mjs', import.meta.url).href
  const recover = () => promisify(execFile)(process.execPath, ['--input-type=module', '--eval',
    `import { recoverRuntimePackage } from ${JSON.stringify(module)};
     await recoverRuntimePackage(${JSON.stringify({ packageName, version, platform: 'darwin', architecture: 'arm64', cliRoot: workspace, registry })});`,
  ], { timeout: 5000, killSignal: 'SIGKILL' })
  try {
    await recover()
    assert.deepEqual(ranges, [undefined, `bytes=${cutoff}-`, undefined])
    assert.equal(readFileSync(join(workspace, '.runtime', 'runtime-darwin-arm64', 'app', 'termestra.jar'), 'utf8'), 'application fixture')
    ranges.length = 0
    integrity = `sha512-${Buffer.alloc(64).toString('base64')}`
    await assert.rejects(recover(), /does not match npm registry integrity/)
    assert.deepEqual(ranges, [undefined, `bytes=${cutoff}-`, undefined])
    assert.equal(readFileSync(join(workspace, '.runtime', 'runtime-darwin-arm64', 'app', 'termestra.jar'), 'utf8'), 'application fixture')
  } finally {
    server.closeAllConnections()
    await new Promise(resolve => server.close(resolve))
    rmSync(workspace, { recursive: true, force: true })
  }
})

test('range fallback retains the shared request capacity', () => {
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-runtime-range-capacity-'))
  const archive = join(workspace, 'runtime.tgz')
  let requests = 0
  try {
    assert.throws(() => downloadWithResume('https://registry.npmjs.org/runtime.tgz', archive, {
      runCurl: () => {
        requests++
        if (requests % 2) writeFileSync(archive, 'partial')
        return { status: requests % 2 ? 18 : 33, stderr: 'interrupted or unsupported range' }
      },
      now: () => 0,
      sleep: () => {},
    }), /failed after 96 resumable requests/)
    assert.equal(requests, 96)
  } finally { rmSync(workspace, { recursive: true, force: true }) }
})


test('CLI rejects malformed ports before starting the runtime', async () => {
  const launcher = new URL('../npm/cli/bin/termestra.mjs', import.meta.url)
  for (const value of ['3000junk', '3.5', '0x1000', '3e3', '+3000', ' 3000', '-1', '65536', '']) {
    await assert.rejects(promisify(execFile)(process.execPath, [fileURLToPath(launcher), '--port', value]), error => {
      assert.equal(error.code, 1)
      assert.equal(error.stdout, '')
      assert.match(error.stderr, value ? /^Invalid port:/ : /^Usage: termestra/)
      return true
    })
  }
})


test('CLI forwards valid decimal port boundaries unchanged', async () => {
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-cli-port-'))
  try {
    const cli = join(workspace, 'cli')
    cpSync(fileURLToPath(new URL('../npm/cli', import.meta.url)), cli, { recursive: true })
    const bin = join(workspace, 'runtime-current', 'runtime', 'bin')
    mkdirSync(bin, { recursive: true })
    writeFileSync(join(bin, 'java'), '#!/bin/sh\nprintf "%s\\n" "$@"\n', { mode: 0o755 })
    const launcher = join(cli, 'bin', 'termestra.mjs')
    for (const value of ['0', '3000', '65535', '003000']) {
      const { stdout, stderr } = await promisify(execFile)(process.execPath, ['--input-type=module', '--eval',
        `Object.defineProperty(process, 'platform', { value: 'darwin' });
         Object.defineProperty(process, 'arch', { value: 'arm64' });
         process.argv = [process.execPath, ${JSON.stringify(launcher)}, '--port', ${JSON.stringify(value)}];
         await import(${JSON.stringify(pathToFileURL(launcher).href)});`,
      ])
      assert.equal(stderr, '')
      assert.equal(stdout.trim().split('\n').at(-1), `--server.port=${Number(value)}`)
    }
  } finally { rmSync(workspace, { recursive: true, force: true }) }
})
