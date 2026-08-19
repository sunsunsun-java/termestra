#!/usr/bin/env node
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import {
  copyFileSync,
  createReadStream,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'

const TIMEOUT_MS = 120_000
const RUNTIME_VARIANTS = [
  { platform: 'darwin', architecture: 'arm64' },
  { platform: 'darwin', architecture: 'x64' },
  { platform: 'linux', architecture: 'arm64' },
  { platform: 'linux', architecture: 'x64' },
  { platform: 'win32', architecture: 'x64' },
]
const targetArgument = process.argv[2]
if (!targetArgument) {
  console.error('Usage: node verify-npm-install.mjs <distribution-target>')
  process.exit(2)
}

const target = resolve(targetArgument)
const currentVariant = { platform: process.platform, architecture: process.arch }
const currentRuntimeName = runtimeName(currentVariant)
const runtimeDirectory = join(target, 'npm', currentRuntimeName.replace('@termestra/', ''))
const cliDirectory = join(target, 'npm-cli')
const artifactDirectory = join(target, 'npm-tarballs')
const workspace = mkdtempSync(join(tmpdir(), 'termestra-npm-install-'))

let registry
try {
  assert.ok(existsSync(runtimeDirectory), `runtime package directory is missing: ${runtimeDirectory}`)
  assert.ok(existsSync(cliDirectory), `CLI package directory is missing: ${cliDirectory}`)

  const cacheDirectory = join(workspace, 'npm-cache')
  const packedDirectory = join(workspace, 'packed')
  const prefix = join(workspace, 'prefix')
  mkdirSync(packedDirectory, { recursive: true })
  mkdirSync(join(prefix, 'etc'), { recursive: true })

  const npmEnvironment = {
    ...process.env,
    npm_config_audit: 'false',
    npm_config_cache: cacheDirectory,
    npm_config_fund: 'false',
    npm_config_update_notifier: 'false',
  }
  const runtimePackage = await pack(runtimeDirectory, packedDirectory, npmEnvironment)
  const cliPackage = await pack(cliDirectory, packedDirectory, npmEnvironment)
  assert.equal(runtimePackage.name, currentRuntimeName)
  assert.equal(cliPackage.name, '@termestra/cli')
  assert.equal(runtimePackage.version, cliPackage.version)
  assertExecutableEntry(runtimePackage, `runtime/bin/${process.platform === 'win32' ? 'java.exe' : 'java'}`)
  assertExecutableEntry(cliPackage, 'bin/termestra.mjs')
  assertExecutableEntry(cliPackage, 'bin/team.mjs')
  assertPackagedFiles(cliPackage)

  const runtimeManifest = readJson(join(runtimeDirectory, 'package.json'))
  const cliManifest = readJson(join(cliDirectory, 'package.json'))
  assert.equal(runtimeManifest.name, runtimePackage.name)
  assert.equal(runtimeManifest.version, runtimePackage.version)
  assert.equal(cliManifest.name, cliPackage.name)
  assert.equal(cliManifest.version, cliPackage.version)
  assertRuntimeDependencySet(cliManifest, runtimePackage.version)

  registry = await startRegistry({ cliManifest, cliPackage, runtimeManifest, runtimePackage })
  const npmrc = [
    `registry=${registry.url}`,
    `@termestra:registry=${registry.url}`,
    'audit=false',
    'fund=false',
    'update-notifier=false',
    '',
  ].join('\n')
  writeFileSync(join(prefix, 'etc', 'npmrc'), npmrc)

  await runNpm([
    'install',
    '--global',
    '--prefix', prefix,
    '--registry', registry.url,
    '--no-audit',
    '--no-fund',
    '@termestra/cli',
  ], { env: npmEnvironment })

  const globalRoot = (await runNpm(['root', '--global', '--prefix', prefix], { env: npmEnvironment })).stdout.trim()
  const installedCli = join(globalRoot, '@termestra', 'cli')
  assert.ok(existsSync(installedCli), 'global npm install did not install @termestra/cli')
  const installedRequire = createRequire(join(installedCli, 'bin', 'termestra.mjs'))
  const installedRuntime = dirname(installedRequire.resolve(`${currentRuntimeName}/package.json`))
  if (process.platform !== 'win32') {
    assert.ok(statSync(join(installedRuntime, 'runtime', 'bin', 'java')).mode & 0o111, 'installed embedded Java lost its executable mode')
  }

  const termestra = process.platform === 'win32' ? 'termestra.cmd' : join(prefix, 'bin', 'termestra')
  const launcherOptions = process.platform === 'win32' ? { cwd: prefix } : {}
  const version = await runCommand(termestra, ['--version'], launcherOptions)
  assert.equal(version.stdout.trim(), cliPackage.version)
  const teamHelp = await runCommand(termestra, ['team', '--help'], launcherOptions)
  assert.match(teamHelp.stdout, /Usage: team/)

  mkdirSync(artifactDirectory, { recursive: true })
  copyFileSync(runtimePackage.path, join(artifactDirectory, runtimePackage.filename))
  copyFileSync(cliPackage.path, join(artifactDirectory, cliPackage.filename))
  console.log(`Verified npm install for ${cliPackage.name}@${cliPackage.version}`)
} finally {
  if (registry) await registry.close()
  rmSync(workspace, { recursive: true, force: true })
}

function runtimeName(variant) {
  return `@termestra/runtime-${variant.platform}-${variant.architecture}`
}

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'))
}

async function pack(packageDirectory, packedDirectory, environment) {
  const result = await runNpm(['pack', '--json', '--pack-destination', packedDirectory, packageDirectory], { env: environment })
  const [packageResult] = JSON.parse(result.stdout)
  assert.ok(packageResult, `npm pack did not produce a package for ${packageDirectory}`)
  const path = join(packedDirectory, packageResult.filename)
  assert.ok(existsSync(path), `npm pack did not create ${path}`)
  return { ...packageResult, path }
}

