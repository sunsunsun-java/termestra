import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import type { Plugin } from 'vite'

const TEMPLATE_URL = new URL('../sw.template.js', import.meta.url)
export const TERMESTRA_SW_TOKEN = '__TERMESTRA_VERSION__'

/**
 * Replace the build-time VERSION token in a service-worker template. Kept as a
 * pure function so substitution behavior is unit-testable without standing up
 * a Vite build.
 */
export const substituteSwTemplate = (template: string, version: string): string =>
  template.split(TERMESTRA_SW_TOKEN).join(version)

interface BuildSwOptions {
  version: string
  cacheRevision?: string
}

export const serviceWorkerCacheGeneration = (
  version: string,
  cacheRevision?: string
): string => [version, cacheRevision].filter(Boolean).join('-')

/**
 * Emit `dist/sw.js` during `vite build`. The SW source lives at
 * `web/src/sw.template.js` (not under the type-checked `src/**` tree because it
 * targets ServiceWorkerGlobalScope, which isn't in the runtime tsconfig).
 */
export const buildSw = (options: BuildSwOptions): Plugin => ({
  name: 'termestra-build-sw',
  apply: 'build',
  generateBundle() {
    const template = readFileSync(fileURLToPath(TEMPLATE_URL), 'utf8')
    const cacheGeneration = serviceWorkerCacheGeneration(options.version, options.cacheRevision)
    const source = substituteSwTemplate(template, cacheGeneration)
    this.emitFile({ type: 'asset', fileName: 'sw.js', source })
  },
})
