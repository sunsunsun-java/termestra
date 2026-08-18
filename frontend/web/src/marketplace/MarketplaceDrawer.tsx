import * as Dialog from '@radix-ui/react-dialog'
import { Search, X } from 'lucide-react'
import { useEffect, useMemo, useReducer } from 'react'

import type { MarketplaceAgentEntry, MarketplaceManifest } from '../api.js'
import { useI18n } from '../i18n.js'
import { sortCategoriesForDisplay } from './categoryLabels.js'
import { MarketplaceAgentCard } from './MarketplaceAgentCard.js'
import { MarketplaceAgentPreview } from './MarketplaceAgentPreview.js'
import { MarketplaceCategoryTree } from './MarketplaceCategoryTree.js'
import { useMarketplace } from './useMarketplace.js'

interface MarketplaceDrawerProps {
  open: boolean
  onClose: () => void
  onImport: (detail: { name: string; description: string }) => void
  importedNames?: ReadonlySet<string>
}

const CORE_CATEGORIES = new Set([
  'engineering',
  'design',
  'product',
  'testing',
  'project-management',
  'specialized',
  'integrations',
])

interface BrowserState {
  query: string
  selectedAgent: MarketplaceAgentEntry | null
  selectedCategory: string | null
  showAllCategories: boolean
}

type BrowserAction =
  | { type: 'catalog-changed' }
  | { type: 'query-changed'; query: string }
  | { type: 'category-selected'; category: string | null }
  | { type: 'agent-selected'; agent: MarketplaceAgentEntry }
  | { type: 'category-scope-toggled' }
  | { type: 'agent-imported' }

const initialBrowserState: BrowserState = {
  query: '',
  selectedAgent: null,
  selectedCategory: null,
  showAllCategories: true,
}

const reduceBrowser = (state: BrowserState, action: BrowserAction): BrowserState => {
  switch (action.type) {
    case 'catalog-changed':
      return { ...initialBrowserState, showAllCategories: state.showAllCategories }
    case 'query-changed':
      return { ...state, query: action.query }
    case 'category-selected':
      return { ...state, query: '', selectedAgent: null, selectedCategory: action.category }
    case 'agent-selected':
      return { ...state, selectedAgent: action.agent }
    case 'agent-imported':
      return { ...state, selectedAgent: null }
    case 'category-scope-toggled': {
      const showAllCategories = !state.showAllCategories
      if (showAllCategories) return { ...state, showAllCategories }
      return {
        ...state,
        selectedAgent:
          state.selectedAgent && CORE_CATEGORIES.has(state.selectedAgent.category)
            ? state.selectedAgent
            : null,
        selectedCategory:
          state.selectedCategory && CORE_CATEGORIES.has(state.selectedCategory)
            ? state.selectedCategory
            : null,
        showAllCategories,
      }
    }
  }
}

interface CatalogView {
  agents: MarketplaceAgentEntry[]
  categoryCounts: Record<string, number>
  hiddenCategoryCount: number
  visibleCategories: readonly string[]
}

const countAgentsByCategory = (agents: MarketplaceAgentEntry[]): Record<string, number> => {
  const counts: Record<string, number> = {}
  for (const { category } of agents) counts[category] = (counts[category] ?? 0) + 1
  return counts
}

const selectCatalogView = (
  manifest: MarketplaceManifest | null,
  state: BrowserState,
  language: 'en' | 'zh'
): CatalogView => {
  if (!manifest) {
    return { agents: [], categoryCounts: {}, hiddenCategoryCount: 0, visibleCategories: [] }
  }

  const categoryScope = state.showAllCategories
    ? manifest.categories
    : manifest.categories.filter((category) => CORE_CATEGORIES.has(category))
  const visibleCategories = sortCategoriesForDisplay(categoryScope, language)
  const search = state.query.trim().toLowerCase()
  const agents = manifest.agents.filter((agent) => {
    const categoryMatches = state.selectedCategory
      ? agent.category === state.selectedCategory
      : state.showAllCategories || CORE_CATEGORIES.has(agent.category)
    if (!categoryMatches) return false
    if (!search) return true
    return (
      agent.name.toLowerCase().includes(search) || agent.description.toLowerCase().includes(search)
    )
  })

  return {
    agents,
    categoryCounts: countAgentsByCategory(manifest.agents),
    hiddenCategoryCount: manifest.categories.length - visibleCategories.length,
    visibleCategories,
  }
}

type Translator = ReturnType<typeof useI18n>['t']

const SourceAttribution = ({ repo, t }: { repo: string; t: Translator }) => {
  const marker = '__marketplace_repository__'
  const [prefix, suffix = ''] = t('marketplace.sourceLabel', { repo: marker }).split(marker)
  return (
    <>
      {prefix}
      <span className="mono">{repo}</span>
      {suffix}
    </>
  )
}

interface DrawerHeaderProps {
  manifest: MarketplaceManifest | null
  query: string
  onQueryChange: (query: string) => void
  t: Translator
}

