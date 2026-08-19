#!/usr/bin/env node
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const tag = process.argv[2]
if (!tag) {
  console.error('Usage: node verify-release-tag.mjs <vX.Y.Z[-prerelease]>')
  process.exit(2)
}

const version = tag.startsWith('v') ? tag.slice(1) : ''
const semver = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:0|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*|[1-9]\d*)(?:\.(?:0|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*|[1-9]\d*))*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/
assert.ok(semver.test(version), `Release tag must be a semantic version prefixed with v, received: ${tag}`)
assert.ok(!version.toLowerCase().includes('snapshot'), `Release tag must not contain SNAPSHOT, received: ${tag}`)

const scriptDirectory = resolve(fileURLToPath(new URL('.', import.meta.url)))
const rootPom = readFileSync(resolve(scriptDirectory, '../../pom.xml'), 'utf8')
const sourceVersion = rootPom.match(/<artifactId>termestra-parent<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1]
assert.equal(sourceVersion, `${version}-SNAPSHOT`, `pom.xml must declare ${version}-SNAPSHOT before tagging ${tag}; found ${sourceVersion ?? 'no project version'}`)

console.log(`Verified release tag ${tag} against source version ${sourceVersion}`)
