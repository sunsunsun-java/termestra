#!/usr/bin/env node
import { spawnSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const launcher = join(dirname(fileURLToPath(import.meta.url)), 'termestra.mjs')
const result = spawnSync(process.execPath, [launcher, 'team', ...process.argv.slice(2)], {
  stdio: 'inherit',
  env: process.env,
})
if (result.error) console.error(`Failed to start team client: ${result.error.message}`)
process.exit(result.status ?? 1)
