import { LoaderCircle, Plus, Terminal as TerminalIcon, X } from 'lucide-react'
import { type KeyboardEvent as ReactKeyboardEvent, useRef } from 'react'

import { useI18n } from '../i18n.js'
import { Tooltip } from '../ui/Tooltip.js'
import type { TerminalTab } from './useTerminalPanelTabs.js'

type TerminalTabsProps = {
  activeId: string | null
  newShellPending: boolean
  onClose: (tabId: string) => void
  onClosePanel: () => void
  onNewShell: () => void
  onSelect: (tabId: string) => void
  tabs: readonly TerminalTab[]
}

type TabButtonMap = Map<string, HTMLButtonElement>

const nextTabIndex = (key: string, current: number, count: number): number | null => {
  if (count === 0) return null
  if (key === 'Home') return 0
  if (key === 'End') return count - 1
  if (key === 'ArrowLeft') return (current - 1 + count) % count
  if (key === 'ArrowRight') return (current + 1) % count
  return null
}

/** A scrollable, keyboard-navigable terminal tab strip. */
export const TerminalTabs = ({
  activeId,
  newShellPending,
  onClose,
  onClosePanel,
  onNewShell,
  onSelect,
  tabs,
}: TerminalTabsProps) => {
  const { t } = useI18n()
  const tabButtons = useRef<TabButtonMap>(new Map())

  const handleNavigation = (
    event: ReactKeyboardEvent<HTMLButtonElement>,
    currentIndex: number
  ): void => {
    const targetIndex = nextTabIndex(event.key, currentIndex, tabs.length)
    if (targetIndex === null) return

    const target = tabs[targetIndex]
    if (!target) return
    event.preventDefault()
    onSelect(target.id)
    tabButtons.current.get(target.id)?.focus()
  }

  return (
    <div
      aria-label={t('terminalPanel.tablistAria')}
      className="scrollbar-thin flex h-9 min-h-9 w-full items-stretch overflow-x-auto"
      data-testid="terminal-tab-strip"
      role="tablist"
      style={{ background: 'var(--bg-2)', borderBottom: '1px solid var(--border)' }}
    >
      {tabs.map((tab, index) => {
        const selected = tab.id === activeId
        const closeLabel = t('terminalPanel.closeTab', { name: tab.label })

        return (
          <div
            className="group relative flex max-w-[200px] shrink-0 items-center gap-1.5 border-r text-xs"
            data-testid={`terminal-tab-${tab.id}`}
            key={tab.id}
            style={{
              background: selected ? 'var(--bg-1)' : 'transparent',
              borderRightColor: 'var(--border)',
              color: selected ? 'var(--text-primary)' : 'var(--text-secondary)',
            }}
          >
            {selected ? (
              <span
                aria-hidden
                className="pointer-events-none absolute top-0 right-0 left-0 h-0.5"
                data-tab-accent
                style={{ background: 'var(--accent)' }}
              />
            ) : null}
            <button
              aria-selected={selected}
              className="flex min-w-0 flex-1 cursor-pointer items-center gap-1.5 py-2 pr-1 pl-3 text-left"
              data-testid={`terminal-tab-select-${tab.id}`}
              onClick={() => onSelect(tab.id)}
              onKeyDown={(event) => handleNavigation(event, index)}
              ref={(node) => {
                if (node) tabButtons.current.set(tab.id, node)
                else tabButtons.current.delete(tab.id)
              }}
              role="tab"
              style={{ color: 'inherit' }}
              tabIndex={selected ? 0 : -1}
              type="button"
            >
              <TerminalIcon aria-hidden size={12} />
              <span className="truncate">{tab.label}</span>
            </button>
            <Tooltip label={closeLabel}>
              <button
                aria-label={closeLabel}
                className={`mr-1 rounded p-0.5 transition ${selected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}
                data-testid={`terminal-tab-close-${tab.id}`}
                onClick={(event) => {
                  event.stopPropagation()
                  onClose(tab.id)
                }}
                style={{ color: 'var(--text-secondary)' }}
                type="button"
              >
                <X aria-hidden size={12} />
              </button>
            </Tooltip>
          </div>
        )
      })}
      <div className="flex flex-1 items-center justify-end gap-1 px-2">
        <Tooltip label={t('terminalPanel.closePanel')}>
          <button
            aria-label={t('terminalPanel.closePanel')}
            className="flex h-6 w-6 shrink-0 items-center justify-center rounded border text-sec transition hover:text-pri disabled:opacity-50"
            data-testid="terminal-panel-close"
            onClick={onClosePanel}
            style={{ background: 'var(--bg-1)', borderColor: 'var(--border)' }}
            type="button"
          >
            <X aria-hidden size={12} />
          </button>
        </Tooltip>
        <Tooltip label={t('terminalPanel.newShell')}>
          <button
            aria-label={t('terminalPanel.newShell')}
            className="flex h-6 w-6 shrink-0 items-center justify-center rounded border text-sec transition hover:text-pri disabled:opacity-50"
            data-testid="terminal-tab-new-shell"
            disabled={newShellPending}
            onClick={onNewShell}
            style={{ background: 'var(--bg-1)', borderColor: 'var(--border)' }}
            type="button"
          >
            {newShellPending ? (
              <LoaderCircle aria-hidden className="animate-spin" size={12} />
            ) : (
              <Plus aria-hidden size={12} />
            )}
          </button>
        </Tooltip>
      </div>
    </div>
  )
}
