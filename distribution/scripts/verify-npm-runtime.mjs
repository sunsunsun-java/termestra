#!/usr/bin/env node
import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath, pathToFileURL } from 'node:url'

const CHECK_OPTIONS = { encoding: 'utf8', timeout: 15_000, maxBuffer: 1024 * 1024, killSignal: 'SIGKILL' }
const REPOSITORY_URL = 'git+https://github.com/sunsunsun-java/termestra.git'
const HOMEPAGE = 'https://github.com/sunsunsun-java/termestra#readme'
const BUGS_URL = 'https://github.com/sunsunsun-java/termestra/issues'
const REGISTRY = 'https://registry.npmjs.org'
const RUNTIME_PLATFORMS = [
  'darwin-arm64',
  'darwin-x64',
]

const target = process.argv[2]
if (!target) {
  console.error('Usage: node verify-npm-runtime.mjs <distribution-target>')
  process.exit(2)
}

const platform = `${process.platform}-${process.arch}`
const runtime = join(target, 'npm', `runtime-${platform}`)
const cli = join(target, 'npm-cli')
const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const readmeNames = ['README.md', 'README.zh-CN.md']
const localReadmeImages = markdown => [...markdown.matchAll(/<img\s+[^>]*src=["']([^"']+)["']/g)]
  .map(match => match[1])
  .filter(source => !source.startsWith('http://') && !source.startsWith('https://'))
  .map(source => source.replace(/^\.\//, ''))
const readJson = path => JSON.parse(readFileSync(path, 'utf8'))

function assertPublicationMetadata(manifest, name) {
  assert.equal(manifest.name, name)
  assert.deepEqual(manifest.repository, { type: 'git', url: REPOSITORY_URL })
  assert.equal(manifest.homepage, HOMEPAGE)
  assert.deepEqual(manifest.bugs, { url: BUGS_URL })
  assert.deepEqual(manifest.publishConfig, { access: 'public', registry: REGISTRY })
}

const manifest = readJson(join(runtime, 'package.json'))
const cliManifest = readJson(join(cli, 'package.json'))

assert.equal(process.platform, 'darwin', 'Termestra runtime packages are supported only on macOS')
assertPublicationMetadata(manifest, `@termestra/runtime-${platform}`)
assert.equal(manifest.private, false)
assert.match(manifest.description, /^Termestra embedded Java runtime for /)
assert.deepEqual(manifest.os, [process.platform])
assert.deepEqual(manifest.cpu, [process.arch])
assert.equal(manifest.libc, undefined)

assertPublicationMetadata(cliManifest, '@termestra/cli')
assert.equal(cliManifest.preferGlobal, true)
assert.deepEqual(cliManifest.engines, { node: '>=20' })
assert.deepEqual(cliManifest.scripts, { postinstall: 'node bin/postinstall.mjs' })
assert.deepEqual(Object.keys(cliManifest.optionalDependencies).sort(), RUNTIME_PLATFORMS.map(platformName => `@termestra/runtime-${platformName}`).sort())
for (const version of Object.values(cliManifest.optionalDependencies)) assert.equal(version, manifest.version)
for (const entry of ['bin/', 'docs/', 'frontend/web/public/screenshots/']) {
  assert.ok(cliManifest.files.includes(entry), `npm CLI files allowlist is missing: ${entry}`)
}

const applicationJar = join(runtime, 'app', 'termestra.jar')
assert.ok(existsSync(applicationJar), 'application jar is missing')
assertEmbeddedApplicationVersion(applicationJar, manifest.version)
assertMacOnlyApplicationJar(applicationJar, process.arch)
assert.ok(existsSync(join(runtime, 'LICENSE.BSL')), 'runtime source license is missing')
assert.ok(existsSync(join(runtime, 'LICENSE')), 'runtime historical license notice is missing')
assert.ok(existsSync(join(runtime, 'NOTICE')), 'runtime NOTICE is missing')
assert.ok(existsSync(join(runtime, 'TRADEMARK.md')), 'runtime trademark notice is missing')
assert.ok(existsSync(join(runtime, 'THIRD_PARTY_NOTICES.md')), 'runtime third-party notices are missing')

const java = join(runtime, 'runtime', 'bin', 'java')
assert.ok(existsSync(java), 'embedded Java launcher is missing')
const javaResult = spawnSync(java, ['-version'], CHECK_OPTIONS)
assert.ifError(javaResult.error)
assert.equal(javaResult.status, 0, javaResult.stderr || javaResult.stdout)
const javaMajor = Number((javaResult.stderr || javaResult.stdout).match(/version "(\d+)/)?.[1])
assert.ok(javaMajor >= 21, `embedded Java must support Java 21 class files, found: ${javaResult.stderr || javaResult.stdout}`)

const cliResult = spawnSync(process.execPath, [join(cli, 'bin', 'termestra.mjs'), '--version'], CHECK_OPTIONS)
assert.ifError(cliResult.error)
assert.equal(cliResult.status, 0, cliResult.stderr)
assert.equal(cliResult.stdout.trim(), manifest.version)

const updateHelp = spawnSync(process.execPath, [join(cli, 'bin', 'termestra.mjs'), 'update', '--help'], CHECK_OPTIONS)
assert.ifError(updateHelp.error)
assert.equal(updateHelp.status, 0, updateHelp.stderr)
assert.match(updateHelp.stdout, /Usage: termestra update/)

const launcher = resolve(cli, 'bin', 'termestra.mjs')
const unsupportedPlatform = spawnSync(process.execPath, [
  '--input-type=module',
  '--eval',
  `Object.defineProperty(process, 'platform', { value: 'linux' });`
    + `Object.defineProperty(process, 'arch', { value: 'x64' });`
    + `process.argv = [process.execPath, ${JSON.stringify(launcher)}];`
    + `await import(${JSON.stringify(pathToFileURL(launcher).href)});`,
], CHECK_OPTIONS)
assert.ifError(unsupportedPlatform.error)
assert.equal(unsupportedPlatform.status, 1)
assert.equal(unsupportedPlatform.stdout, '')
assert.equal(unsupportedPlatform.stderr, 'Termestra supports macOS only; detected linux-x64.\n')

const missingRuntime = spawnSync(process.execPath, [join(cli, 'bin', 'postinstall.mjs')], {
  ...CHECK_OPTIONS,
  env: { ...process.env, TERMESTRA_DISABLE_RUNTIME_RECOVERY: '1' },
})
assert.ifError(missingRuntime.error)
assert.equal(missingRuntime.status, 1)
assert.equal(missingRuntime.stdout, '')
assert.equal(
  missingRuntime.stderr,
  `Termestra runtime package @termestra/runtime-${platform} is missing. npm may have skipped a failed optional download; retry: npm install -g @termestra/cli\n`,
)

for (const readmeName of readmeNames) {
  const readmePath = join(cli, readmeName)
  assert.ok(existsSync(readmePath), `npm CLI ${readmeName} is missing`)
  const markdown = readFileSync(readmePath, 'utf8')
  for (const imagePath of localReadmeImages(markdown)) {
    const stagedImagePath = join(cli, imagePath)
    const sourceImagePath = join(repositoryRoot, imagePath)
    assert.ok(existsSync(stagedImagePath), `${readmeName} image is missing: ${imagePath}`)
    assert.ok(existsSync(sourceImagePath), `${readmeName} source image is missing: ${imagePath}`)
    assert.deepEqual(
      readFileSync(stagedImagePath),
      readFileSync(sourceImagePath),
      `${readmeName} image is stale: ${imagePath}`,
    )
  }
}

for (const filename of ['LICENSE.BSL', 'LICENSE', 'NOTICE', 'TRADEMARK.md', 'THIRD_PARTY_NOTICES.md']) {
  assert.ok(existsSync(join(cli, filename)), `npm CLI ${filename} is missing`)
}
assert.ok(existsSync(join(cli, 'frontend', 'web', 'public', 'logo.png')), 'npm CLI README logo is missing')
assert.ok(existsSync(join(cli, 'docs', 'README.md')), 'npm CLI documentation map is missing')
assert.ok(existsSync(join(cli, 'docs', 'architecture', 'overview.md')), 'npm CLI architecture overview is missing')
assert.ok(existsSync(join(cli, 'docs', 'product', 'roadmap.md')), 'npm CLI roadmap is missing')
assert.ok(existsSync(join(cli, 'docs', 'governance', 'licensing-review.md')), 'npm CLI licensing review is missing')
assert.ok(existsSync(join(cli, 'docs', 'governance', 'public-asset-remediation-2026-08-18.md')), 'npm CLI public-asset remediation record is missing')
assert.ok(existsSync(join(cli, 'docs', 'release', 'npm.md')), 'npm CLI release guide is missing')
assert.ok(existsSync(join(cli, 'backend', 'src', 'main', 'resources', 'vendor', 'marketplace', 'en', 'LICENSE')), 'npm CLI marketplace license is missing')
assert.ok(existsSync(join(cli, 'frontend', 'web', 'public', 'sounds', 'LICENSE-KENNEY.txt')), 'npm CLI sound attribution is missing')
for (const retiredDirectory of ['cli-agent-icons', 'open-target-icons']) {
  const sourceDirectory = `frontend/web/public/${retiredDirectory}/`
  assert.ok(!cliManifest.files.includes(sourceDirectory), `npm CLI files allowlist must not include retired asset directory: ${sourceDirectory}`)
  assert.ok(!existsSync(join(cli, 'frontend', 'web', 'public', retiredDirectory)), `npm CLI must not distribute retired asset directory: ${sourceDirectory}`)
}

console.log(`Verified ${manifest.name}@${manifest.version}`)

function assertEmbeddedApplicationVersion(applicationJar, expectedVersion) {
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-version-contract-'))
  const propertiesEntry = 'BOOT-INF/classes/application.properties'
  try {
    const jar = jarExecutable()
    const extraction = spawnSync(
      jar,
      ['xf', resolve(applicationJar), propertiesEntry],
      { ...CHECK_OPTIONS, cwd: workspace },
    )
    assert.ifError(extraction.error)
    assert.equal(extraction.status, 0, extraction.stderr || extraction.stdout)
    const properties = readFileSync(join(workspace, propertiesEntry), 'utf8')
    const packagedVersion = properties.match(/^termestra\.version=(.+)$/m)?.[1]?.trim()
    assert.equal(
      packagedVersion,
      expectedVersion,
      `embedded application version must match npm runtime version ${expectedVersion}`,
    )
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
}

function assertMacOnlyApplicationJar(applicationJar, architecture) {
  const outerEntries = jarEntries(applicationJar)
  assert.deepEqual(
    outerEntries.filter(entry => /^BOOT-INF\/lib\/.*(?:linux|windows|win32|epoll).*\.jar$/i.test(entry)),
    [],
    'macOS application jar contains Linux or Windows runtime dependencies',
  )
  assert.deepEqual(
    outerEntries.filter(entry => /^BOOT-INF\/lib\/netty-codec-(?:http3|native-quic)-.*\.jar$/.test(entry)),
    [],
    'macOS application jar contains unused HTTP/3 or QUIC dependencies',
  )
  const oppositeArchitecture = architecture === 'arm64' ? 'osx-x86_64' : 'osx-aarch_64'
  assert.deepEqual(
    outerEntries.filter(entry => entry.includes(oppositeArchitecture)),
    [],
    `macOS application jar contains the opposite architecture: ${oppositeArchitecture}`,
  )
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-macos-jar-contract-'))
  try {
    for (const pattern of [/^BOOT-INF\/lib\/sqlite-jdbc-.*\.jar$/, /^BOOT-INF\/lib\/pty4j-.*\.jar$/, /^BOOT-INF\/lib\/jna-[0-9].*\.jar$/, /^BOOT-INF\/lib\/jna-platform-.*\.jar$/]) {
      const nestedEntry = outerEntries.find(entry => pattern.test(entry))
      assert.ok(nestedEntry, `application jar is missing dependency matching ${pattern}`)
      const extraction = spawnSync(jarExecutable(), ['xf', resolve(applicationJar), nestedEntry],
        { ...CHECK_OPTIONS, cwd: workspace })
      assert.ifError(extraction.error)
      assert.equal(extraction.status, 0, extraction.stderr || extraction.stdout)
    }

    const sqlite = nestedJar(workspace, outerEntries, /^BOOT-INF\/lib\/sqlite-jdbc-.*\.jar$/)
    const sqliteTarget = architecture === 'arm64' ? 'org/sqlite/native/Mac/aarch64/' : 'org/sqlite/native/Mac/x86_64/'
    assertOnlyTargetNativeEntries(sqlite, 'org/sqlite/native/', sqliteTarget)

    const pty4j = nestedJar(workspace, outerEntries, /^BOOT-INF\/lib\/pty4j-.*\.jar$/)
    assertOnlyTargetNativeEntries(pty4j, 'resources/com/pty4j/native/', 'resources/com/pty4j/native/darwin/')
    assert.ok(!jarEntries(pty4j).some(entry => entry.startsWith('com/pty4j/windows/')),
      'pty4j retains Windows implementation classes')
    assertPty4jMachOArchitecture(pty4j, architecture)

    const jna = nestedJar(workspace, outerEntries, /^BOOT-INF\/lib\/jna-[0-9].*\.jar$/)
    const jnaTarget = architecture === 'arm64' ? 'com/sun/jna/darwin-aarch64/' : 'com/sun/jna/darwin-x86-64/'
    const jnaNativeEntries = jarEntries(jna).filter(entry => /\/(?:libjnidispatch|jnidispatch)[^/]*$/.test(entry))
    assert.ok(jnaNativeEntries.length > 0, 'JNA jar contains no native dispatcher')
    assert.ok(jnaNativeEntries.every(entry => entry.startsWith(jnaTarget)),
      `JNA jar contains a non-target native dispatcher: ${jnaNativeEntries.join(', ')}`)

    const jnaPlatform = nestedJar(workspace, outerEntries, /^BOOT-INF\/lib\/jna-platform-.*\.jar$/)
    assert.deepEqual(
      jarEntries(jnaPlatform).filter(entry => /^com\/sun\/jna\/platform\/(?:linux|win32|wince)\//.test(entry)),
      [],
      'JNA platform jar contains Linux or Windows implementations',
    )
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
}

function nestedJar(workspace, outerEntries, pattern) {
  const entry = outerEntries.find(candidate => pattern.test(candidate))
  assert.ok(entry)
  return join(workspace, entry)
}

function assertOnlyTargetNativeEntries(jar, nativeRoot, targetRoot) {
  const nativeEntries = jarEntries(jar).filter(entry => entry.startsWith(nativeRoot) && !entry.endsWith('/'))
  assert.ok(nativeEntries.length > 0, `${jar} contains no native files`)
  assert.ok(nativeEntries.every(entry => entry.startsWith(targetRoot)),
    `${jar} contains non-target native files: ${nativeEntries.filter(entry => !entry.startsWith(targetRoot)).join(', ')}`)
}

function assertPty4jMachOArchitecture(pty4j, architecture) {
  const workspace = mkdtempSync(join(tmpdir(), 'termestra-pty4j-architecture-'))
  const entries = [
    'resources/com/pty4j/native/darwin/libpty.dylib',
    'resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper',
  ]
  try {
    const extraction = spawnSync(jarExecutable(), ['xf', resolve(pty4j), ...entries],
      { ...CHECK_OPTIONS, cwd: workspace })
    assert.ifError(extraction.error)
    assert.equal(extraction.status, 0, extraction.stderr || extraction.stdout)
    const expected = architecture === 'arm64' ? 'arm64' : 'x86_64'
    for (const entry of entries) {
      const slices = spawnSync('lipo', ['-archs', join(workspace, entry)], CHECK_OPTIONS)
      assert.ifError(slices.error)
      assert.equal(slices.status, 0, slices.stderr || slices.stdout)
      assert.equal(slices.stdout.trim(), expected, `${entry} must contain only the ${expected} Mach-O slice`)
    }
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
}

function jarEntries(path) {
  const result = spawnSync(jarExecutable(), ['tf', resolve(path)], CHECK_OPTIONS)
  assert.ifError(result.error)
  assert.equal(result.status, 0, result.stderr || result.stdout)
  return result.stdout.split(/\r?\n/).filter(Boolean)
}

function jarExecutable() {
  return process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'jar') : 'jar'
}
