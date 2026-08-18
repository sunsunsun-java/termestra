import DOMPurify from 'isomorphic-dompurify'
import { ExternalLink } from 'lucide-react'
import { marked } from 'marked'
import { useEffect, useMemo, useReducer } from 'react'

import type { MarketplaceAgentDetail, MarketplaceAgentEntry } from '../api.js'
import { useI18n } from '../i18n.js'

marked.setOptions({ breaks: false, gfm: true })

interface MarketplaceAgentPreviewProps {
  agent: MarketplaceAgentEntry
  sourceRepo: string
  loadAgent: (path: string) => Promise<MarketplaceAgentDetail>
  onImport: (detail: { name: string; description: string }) => void
}

type DocumentState =
  | { status: 'loading'; detail: null; error: null }
  | { status: 'loaded'; detail: MarketplaceAgentDetail; error: null }
  | { status: 'error'; detail: null; error: string }

type DocumentAction =
  | { type: 'request' }
  | { type: 'resolved'; detail: MarketplaceAgentDetail }
  | { type: 'rejected'; error: string }

const loadingState: DocumentState = { status: 'loading', detail: null, error: null }

const reduceDocument = (_state: DocumentState, action: DocumentAction): DocumentState => {
  switch (action.type) {
    case 'request':
      return loadingState
    case 'resolved':
      return { status: 'loaded', detail: action.detail, error: null }
    case 'rejected':
      return { status: 'error', detail: null, error: action.error }
  }
}

const errorMessage = (error: unknown): string =>
  error instanceof Error ? error.message : 'unknown'

const useAgentDocument = (
  path: string,
  loadAgent: (path: string) => Promise<MarketplaceAgentDetail>
): DocumentState => {
  const [state, dispatch] = useReducer(reduceDocument, loadingState)

  useEffect(() => {
    let active = true
    dispatch({ type: 'request' })
    void loadAgent(path).then(
      (detail) => {
        if (active) dispatch({ type: 'resolved', detail })
      },
      (error: unknown) => {
        if (active) dispatch({ type: 'rejected', error: errorMessage(error) })
      }
    )
    return () => {
      active = false
    }
  }, [loadAgent, path])

  return state
}

const sanitizedMarkdown = (body: string): string => {
  const parsed = marked.parse(body, { async: false })
  if (typeof parsed !== 'string') {
    throw new TypeError('Marketplace markdown parser returned an asynchronous result')
  }
  return DOMPurify.sanitize(parsed, {
    ALLOWED_ATTR: ['href', 'name', 'target', 'rel', 'title', 'class', 'id'],
    USE_PROFILES: { html: true },
  })
}

export const MarketplaceAgentPreview = ({
  agent,
  loadAgent,
  onImport,
  sourceRepo,
}: MarketplaceAgentPreviewProps) => {
  const { t } = useI18n()
  const document = useAgentDocument(agent.path, loadAgent)
  const html = useMemo(
    () => (document.status === 'loaded' ? sanitizedMarkdown(document.detail.body) : null),
    [document]
  )
  const sourceUrl = `https://github.com/${sourceRepo}/blob/HEAD/${agent.path}`
  const importDocument = () => {
    if (document.status !== 'loaded') return
    onImport({ name: agent.name, description: document.detail.body.trim() })
  }

  return (
    <article
      className="flex h-full flex-col gap-3 border-l px-4 py-3"
      data-testid="marketplace-agent-preview"
      style={{ borderColor: 'var(--border)' }}
    >
      <header className="flex min-w-0 flex-col gap-1">
        <div className="flex min-w-0 items-center gap-2">
          {agent.emoji ? (
            <span className="shrink-0 text-lg leading-none">{agent.emoji}</span>
          ) : null}
          <h3 className="min-w-0 break-words text-base font-semibold text-pri">{agent.name}</h3>
        </div>
        <p className="text-xs text-ter">{agent.description}</p>
      </header>

      <div
        key={agent.path}
        className="scroll-y min-h-0 flex-1 rounded px-3 py-2 text-xs leading-relaxed"
        style={{ background: 'var(--bg-2)' }}
      >
        {document.status === 'loading' ? <p className="text-ter">…</p> : null}
        {document.status === 'error' ? (
          <p className="text-ter">
            {t('marketplace.loadFailed')}: {document.error}
          </p>
        ) : null}
        {document.status === 'loaded' && html ? (
          <div
            className="marketplace-prose"
            // biome-ignore lint/security/noDangerouslySetInnerHtml: sanitized above with a restricted attribute allowlist
            dangerouslySetInnerHTML={{ __html: html }}
          />
        ) : null}
      </div>

      <footer className="flex items-center justify-between gap-2">
        <a
          className="inline-flex items-center gap-1 text-xs text-sec transition-colors hover:text-pri"
          href={sourceUrl}
          rel="noreferrer noopener"
          target="_blank"
        >
          {t('marketplace.viewSource')}
          <ExternalLink size={11} aria-hidden />
        </a>
        <button
          type="button"
          className="icon-btn icon-btn--primary"
          data-testid="marketplace-import-button"
          disabled={document.status !== 'loaded'}
          onClick={importDocument}
        >
          {t('marketplace.importButton')}
        </button>
      </footer>
    </article>
  )
}
