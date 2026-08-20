#!/usr/bin/env node
import { createRequire } from 'node:module'
import { readFileSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { runtimePackageName, runtimePlatform } from './runtime-package.mjs'
import { recoverRuntimePackage } from './runtime-recovery.mjs'

const platform = runtimePlatform()
const packageName = runtimePackageName()
if (!packageName) {
  console.error(`Termestra supports macOS only; detected ${platform}.`)
  process.exit(1)
}

const require = createRequire(import.meta.url)
const cliRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const cliManifest = JSON.parse(readFileSync(join(cliRoot, 'package.json'), 'utf8'))
const version = cliManifest.optionalDependencies?.[packageName]
let installed = false
try {
  require.resolve(`${packageName}/package.json`)
  installed = true
} catch {
  try {
    const recoveredManifest = JSON.parse(readFileSync(
      join(cliRoot, '.runtime', basename(packageName), 'package.json'), 'utf8'))
    installed = recoveredManifest.name === packageName && recoveredManifest.version === version
  } catch {
    installed = false
  }
  if (!installed && process.env.TERMESTRA_DISABLE_RUNTIME_RECOVERY !== '1' && version) {
    try {
      console.log(`Termestra: retrying interrupted download for ${packageName}@${version}...`)
      await recoverRuntimePackage({
        packageName,
        version,
        platform: process.platform,
        architecture: process.arch,
        cliRoot,
      })
      installed = true
    } catch (error) {
      console.error(`Termestra automatic runtime recovery failed: ${error instanceof Error ? error.message : error}`)
    }
  }
}
if (!installed) {
  console.error(`Termestra runtime package ${packageName} is missing. npm may have skipped a failed optional download; retry: npm install -g @termestra/cli`)
  process.exit(1)
}
