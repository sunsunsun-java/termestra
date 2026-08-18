import { LoaderCircle, Play, Terminal as TerminalIcon } from 'lucide-react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'

import { useI18n } from '../i18n.js'
import { TerminalTabs } from './TerminalTabs.js'
import { TERMINAL_PANEL_MIN_HEIGHT, useTerminalPanelHeight } from './useTerminalPanelHeight.js'
import type { TerminalTab } from './useTerminalPanelTabs.js'

type TerminalBottomPanelProps = {
  activeId: string | null
  newShellPending: boolean
  onClose: (tabId: string) => void
  onClosePanel: () => void
  onNewShell: () => void
  onSelect: (tabId: string) => void
  onStartWorker: (workerId: string) => void
  startingWorkerId: string | null
  tabs: readonly TerminalTab[]
}

const activeTabFrom = (
  tabs: readonly TerminalTab[],
  requestedId: string | null
): TerminalTab | null => tabs.find(({ id }) => id === requestedId) ?? tabs[0] ?? null

const isCloseShortcut = (event: ReactKeyboardEvent<HTMLDivElement>): boolean =>
  (event.metaKey || event.ctrlKey) &&
  !event.altKey &&
  !event.shiftKey &&
  event.key.toLowerCase() === 'w'

type TerminalContentProps = {
  onStartWorker: (workerId: string) => void
  startingWorkerId: string | null
  tab: TerminalTab
}

const TerminalContent = ({ onStartWorker, startingWorkerId, tab }: TerminalContentProps) => {
  const { t } = useI18n()

  if (tab.kind === 'shell') {
    return (
      <div
        id={`shell-pty-${tab.runId}`}
        className="flex h-full w-full"
        data-pty-slot="shell"
        data-testid={`terminal-panel-slot-shell-${tab.runId}`}
      />
    )
  }

  if (tab.runId) {
    return (
      <div
        id={`worker-pty-${tab.runId}`}
        className="flex h-full w-full"
        data-pty-slot="worker"
        data-testid={`terminal-panel-slot-worker-${tab.workerId}`}
      />
    )
  }

  const starting = startingWorkerId === tab.workerId
  return (
    <div
      className="flex h-full w-full flex-col items-center justify-center gap-3 px-6 text-center text-xs text-ter"
      data-testid="terminal-panel-stopped-worker"
    >
      <span className="flex items-center gap-2">
        <TerminalIcon size={14} aria-hidden />
        {t('terminalPanel.workerStopped', { name: tab.label })}
      </span>
      <button
        type="button"
        className="icon-btn icon-btn--primary"
        data-testid="terminal-panel-start-worker"
        disabled={starting}
        onClick={() => onStartWorker(tab.workerId)}
      >
        {starting ? (
          <LoaderCircle size={12} className="animate-spin" aria-hidden />
        ) : (
          <Play size={12} aria-hidden />
        )}
        {t(starting ? 'common.starting' : 'common.start')}
      </button>
    </div>
  )
}

/**
 * Owns the bottom-docked terminal chrome. PTY processes stay mounted by the
 * workspace-level terminal registry; this component only exposes the active
 * run's portal slot and can therefore switch tabs without rebuilding xterm.
 */
export const TerminalBottomPanel = ({
  activeId,
  newShellPending,
  onClose,
  onClosePanel,
  onNewShell,
  onSelect,
  onStartWorker,
  startingWorkerId,
  tabs,
}: TerminalBottomPanelProps) => {
  const { t } = useI18n()
  const panelSize = useTerminalPanelHeight()
  const activeTab = activeTabFrom(tabs, activeId)

  if (!activeTab) return null

  const closeVisibleTab = (event: ReactKeyboardEvent<HTMLDivElement>): void => {
    if (!isCloseShortcut(event)) return
    event.preventDefault()
    onClose(activeTab.id)
  }

  return (
    // biome-ignore lint/a11y/noStaticElementInteractions: keyboard shortcut applies while focus is anywhere in the terminal panel
    <div
      className="relative flex shrink-0 flex-col"
      data-testid="terminal-bottom-panel"
      onKeyDown={closeVisibleTab}
      style={{
        background: 'var(--bg-1)',
        borderTop: '1px solid var(--border)',
        height: panelSize.height,
      }}
      tabIndex={-1}
    >
      {/* biome-ignore lint/a11y/useSemanticElements: div is the pointer target for the horizontal splitter */}
      <div
        aria-label={t('terminalPanel.resizeAria')}
        aria-orientation="horizontal"
        aria-valuemin={TERMINAL_PANEL_MIN_HEIGHT}
        aria-valuenow={Math.round(panelSize.height)}
        className="absolute top-0 right-0 left-0 z-10 h-2 -translate-y-1 cursor-ns-resize"
        data-resizing={panelSize.dragging || undefined}
        data-testid="terminal-panel-resize-handle"
        onPointerDown={panelSize.beginDrag}
        role="separator"
        tabIndex={-1}
      />
      <TerminalTabs
        activeId={activeTab.id}
        newShellPending={newShellPending}
        onClose={onClose}
        onClosePanel={onClosePanel}
        onNewShell={onNewShell}
        onSelect={onSelect}
        tabs={tabs}
      />
      <div className="min-h-0 flex-1" style={{ background: 'var(--bg-crust)' }}>
        <TerminalContent
          onStartWorker={onStartWorker}
          startingWorkerId={startingWorkerId}
          tab={activeTab}
        />
      </div>
    </div>
  )
}