const DrawerHeader = ({ manifest, onQueryChange, query, t }: DrawerHeaderProps) => (
  <header
    className="flex shrink-0 items-center justify-between gap-4 border-b px-5 py-4"
    style={{ borderColor: 'var(--border)' }}
  >
    <div className="flex flex-col gap-0.5">
      <Dialog.Title className="text-lg font-semibold text-pri">
        {t('marketplace.title')}
      </Dialog.Title>
      <Dialog.Description className="text-xs text-ter">
        {manifest ? <SourceAttribution repo={manifest.source.repo} t={t} /> : ' '}
      </Dialog.Description>
    </div>
    <div className="flex items-center gap-2">
      <div className="relative flex w-72 items-center">
        <Search
          size={14}
          aria-hidden
          className="pointer-events-none absolute left-3 text-ter"
        />
        <input
          type="search"
          className="input"
          data-testid="marketplace-search"
          onChange={(event) => onQueryChange(event.currentTarget.value)}
          placeholder={t('marketplace.searchPlaceholder')}
          style={{ paddingLeft: '36px' }}
          value={query}
        />
      </div>
      <Dialog.Close asChild>
        <button
          type="button"
          aria-label={t('marketplace.close')}
          className="flex h-7 w-7 items-center justify-center rounded text-sec hover:bg-3 hover:text-pri"
          data-testid="marketplace-close"
        >
          <X size={14} aria-hidden />
        </button>
      </Dialog.Close>
    </div>
  </header>
)

interface AgentGridProps {
  agents: MarketplaceAgentEntry[]
  error: string | null
  importedNames: ReadonlySet<string> | undefined
  onSelect: (agent: MarketplaceAgentEntry) => void
  selectedAgent: MarketplaceAgentEntry | null
  status: 'idle' | 'loading' | 'loaded' | 'error'
  t: Translator
}

const AgentGrid = ({
  agents,
  error,
  importedNames,
  onSelect,
  selectedAgent,
  status,
  t,
}: AgentGridProps) => {
  if (status === 'loading') {
    return <div className="flex h-full items-center justify-center text-sm text-ter">{t('marketplace.loading')}</div>
  }
  if (status === 'error') {
    return (
      <div className="flex h-full items-center justify-center text-sm text-ter">
        {t('marketplace.loadFailed')}: {error}
      </div>
    )
  }
  if (agents.length === 0) {
    return <div className="flex h-full items-center justify-center text-sm text-ter">{t('marketplace.empty')}</div>
  }

  return (
    <div className="grid gap-3" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))' }}>
      {agents.map((agent) => (
        <MarketplaceAgentCard
          key={agent.path}
          agent={agent}
          imported={importedNames?.has(agent.name) ?? false}
          onSelect={() => onSelect(agent)}
          selected={selectedAgent?.path === agent.path}
        />
      ))}
    </div>
  )
}

export const MarketplaceDrawer = ({
  importedNames,
  onClose,
  onImport,
  open,
}: MarketplaceDrawerProps) => {
  const { language, t } = useI18n()
  const { loadAgent, manifestState } = useMarketplace(language, open)
  const [browser, dispatch] = useReducer(reduceBrowser, initialBrowserState)
  const manifest = manifestState.data

  useEffect(() => {
    dispatch({ type: 'catalog-changed' })
  }, [language])

  const catalog = useMemo(
    () => selectCatalogView(manifest, browser, language),
    [browser, language, manifest]
  )
  const importAgent = (detail: { name: string; description: string }) => {
    onImport(detail)
    dispatch({ type: 'agent-imported' })
    onClose()
  }

  return (
    <Dialog.Root open={open} onOpenChange={(nextOpen) => !nextOpen && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay data-testid="marketplace-overlay" className="app-overlay fixed inset-0 z-40" />
        <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center p-4">
          <Dialog.Content
            className="dialog-scale-pop elev-2 pointer-events-auto flex w-full flex-col rounded-lg border"
            data-testid="marketplace-content"
            style={{
              background: 'var(--bg-elevated)',
              borderColor: 'var(--border-bright)',
              height: 'min(820px, calc(100vh - 48px))',
              width: 'min(1280px, calc(100vw - 32px))',
            }}
          >
            <DrawerHeader
              manifest={manifest}
              onQueryChange={(query) => dispatch({ type: 'query-changed', query })}
              query={browser.query}
              t={t}
            />

            <div
              className="grid min-h-0 flex-1 divide-x transition-[grid-template-columns] duration-200 ease-out"
              style={{
                borderColor: 'var(--border)',
                gridTemplateColumns: browser.selectedAgent
                  ? '200px minmax(0, 1fr) 380px'
                  : '200px minmax(0, 1fr)',
              }}
            >
              <aside className="scroll-y min-h-0 px-4 py-3">
                {manifest ? (
                  <MarketplaceCategoryTree
                    categories={catalog.visibleCategories}
                    counts={catalog.categoryCounts}
                    hiddenCount={catalog.hiddenCategoryCount}
                    onSelect={(category) => dispatch({ type: 'category-selected', category })}
                    onToggleShowAll={() => dispatch({ type: 'category-scope-toggled' })}
                    selected={browser.selectedCategory}
                    showAll={browser.showAllCategories}
                  />
                ) : null}
              </aside>

              <section
                key={browser.selectedCategory ?? '__all__'}
                className="scroll-y min-h-0 px-4 py-3"
                data-testid="marketplace-agent-grid"
              >
                <AgentGrid
                  agents={catalog.agents}
                  error={manifestState.error}
                  importedNames={importedNames}
                  onSelect={(selectedAgent) =>
                    dispatch({ type: 'agent-selected', agent: selectedAgent })
                  }
                  selectedAgent={browser.selectedAgent}
                  status={manifestState.status}
                  t={t}
                />
              </section>

              {browser.selectedAgent && manifest ? (
                <section className="min-h-0">
                  <MarketplaceAgentPreview
                    agent={browser.selectedAgent}
                    loadAgent={loadAgent}
                    onImport={importAgent}
                    sourceRepo={manifest.source.repo}
                  />
                </section>
              ) : null}
            </div>
          </Dialog.Content>
        </div>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
