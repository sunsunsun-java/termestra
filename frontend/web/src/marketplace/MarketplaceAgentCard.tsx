import { Bookmark } from 'lucide-react'
import type { CSSProperties } from 'react'

import type { MarketplaceAgentEntry } from '../api.js'
import { useI18n } from '../i18n.js'

interface MarketplaceAgentCardProps {
  agent: MarketplaceAgentEntry
  selected: boolean
  imported: boolean
  onSelect: () => void
}

interface CardPresentation {
  displayName: string
  tagline: string
  title: string | undefined
}

const presentAgent = (agent: MarketplaceAgentEntry): CardPresentation => ({
  displayName: agent.displayName ?? agent.name,
  tagline: agent.vibe?.trim() ? agent.vibe : agent.description,
  title: agent.nameOverflows ? (agent.displayName ?? agent.name) : undefined,
})

const cardStyle = (selected: boolean): CSSProperties => ({
  background: selected
    ? 'color-mix(in oklab, var(--accent) 14%, var(--bg-2))'
    : 'var(--bg-2)',
  borderColor: selected ? 'var(--accent)' : 'var(--border-bright)',
  ['--tw-ring-color' as string]: 'color-mix(in oklab, var(--accent) 55%, transparent)',
})

const badgeStyle = (selected: boolean): CSSProperties => ({
  background: selected
    ? 'var(--accent)'
    : 'color-mix(in oklab, var(--accent) 28%, transparent)',
  color: selected ? '#ffffff' : 'color-mix(in oklab, var(--accent) 60%, white)',
})

export const MarketplaceAgentCard = ({
  agent,
  imported,
  onSelect,
  selected,
}: MarketplaceAgentCardProps) => {
  const { t } = useI18n()
  const presentation = presentAgent(agent)
  const importedLabel = t('marketplace.importedBadge')

  return (
    <button
      type="button"
      className="marketplace-card flex w-full cursor-pointer flex-col gap-1.5 rounded-md border px-3 py-2.5 text-left outline-none transition-[background,border-color,transform] duration-100 ease-out focus-visible:ring-2 focus-visible:ring-offset-0 active:translate-y-px"
      data-agent-path={agent.path}
      data-imported={imported ? 'true' : undefined}
      data-selected={selected ? 'true' : undefined}
      data-testid="marketplace-agent-card"
      onClick={onSelect}
      style={cardStyle(selected)}
    >
      <span className="flex items-center justify-between gap-2">
        <span className="flex min-w-0 items-center gap-2">
          {agent.emoji ? <span className="text-base leading-none">{agent.emoji}</span> : null}
          <span className="truncate text-sm font-semibold text-pri" title={presentation.title}>
            {presentation.displayName}
          </span>
        </span>
        {imported ? (
          <span
            role="img"
            aria-label={importedLabel}
            className="flex shrink-0 items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wider"
            data-testid="marketplace-agent-imported"
            style={badgeStyle(selected)}
            title={importedLabel}
          >
            <Bookmark size={10} aria-hidden />
          </span>
        ) : null}
      </span>
      <span className="line-clamp-1 text-xs leading-snug text-sec">{presentation.tagline}</span>
    </button>
  )
}
