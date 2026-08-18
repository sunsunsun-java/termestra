// Marketplace data hook — fetches the bundled manifest/agent for the current UI
// language. Both caches have explicit LRU bounds so a long-lived UI session
// cannot retain every opened body forever.

import { useCallback, useEffect, useRef, useState } from 'react'

import {
  fetchMarketplaceAgent,
  fetchMarketplaceManifest,
  type MarketplaceAgentDetail,
  type MarketplaceLanguage,
  type MarketplaceManifest,
} from '../api.js'
import { BoundedLruMap } from '../lib/bounded-lru-map.js'

interface ManifestState {
  status: 'idle' | 'loading' | 'loaded' | 'error'
  data: MarketplaceManifest | null
  error: string | null
}

const emptyState: ManifestState = { status: 'idle', data: null, error: null }
const MAX_MANIFEST_CACHE_ENTRIES = 2
const MAX_AGENT_CACHE_ENTRIES = 64

export const useMarketplace = (language: MarketplaceLanguage, enabled: boolean) => {
  const [manifestState, setManifestState] = useState<ManifestState>(emptyState)
  const manifestCache = useRef(
    new BoundedLruMap<MarketplaceLanguage, MarketplaceManifest>(MAX_MANIFEST_CACHE_ENTRIES)
  )
  const agentCache = useRef(
    new BoundedLruMap<string, Promise<MarketplaceAgentDetail>>(MAX_AGENT_CACHE_ENTRIES)
  )

  useEffect(() => {
    if (!enabled) return
    const cached = manifestCache.current.get(language)
    if (cached) {
      setManifestState({ status: 'loaded', data: cached, error: null })
      return
    }
    setManifestState({ status: 'loading', data: null, error: null })
    let cancelled = false
    fetchMarketplaceManifest(language)
      .then((data) => {
        if (cancelled) return
        manifestCache.current.set(language, data)
        setManifestState({ status: 'loaded', data, error: null })
      })
      .catch((error: unknown) => {
        if (cancelled) return
        setManifestState({
          status: 'error',
          data: null,
          error: error instanceof Error ? error.message : 'unknown',
        })
      })
    return () => {
      cancelled = true
    }
  }, [enabled, language])

  const loadAgent = useCallback(
    async (path: string): Promise<MarketplaceAgentDetail> => {
      const cacheKey = `${language}::${path}`
      const cached = agentCache.current.get(cacheKey)
      if (cached) return cached
      const request = fetchMarketplaceAgent(language, path)
      agentCache.current.set(cacheKey, request)
      try {
        return await request
      } catch (error) {
        agentCache.current.delete(cacheKey)
        throw error
      }
    },
    [language]
  )

  return { manifestState, loadAgent }
}
