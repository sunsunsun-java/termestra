#!/usr/bin/env node
import { createRequire } from 'node:module'

import { runtimePackageName, runtimePlatform } from './runtime-package.mjs'

const platform = runtimePlatform()
const packageName = runtimePackageName()
if (!packageName) {
  console.error(`Termestra supports macOS only; detected ${platform}.`)
  process.exit(1)
}

const require = createRequire(import.meta.url)
try {
  require.resolve(`${packageName}/package.json`)
} catch {
  console.error(`Termestra runtime package ${packageName} is missing. npm may have skipped a failed optional download; retry: npm install -g @termestra/cli`)
  process.exit(1)
}
