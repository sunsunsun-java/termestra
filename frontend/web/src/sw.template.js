// Termestra PWA service worker.
//
// The single occurrence of the build-time version placeholder lives in the
// VERSION constant directly below. `web/src/pwa/build-sw.ts` rewrites it at
// `vite build` time so each Termestra release writes to its own cache bucket.
// Activation retains only the current Termestra cache generation.

const VERSION = '__TERMESTRA_VERSION__'
const CACHE_PREFIX = 'termestra-cache-v'
const SHELL_CACHE = `${CACHE_PREFIX}${VERSION}-shell`
const ASSETS_CACHE = `${CACHE_PREFIX}${VERSION}-assets`
const STATIC_CACHE = `${CACHE_PREFIX}${VERSION}-static`

const SHELL_PRECACHE = ['/']
const STATIC_PRECACHE = [
  '/logo.png',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-512-maskable.png',
  '/icons/apple-touch-icon-180.png',
  '/icons/icon-32.png',
]

const reportCacheFailure = (operation, error) => {
  console.warn(`[termestra] service worker ${operation} failed; continuing with the network`, error)
}

const handleRecoverableCacheFailure = (operation, error) => {
  if (!(error instanceof DOMException) && !(error instanceof TypeError)) throw error
  reportCacheFailure(operation, error)
}

const precache = async (cacheName, urls) => {
  try {
    const cache = await caches.open(cacheName)
    if (urls.length > 0) await cache.addAll(urls)
  } catch (error) {
    handleRecoverableCacheFailure('precache', error)
  }
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    Promise.all([
      precache(SHELL_CACHE, SHELL_PRECACHE),
      precache(STATIC_CACHE, STATIC_PRECACHE),
      precache(ASSETS_CACHE, []),
    ])
  )
})

const activateWorker = async () => {
  let cacheNames
  try {
    cacheNames = await caches.keys()
  } catch (error) {
    handleRecoverableCacheFailure('enumeration', error)
    cacheNames = []
  }
  const currentCacheNames = new Set([SHELL_CACHE, ASSETS_CACHE, STATIC_CACHE])
  await Promise.all(
    cacheNames.map(async (cacheName) => {
      if (!cacheName.startsWith(CACHE_PREFIX) || currentCacheNames.has(cacheName)) return
      try {
        await caches.delete(cacheName)
      } catch (error) {
        handleRecoverableCacheFailure('delete', error)
      }
    })
  )
  try {
    await self.clients.claim()
  } catch (error) {
    handleRecoverableCacheFailure('client claim', error)
  }
}

self.addEventListener('activate', (event) => {
  event.waitUntil(activateWorker())
})

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting()
  }
})

const isHashedAsset = (pathname) => pathname.startsWith('/assets/')
const isStaticAsset = (pathname) =>
  pathname.startsWith('/icons/') ||
  pathname.startsWith('/screenshots/') ||
  pathname.startsWith('/sounds/') ||
  pathname === '/logo.png'
const isShell = (pathname) => pathname === '/' || pathname === '/index.html'

const normalizedCacheKey = (request) => {
  const url = new URL(request.url)
  url.search = ''
  url.hash = ''
  return url.href
}

const openCache = async (cacheName) => {
  try {
    return await caches.open(cacheName)
  } catch (error) {
    handleRecoverableCacheFailure('open', error)
    return null
  }
}

const readCache = async (cache, key) => {
  if (!cache) return undefined
  try {
    return await cache.match(key)
  } catch (error) {
    handleRecoverableCacheFailure('read', error)
    return undefined
  }
}

const writeCache = async (cache, key, response) => {
  if (!cache || !response.ok) return
  try {
    await cache.put(key, response.clone())
  } catch (error) {
    handleRecoverableCacheFailure('write', error)
  }
}

const cacheFirst = async (request, cacheName) => {
  const cache = await openCache(cacheName)
  const key = normalizedCacheKey(request)
  const cached = await readCache(cache, key)
  if (cached) return cached
  const response = await fetch(request)
  await writeCache(cache, key, response)
  return response
}

const networkFirst = async (request, cacheName, cacheKey) => {
  const cache = await openCache(cacheName)
  const key = cacheKey ?? normalizedCacheKey(request)
  try {
    const response = await fetch(request)
    await writeCache(cache, key, response)
    return response
  } catch (error) {
    const cached = await readCache(cache, key)
    if (cached) return cached
    throw error
  }
}

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (url.origin !== self.location.origin) return
  if (event.request.method !== 'GET') return
  if (url.pathname.startsWith('/api/')) return
  if (url.pathname.startsWith('/ws/')) return
  if (url.pathname === '/sw.js' || url.pathname === '/manifest.webmanifest') return
  if (isShell(url.pathname)) {
    event.respondWith(networkFirst(event.request, SHELL_CACHE, `${url.origin}/`))
    return
  }
  if (isHashedAsset(url.pathname)) {
    event.respondWith(cacheFirst(event.request, ASSETS_CACHE))
    return
  }
  if (isStaticAsset(url.pathname)) {
    event.respondWith(cacheFirst(event.request, STATIC_CACHE))
  }
})