function assertExecutableEntry(packageResult, path) {
  const entry = packageResult.files.find(file => file.path === path)
  assert.ok(entry, `${packageResult.name} tarball is missing ${path}`)
  if (process.platform !== 'win32') {
    assert.ok(entry.mode & 0o111, `${packageResult.name} tarball lost the executable mode for ${path}`)
  }
}

function assertPackagedFiles(packageResult) {
  const paths = packageResult.files.map(file => file.path)
  for (const required of ['package.json', 'README.md', 'bin/termestra.mjs', 'docs/release/npm.md']) {
    assert.ok(paths.includes(required), `${packageResult.name} tarball is missing ${required}`)
  }
  for (const retiredDirectory of [
    'frontend/web/public/cli-agent-icons/',
    'frontend/web/public/open-target-icons/',
  ]) {
    assert.ok(
      !paths.some(path => path.startsWith(retiredDirectory)),
      `${packageResult.name} tarball contains retired asset directory: ${retiredDirectory}`,
    )
  }
  for (const path of paths) {
    assert.ok(!path.startsWith('target/'), `${packageResult.name} tarball contains generated target output: ${path}`)
    assert.ok(!path.includes('.DS_Store'), `${packageResult.name} tarball contains macOS metadata: ${path}`)
  }
}

function assertRuntimeDependencySet(cliManifest, version) {
  const expected = new Set(RUNTIME_VARIANTS.map(runtimeName))
  const dependencies = cliManifest.optionalDependencies ?? {}
  assert.deepEqual(new Set(Object.keys(dependencies)), expected)
  for (const dependencyVersion of Object.values(dependencies)) assert.equal(dependencyVersion, version)
}

async function startRegistry({ cliManifest, cliPackage, runtimeManifest, runtimePackage }) {
  const tarballs = new Map([
    [`/tarballs/${cliPackage.filename}`, cliPackage.path],
    [`/tarballs/${runtimePackage.filename}`, runtimePackage.path],
  ])
  let baseUrl
  const server = createServer((request, response) => {
    const requestUrl = new URL(request.url ?? '/', baseUrl)
    const tarball = tarballs.get(requestUrl.pathname)
    if (tarball) {
      response.writeHead(200, { 'content-type': 'application/octet-stream' })
      createReadStream(tarball).pipe(response)
      return
    }

    const packageName = decodeURIComponent(requestUrl.pathname.replace(/^\//, ''))
    if (packageName === cliManifest.name) {
      sendJson(response, packument(cliManifest, cliPackage, baseUrl))
      return
    }
    const variant = RUNTIME_VARIANTS.find(candidate => runtimeName(candidate) === packageName)
    if (variant) {
      const manifest = packageName === runtimeManifest.name
        ? runtimeManifest
        : syntheticRuntimeManifest(variant, cliManifest.version)
      const tarballUrl = packageName === runtimeManifest.name
        ? `${baseUrl}/tarballs/${runtimePackage.filename}`
        : `${baseUrl}/unavailable/${packageName.replace('@termestra/', '')}.tgz`
      const packed = packageName === runtimeManifest.name ? runtimePackage : undefined
      sendJson(response, packument(manifest, packed, baseUrl, tarballUrl))
      return
    }
    response.writeHead(404).end()
  })

  const address = await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => resolve(server.address()))
  })
  assert.ok(address && typeof address === 'object', 'local npm registry did not bind an address')
  baseUrl = `http://127.0.0.1:${address.port}`
  return {
    url: baseUrl,
    close: () => new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())),
  }
}

function syntheticRuntimeManifest(variant, version) {
  return {
    name: runtimeName(variant),
    version,
    os: [variant.platform],
    cpu: [variant.architecture],
    ...(variant.platform === 'linux' ? { libc: 'glibc' } : {}),
  }
}

function packument(manifest, packed, baseUrl, explicitTarballUrl) {
  const tarball = explicitTarballUrl ?? `${baseUrl}/tarballs/${packed.filename}`
  return {
    name: manifest.name,
    'dist-tags': { latest: manifest.version },
    versions: {
      [manifest.version]: {
        ...manifest,
        dist: {
          tarball,
          ...(packed ? { integrity: packed.integrity } : {}),
        },
      },
    },
  }
}

function sendJson(response, body) {
  response.writeHead(200, { 'content-type': 'application/json' })
  response.end(JSON.stringify(body))
}

function runNpm(args, options = {}) {
  return runCommand(process.platform === 'win32' ? 'npm.cmd' : 'npm', args, options)
}

function runCommand(command, args, options = {}) {
  if (process.platform === 'win32' && command.toLowerCase().endsWith('.cmd')) {
    return run(process.env.ComSpec ?? 'cmd.exe', ['/d', '/s', '/c', command, ...args], options)
  }
  return run(command, args, options)
}

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd,
      env: options.env,
      shell: false,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    })
    let stdout = ''
    let stderr = ''
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      child.kill()
    }, TIMEOUT_MS)
    child.stdout.on('data', chunk => { stdout += chunk })
    child.stderr.on('data', chunk => { stderr += chunk })
    child.once('error', error => {
      clearTimeout(timer)
      reject(error)
    })
    child.once('close', code => {
      clearTimeout(timer)
      if (code === 0 && !timedOut) {
        resolve({ stdout, stderr })
        return
      }
      reject(new Error(`${command} ${args.join(' ')} failed${timedOut ? ' after timeout' : ` with exit code ${code}`}\n${stderr || stdout}`))
    })
  })
}
