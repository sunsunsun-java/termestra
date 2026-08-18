import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'

const loadServiceWorker = async ({
  cache,
  cacheNames = [],
  claimClients = async () => {},
  deleteCache = async () => true,
  fetch,
  listCaches = async () => cacheNames,
}) => {
  const listeners = new Map()
  const self = {
    addEventListener(type, listener) {
      listeners.set(type, listener)
    },
    clients: { claim: claimClients },
    location: { origin: 'http://127.0.0.1:5180' },
    skipWaiting() {},
  }
  const source = await readFile(new URL('../web/src/sw.template.js', import.meta.url), 'utf8')
  vm.runInNewContext(source, {
    URL,
    caches: {
      delete: deleteCache,
      keys: listCaches,
      open: async () => cache,
    },
    console: { warn() {} },
    DOMException,
    fetch,
    Promise,
    self,
  })
  return listeners
}

test('a cache write failure cannot turn a successful asset response into a failed request', async () => {
  const networkResponse = {
    clone: () => ({ clone: true }),
    ok: true,
  }
  const listeners = await loadServiceWorker({
    cache: {
      match: async () => undefined,
      put: async () => {
        throw new DOMException('quota exceeded', 'QuotaExceededError')
      },
    },
    fetch: async () => networkResponse,
  })
  const fetchHandler = listeners.get('fetch')
  let responsePromise

  fetchHandler({
    request: {
      method: 'GET',
      url: 'http://127.0.0.1:5180/assets/app-a1b2.js',
    },
    respondWith(value) {
      responsePromise = value
    },
  })

  assert.equal(await responsePromise, networkResponse)
})

test('asset query variants share one canonical cache key', async () => {
  const writes = []
  const listeners = await loadServiceWorker({
    cache: {
      match: async () => undefined,
      put: async (key) => {
        writes.push(key)
      },
    },
    fetch: async () => ({ clone: () => ({}), ok: true }),
  })
  let responsePromise

  listeners.get('fetch')({
    request: {
      method: 'GET',
      url: 'http://127.0.0.1:5180/assets/app-a1b2.js?cache-bust=1',
    },
    respondWith(value) {
      responsePromise = value
    },
  })
  await responsePromise

  assert.deepEqual(writes, ['http://127.0.0.1:5180/assets/app-a1b2.js'])
})

test('unknown same-origin GET paths bypass the app-shell cache', async () => {
  const listeners = await loadServiceWorker({
    cache: { match: async () => undefined, put: async () => {} },
    fetch: async () => ({ clone: () => ({}), ok: true }),
  })
  let intercepted = false

  listeners.get('fetch')({
    request: { method: 'GET', url: 'http://127.0.0.1:5180/arbitrary-resource' },
    respondWith() {
      intercepted = true
    },
  })

  assert.equal(intercepted, false)
})

test('offline index.html navigation reuses the root app-shell entry', async () => {
  const cachedShell = { cached: true }
  const reads = []
  const listeners = await loadServiceWorker({
    cache: {
      match: async (key) => {
        reads.push(key)
        return key === 'http://127.0.0.1:5180/' ? cachedShell : undefined
      },
      put: async () => {},
    },
    fetch: async () => {
      throw new TypeError('offline')
    },
  })
  let responsePromise

  listeners.get('fetch')({
    request: { method: 'GET', url: 'http://127.0.0.1:5180/index.html' },
    respondWith(value) {
      responsePromise = value
    },
  })

  assert.equal(await responsePromise, cachedShell)
  assert.deepEqual(reads, ['http://127.0.0.1:5180/'])
})

test('precache storage failure does not reject service-worker installation', async () => {
  let attempts = 0
  const listeners = await loadServiceWorker({
    cache: {
      addAll: async () => {
        attempts += 1
        throw new DOMException('quota exceeded', 'QuotaExceededError')
      },
      match: async () => undefined,
      put: async () => {},
    },
    fetch: async () => ({ clone: () => ({}), ok: true }),
  })
  let installPromise

  listeners.get('install')({
    waitUntil(value) {
      installPromise = value
    },
  })

  await installPromise
  assert.equal(attempts, 2)
})

test('activation deletes every non-current Termestra cache and preserves unrelated caches', async () => {
  const deletions = []
  let claims = 0
  const listeners = await loadServiceWorker({
    cache: { match: async () => undefined, put: async () => {} },
    cacheNames: [
      'termestra-cache-v0.0.1-shell',
      'termestra-cache-v0.0.1-assets',
      'termestra-cache-v__TERMESTRA_VERSION__-shell',
      'termestra-cache-v__TERMESTRA_VERSION__-assets',
      'termestra-cache-v__TERMESTRA_VERSION__-static',
      'another-app-cache',
    ],
    deleteCache: async (cacheName) => {
      deletions.push(cacheName)
      return true
    },
    claimClients: async () => {
      claims += 1
    },
    fetch: async () => ({ clone: () => ({}), ok: true }),
  })
  let activationPromise

  listeners.get('activate')({
    waitUntil(value) {
      activationPromise = value
    },
  })

  await activationPromise
  assert.deepEqual(deletions.sort(), [
    'termestra-cache-v0.0.1-assets',
    'termestra-cache-v0.0.1-shell',
  ])
  assert.equal(claims, 1)
})

test('an obsolete-cache deletion failure does not block worker activation', async () => {
  let claims = 0
  const listeners = await loadServiceWorker({
    cache: { match: async () => undefined, put: async () => {} },
    cacheNames: [
      'termestra-cache-v0.0.1-shell',
      'termestra-cache-v0.0.2-shell',
      'termestra-cache-v__TERMESTRA_VERSION__-shell',
    ],
    deleteCache: async () => {
      throw new DOMException('cache storage unavailable', 'InvalidStateError')
    },
    claimClients: async () => {
      claims += 1
    },
    fetch: async () => ({ clone: () => ({}), ok: true }),
  })
  let activationPromise

  listeners.get('activate')({
    waitUntil(value) {
      activationPromise = value
    },
  })

  await activationPromise
  assert.equal(claims, 1)
})

test('cache enumeration failure still allows the new worker to activate', async () => {
  let claims = 0
  const listeners = await loadServiceWorker({
    cache: { match: async () => undefined, put: async () => {} },
    fetch: async () => ({ clone: () => ({}), ok: true }),
    claimClients: async () => {
      claims += 1
    },
    listCaches: async () => {
      throw new DOMException('cache storage unavailable', 'InvalidStateError')
    },
  })
  let activationPromise

  listeners.get('activate')({
    waitUntil(value) {
      activationPromise = value
    },
  })

  await activationPromise
  assert.equal(claims, 1)
})
