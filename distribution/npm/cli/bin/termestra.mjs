#!/usr/bin/env node
import { createRequire } from 'node:module'
import { basename, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { existsSync, readFileSync } from 'node:fs'
import { spawn, spawnSync } from 'node:child_process'

import { runtimePackageName, runtimePlatform } from './runtime-package.mjs'

const args = process.argv.slice(2)
const npmCommand = 'npm'
const npmInstallArguments = ['install', '-g', '@termestra/cli@latest']
const usage = `Usage:
  termestra [--port <port>]
  termestra update
  termestra team <command>

Options:
  --port <port>   Bind the local runtime to a specific port (default: 3000).
  -h, --help      Print this help.
  -v, --version   Print the installed Termestra version.

Commands:
  update          Upgrade Termestra via npm install -g.
  team            Coordinate agents through the local runtime.`

if (args[0] === 'update') {
  if (args.length > 1) {
    if (args.length === 2 && (args[1] === '--help' || args[1] === '-h')) {
      console.log('Usage: termestra update\n\nUpgrade Termestra with npm install -g @termestra/cli@latest.')
      process.exit(0)
    }
    console.error(`Unknown update argument: ${args[1]}`)
    console.error('Usage: termestra update')
    process.exit(1)
  }
  const result = spawnSync(npmCommand, npmInstallArguments, { stdio: 'inherit' })
  if (result.error) {
    console.error(`Failed to update Termestra: ${result.error.message}`)
    console.error('Run manually: npm install -g @termestra/cli@latest')
    process.exit(1)
  }
  if (result.status === 0) console.log('Termestra updated. Restart `termestra` to use the new version.')
  process.exit(result.status ?? 1)
}
if (args[0] !== 'team' && (args.includes('--help') || args.includes('-h'))) {
  console.log(usage)
  process.exit(0)
}
if (args[0] !== 'team' && (args.includes('--version') || args.includes('-v'))) {
  const packageFile = join(dirname(fileURLToPath(import.meta.url)), '..', 'package.json')
  console.log(JSON.parse(readFileSync(packageFile, 'utf8')).version)
  process.exit(0)
}

const javaArgs = []
if (args[0] === 'team') {
  javaArgs.push(...args)
} else {
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index]
    if (argument !== '--port') {
      console.error(argument?.startsWith('-') ? `Unknown option: ${argument}` : `Unknown argument: ${argument}`)
      process.exit(1)
    }
    const value = args[index + 1]
    const port = Number.parseInt(value ?? '', 10)
    if (!value || Number.isNaN(port) || port < 0 || port > 65535) {
      console.error(value ? `Invalid port: ${value}` : 'Usage: termestra [--port <port>]')
      process.exit(1)
    }
    javaArgs.push(`--server.port=${port}`)
    index += 1
  }
}

const platform = runtimePlatform()
const packageName = runtimePackageName()
if (!packageName) {
  console.error(`Termestra supports macOS only; detected ${platform}.`)
  process.exit(1)
}
const require = createRequire(import.meta.url)
const cliRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
let root
try { root = dirname(require.resolve(`${packageName}/package.json`)) }
catch {
  const recovered = join(cliRoot, '.runtime', basename(packageName))
  const local = join(cliRoot, '..', 'runtime-current')
  if (existsSync(join(recovered, 'package.json'))) root = recovered
  else if (existsSync(local)) root = local
}
if (!root) {
  console.error(`Termestra runtime package ${packageName} is missing. npm may have skipped a failed optional download; retry: npm install -g @termestra/cli`)
  process.exit(1)
}
const java = join(root, 'runtime', 'bin', 'java')
const jar = join(root, 'app', 'termestra.jar')
const child = spawn(java, ['-jar', jar, ...javaArgs], { stdio: 'inherit', env: process.env })
let forwardedSignal = null
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    forwardedSignal = signal
    if (!child.killed) child.kill(signal)
  })
}
child.on('error', error => {
  console.error(`Failed to start Termestra: ${error.message}`)
  process.exit(1)
})
child.on('exit', (code, signal) => {
  const terminalSignal = signal ?? forwardedSignal
  if (terminalSignal === 'SIGINT') process.exit(130)
  if (terminalSignal === 'SIGTERM') process.exit(143)
  process.exit(code ?? 1)
})
