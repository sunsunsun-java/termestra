import { ChevronLeft, ChevronRight } from 'lucide-react'
import type { ReactNode } from 'react'

import { useI18n } from '../i18n.js'
import { Tooltip } from '../ui/Tooltip.js'
import { Topbar } from './Topbar.js'
import {
  useWorkspaceSidebarResize,
  WORKSPACE_SIDEBAR_MAX,
  WORKSPACE_SIDEBAR_MIN,
} from './useWorkspaceSidebarResize.js'

type MainLayoutProps = {
  children: ReactNode
  hideTopbarActions?: boolean
  onToggleTaskGraph?: () => void
  openTaskCount?: number
  sidebar: ReactNode
  taskGraphOpen?: boolean
  topbarActions?: ReactNode
}

export const MainLayout = ({
  children,
  hideTopbarActions = false,
  onToggleTaskGraph,
  openTaskCount = 0,
  sidebar,
  taskGraphOpen = false,
  topbarActions,
}: MainLayoutProps) => {
  const { t } = useI18n()
  const sidebarResize = useWorkspaceSidebarResize()

  return (
    <div
      className="flex h-screen w-full flex-col overflow-hidden"
      style={{ background: 'var(--bg-0)', color: 'var(--text-primary)' }}
    >
      <Topbar
        actions={topbarActions}
        hideActions={hideTopbarActions}
        onToggleTaskGraph={onToggleTaskGraph}
        openTaskCount={openTaskCount}
        taskGraphOpen={taskGraphOpen}
      />
      <div className="flex min-h-0 flex-1">
        <aside
          aria-label={t('layout.sidebarAria')}
          className="workspace-sidebar relative flex shrink-0 flex-col"
          data-resizing={sidebarResize.resizing ? 'true' : 'false'}
          style={{
            background: 'var(--bg-0)',
            boxShadow: 'inset -1px 0 0 var(--border)',
            width: `${sidebarResize.width}px`,
          }}
        >
          {sidebar}
          <Tooltip
            side="right"
            label={
              sidebarResize.collapsed
                ? t('layout.sidebarExpandAria')
                : t('layout.sidebarCollapseAria')
            }
          >
            <button
              type="button"
              aria-expanded={!sidebarResize.collapsed}
              aria-label={
                sidebarResize.collapsed
                  ? t('layout.sidebarExpandAria')
                  : t('layout.sidebarCollapseAria')
              }
              className="workspace-sidebar-toggle icon-btn flex h-7 w-7 items-center justify-center text-ter hover:text-pri"
              data-testid="workspace-sidebar-toggle"
              onClick={sidebarResize.toggleCollapsed}
            >
              {sidebarResize.collapsed ? (
                <ChevronRight size={15} aria-hidden />
              ) : (
                <ChevronLeft size={15} aria-hidden />
              )}
            </button>
          </Tooltip>
          <hr
            aria-label={t('layout.sidebarResizeAria')}
            aria-orientation="vertical"
            aria-valuemin={WORKSPACE_SIDEBAR_MIN}
            aria-valuemax={WORKSPACE_SIDEBAR_MAX}
            aria-valuenow={Math.round(sidebarResize.width)}
            tabIndex={0}
            className="workspace-sidebar-resizer"
            data-resizing={sidebarResize.resizing ? 'true' : 'false'}
            onMouseDown={sidebarResize.beginResize}
            onKeyDown={sidebarResize.onResizeKeyDown}
          />
        </aside>
        <section className="relative flex min-w-0 flex-1">{children}</section>
      </div>
    </div>
  )
}
