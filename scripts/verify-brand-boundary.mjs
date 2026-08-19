#!/usr/bin/env node

import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const ignoredDirectories = new Set(['.git', 'dist', 'node_modules', 'target', 'vendor'])
const legalFiles = new Set([
  'LICENSE',
  'LICENSE.BSL',
  'NOTICE',
  'TRADEMARK.md',
  'docs/governance/licensing-review.md',
])
const attributionFiles = new Set(['README.md', 'README.zh-CN.md'])
const negativeContractTests = new Set([
  'backend/src/test/java/dev/termestra/bootstrap/AgentExecutionHttpIntegrationTest.java',
  'backend/src/test/java/dev/termestra/bootstrap/TeamProtocolHttpIntegrationTest.java',
  'backend/src/test/java/dev/termestra/bootstrap/WorkspaceCreationIdempotencyHttpIntegrationTest.java',
  'backend/src/test/java/dev/termestra/platform/cli/team/TeamCliTest.java',
  'backend/src/test/java/dev/termestra/tasks/adapter/out/filesystem/NioTasksDocumentStoreTest.java',
])
const legacyBrandPatterns = [
  /\bhive\b/i,
  /(?:^|[^A-Za-z])(?:hive|Hive|HIVE)(?=[A-Z_])/,
]

export const normalizeRepositoryPath = (path) => path.replaceAll('\\', '/')

const discoverTextMatches = async (directory, matches) => {
  const entries = await readdir(directory, { withFileTypes: true })
  for (const entry of entries) {
    if (entry.isSymbolicLink()) continue
    if (entry.isDirectory()) {
      if (!ignoredDirectories.has(entry.name)) {
        await discoverTextMatches(resolve(directory, entry.name), matches)
      }
      continue
    }
    if (!entry.isFile()) continue

    const absolutePath = resolve(directory, entry.name)
    const path = normalizeRepositoryPath(relative(repositoryRoot, absolutePath))
    if (path === 'scripts/verify-brand-boundary.mjs') continue
    const content = await readFile(absolutePath)
    if (content.includes(0)) continue
    const text = content.toString('utf8')
    if (legacyBrandPatterns.some((pattern) => pattern.test(text))) matches.push(path)
  }
}

const verifyBrandBoundary = async () => {
  const matches = []
  await discoverTextMatches(repositoryRoot, matches)
  matches.sort()
  const unexpected = matches.filter(
    (path) =>
      !legalFiles.has(path) &&
      !attributionFiles.has(path) &&
      !negativeContractTests.has(path),
  )
  assert.deepEqual(
    unexpected,
    [],
    `non-attribution legacy brand references remain:\n${unexpected.join('\n')}`,
  )
  console.log(
    `Verified Termestra brand boundary (${matches.length} legal-attribution or rejection-test files)`,
  )
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await verifyBrandBoundary()
}
