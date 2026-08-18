const CACHE_PREFIX = 'termestra-cache-v'
const CURRENT_CACHE_SUFFIXES = ['shell', 'assets', 'static'] as const

export const cacheNamesToDelete = (
  cacheNames: readonly string[],
  currentGeneration: string
): string[] => {
  const currentCacheNames = new Set(
    CURRENT_CACHE_SUFFIXES.map((suffix) => `${CACHE_PREFIX}${currentGeneration}-${suffix}`)
  )
  return cacheNames.filter(
    (cacheName) => cacheName.startsWith(CACHE_PREFIX) && !currentCacheNames.has(cacheName)
  )
}
