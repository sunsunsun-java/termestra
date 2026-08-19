#!/usr/bin/env node
import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const CHECK_OPTIONS = { encoding: 'utf8', timeout: 15_000, maxBuffer: 1024 * 1024, killSignal: 'SIGKILL' }
const REPOSITORY_URL = 'git+https://github.com/sunsunsun-java/termestra.git'
const HOMEPAGE = 'https://github.com/sunsunsun-java/termestra#readme'
const BUGS_URL = 'https://github.com/sunsunsun-java/termestra/issues'
const REGISTRY = 'https://registry.npmjs.org'
const RUNTIME_PLATFORMS = [
  'darwin-arm64',
  'darwin-x64',
  'linux-arm64',
  'linux-x64',
  'win32-x64',
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

assertPublicationMetadata(manifest, `@termestra/runtime-${platform}`)
assert.equal(manifest.private, false)
assert.match(manifest.description, /^Termestra embedded Java runtime for /)
assert.deepEqual(manifest.os, [process.platform])
assert.deepEqual(manifest.cpu, [process.arch])
if (process.platform === 'linux') assert.equal(manifest.libc, 'glibc')
else assert.equal(manifest.libc, undefined)

assertPublicationMetadata(cliManifest, '@termestra/cli')
assert.equal(cliManifest.preferGlobal, true)
assert.deepEqual(cliManifest.engines, { node: '>=20' })
assert.deepEqual(Object.keys(cliManifest.optionalDependencies).sort(), RUNTIME_PLATFORMS.map(platformName => `@termestra/runtime-${platformName}`).sort())
for (const version of Object.values(cliManifest.optionalDependencies)) assert.equal(version, manifest.version)
for (const entry of ['bin/', 'docs/', 'frontend/web/public/screenshots/']) {
  assert.ok(cliManifest.files.includes(entry), `npm CLI files allowlist is missing: ${entry}`)
}

const applicationJar = join(runtime, 'app', 'termestra.jar')
assert.ok(existsSync(applicationJar), 'application jar is missing')
assertEmbeddedApplicationVersion(applicationJar, manifest.version)
assert.ok(existsSync(join(runtime, 'LICENSE.BSL')), 'runtime source license is missing')
assert.ok(existsSync(join(runtime, 'LICENSE')), 'runtime historical license notice is missing')
assert.ok(existsSync(join(runtime, 'NOTICE')), 'runtime NOTICE is missing')
assert.ok(existsSync(join(runtime, 'TRADEMARK.md')), 'runtime trademark notice is missing')
assert.ok(existsSync(join(runtime, 'THIRD_PARTY_NOTICES.md')), 'runtime third-party notices are missing')

const java = join(runtime, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
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
    const jarExecutable = process.platform === 'win32' ? 'jar.exe' : 'jar'
    const jar = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', jarExecutable) : jarExecutable
    const extraction = spawnSync(
      jar,
      ['--extract', '--file', resolve(applicationJar), propertiesEntry],
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
