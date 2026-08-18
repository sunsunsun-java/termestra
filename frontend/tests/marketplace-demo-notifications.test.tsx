// @vitest-environment jsdom

import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import type { TeamListItem, WorkspaceSummary } from '../src/shared/types.js'
import type { MarketplaceAgentEntry } from '../web/src/api.js'
import { DemoBanner } from '../web/src/demo/DemoBanner.js'
import { DEMO_WORKSPACE } from '../web/src/demo/demo-fixture.js'
import { useDemoMode } from '../web/src/demo/useDemoMode.js'
import { useEffectiveWorkspaceState } from '../web/src/demo/useEffectiveWorkspaceState.js'
import { I18nProvider } from '../web/src/i18n.js'
import { LanguageToggle } from '../web/src/layout/LanguageToggle.js'
import { MarketplaceAgentCard } from '../web/src/marketplace/MarketplaceAgentCard.js'
import { MarketplaceAgentPreview } from '../web/src/marketplace/MarketplaceAgentPreview.js'
import { MarketplaceDrawer } from '../web/src/marketplace/MarketplaceDrawer.js'
import {
  localizeMarketplaceCategory,
  sortCategoriesForDisplay,
} from '../web/src/marketplace/categoryLabels.js'
import { NotificationProvider } from '../web/src/notifications/NotificationProvider.js'
import { NotificationSettingsButton } from '../web/src/notifications/NotificationSettingsButton.js'
import { WorkspaceNotifications } from '../web/src/notifications/WorkspaceNotifications.js'
import { Toaster } from '../web/src/ui/toast.js'
import { ToastProvider } from '../web/src/ui/useToast.js'
import { UI_LANGUAGE_STORAGE_KEY } from '../web/src/uiLanguage.js'

const withI18n = (children: ReactNode) => <I18nProvider>{children}</I18nProvider>

const withNotifications = (children: ReactNode) => (
  <I18nProvider>
    <ToastProvider>
      <NotificationProvider>
        {children}
        <Toaster />
      </NotificationProvider>
    </ToastProvider>
  </I18nProvider>
)

const agent: MarketplaceAgentEntry = {
  category: 'engineering',
  color: null,
  description: 'Builds reliable systems',
  displayName: 'Platform Builder',
  emoji: '🛠️',
  name: 'platform-builder',
  nameOverflows: true,
  path: 'engineering/platform-builder.md',
  vibe: 'Calm and methodical',
}

const workspace: WorkspaceSummary = { id: 'ws-1', name: 'Alpha', path: '/work/alpha' }

const worker = (patch: Partial<TeamListItem> = {}): TeamListItem => ({
  id: 'worker-1',
  name: 'Ada',
  pendingTaskCount: 2,
  role: 'coder',
  status: 'working',
  ...patch,
})

beforeEach(() => {
  window.localStorage.clear()
  window.localStorage.setItem(UI_LANGUAGE_STORAGE_KEY, 'en')
  vi.spyOn(window.HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('demo workspace experience', () => {
  test('enters and leaves demo mode through its public actions', () => {
    const { result } = renderHook(() => useDemoMode())

    expect(result.current.demoMode).toBe(false)
    fireEvent.click(
      render(
        <button type="button" onClick={result.current.enableDemo}>
          Try demo
        </button>
      ).getByRole('button')
    )
    expect(result.current.demoMode).toBe(true)

    act(() => result.current.exitDemo())
    expect(result.current.demoMode).toBe(false)
  })

  test('projects demo fixtures without mutating the live workspace inputs', () => {
    const workspaces = [workspace]
    const workersByWorkspaceId = { [workspace.id]: [worker()] }
    const { result, rerender } = renderHook(
      (demoMode: boolean) =>
        useEffectiveWorkspaceState({
          activeWorkspaceId: workspace.id,
          demoMode,
          workersByWorkspaceId,
          workspaces,
        }),
      { initialProps: false }
    )

    expect(result.current.effectiveActiveWorkspace).toBe(workspace)
    expect(result.current.pollWorkspaceId).toBe(workspace.id)

    rerender(true)
    expect(result.current.effectiveActiveWorkspace).toBe(DEMO_WORKSPACE)
    expect(result.current.effectiveWorkspaces).toEqual([DEMO_WORKSPACE])
    expect(result.current.pollWorkspaceId).toBeNull()
    expect(workersByWorkspaceId).toEqual({ [workspace.id]: [worker()] })
  })

  test('announces that the demo is recorded and lets the user exit', () => {
    const onExit = vi.fn()
    render(withI18n(<DemoBanner onExit={onExit} />))

    expect(screen.getByRole('region', { name: 'Demo mode' }).textContent).toContain('pre-recorded')
    fireEvent.click(screen.getByRole('button', { name: 'Exit demo' }))
    expect(onExit).toHaveBeenCalledOnce()
  })
})

describe('marketplace browsing', () => {
  test('localizes known categories and gives unknown categories a readable fallback', () => {
    expect(localizeMarketplaceCategory('project-management', 'zh')).toBe('项目管理')
    expect(localizeMarketplaceCategory('release-engineering', 'en')).toBe('Release Engineering')
    expect(
      sortCategoriesForDisplay(['misc', 'design', 'engineering', 'testing'], 'zh')
    ).toEqual(['engineering', 'testing', 'design', 'misc'])
  })

  test('shows the curated card identity and exposes selection and import state', () => {
    const onSelect = vi.fn()
    render(
      withI18n(
        <MarketplaceAgentCard agent={agent} imported selected onSelect={onSelect} />
      )
    )

    const card = screen.getByTestId('marketplace-agent-card')
    expect(card.getAttribute('data-agent-path')).toBe(agent.path)
    expect(card.getAttribute('data-imported')).toBe('true')
    expect(card.getAttribute('data-selected')).toBe('true')
    expect(card.textContent).toContain('Platform Builder')
    expect(card.textContent).toContain('Calm and methodical')
    expect(screen.getByRole('img', { name: 'You have a saved template with this name' })).not.toBeNull()
    fireEvent.click(card)
    expect(onSelect).toHaveBeenCalledOnce()
  })

  test('renders sanitized role instructions and imports their trimmed source body', async () => {
    const onImport = vi.fn()
    const loadAgent = vi.fn().mockResolvedValue({
      body: '# Operating guide\n\n[unsafe](javascript:alert(1))<script>boom()</script>',
      frontmatter: {},
      path: agent.path,
    })
    render(
      withI18n(
        <MarketplaceAgentPreview
          agent={agent}
          loadAgent={loadAgent}
          onImport={onImport}
          sourceRepo="termestra/roles"
        />
      )
    )

    expect(screen.getByTestId('marketplace-import-button').hasAttribute('disabled')).toBe(true)
    expect(await screen.findByRole('heading', { name: 'Operating guide' })).not.toBeNull()
    expect(screen.queryByText('boom()')).toBeNull()
    expect(screen.getByText('unsafe').closest('a')?.hasAttribute('href')).toBe(false)

    fireEvent.click(screen.getByTestId('marketplace-import-button'))
    expect(onImport).toHaveBeenCalledWith({
      description: '# Operating guide\n\n[unsafe](javascript:alert(1))<script>boom()</script>',
      name: 'platform-builder',
    })
    expect(screen.getByRole('link', { name: /View on GitHub/ }).getAttribute('href')).toBe(
      'https://github.com/termestra/roles/blob/HEAD/engineering/platform-builder.md'
    )
  })

  test('searches the loaded catalog and closes after importing a role', async () => {
    const manifest = {
      agents: [agent],
      categories: ['engineering'],
      language: 'en',
      source: { commit: 'abc', fetched_at: '2026-08-12', repo: 'termestra/roles' },
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.startsWith('/api/marketplace/manifest')) {
        return new Response(JSON.stringify(manifest), {
          headers: { 'content-type': 'application/json' },
          status: 200,
        })
      }
      if (url.startsWith('/api/marketplace/agent')) {
        return new Response(
          JSON.stringify({ body: '  Coordinate the platform team.  ', frontmatter: {}, path: agent.path }),
          { headers: { 'content-type': 'application/json' }, status: 200 }
        )
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const onClose = vi.fn()
    const onImport = vi.fn()
    render(
      withI18n(
        <MarketplaceDrawer open onClose={onClose} onImport={onImport} />
      )
    )

    expect(await screen.findByText('Platform Builder')).not.toBeNull()
    fireEvent.change(screen.getByTestId('marketplace-search'), {
      target: { value: 'reliable' },
    })
    fireEvent.click(screen.getByTestId('marketplace-agent-card'))
    await waitFor(() =>
      expect(screen.getByTestId('marketplace-import-button').hasAttribute('disabled')).toBe(false)
    )
    fireEvent.click(screen.getByTestId('marketplace-import-button'))

    expect(onImport).toHaveBeenCalledWith({
      description: 'Coordinate the platform team.',
      name: 'platform-builder',
    })
    expect(onClose).toHaveBeenCalledOnce()
  })
})

describe('notification and language controls', () => {
  test('persists selected notification detail and uses it for a test alert', async () => {
    render(withNotifications(<NotificationSettingsButton />))

    fireEvent.click(screen.getByRole('button', { name: 'Notification settings' }))
    fireEvent.click(screen.getByDisplayValue('detailed'))
    fireEvent.click(screen.getByDisplayValue('off'))
    fireEvent.click(screen.getByRole('button', { name: 'Test' }))

    expect(
      await screen.findByText(
        'Termestra notifications are working with your selected sound and detail level.'
      )
    ).not.toBeNull()
    await waitFor(() => {
      expect(window.localStorage.getItem('termestra.notification.settings')).toContain(
        '"detail":"detailed"'
      )
    })
  })

  test('notifies only after a worker transition and classifies report and stop events', async () => {
    const view = render(
      withNotifications(
        <WorkspaceNotifications terminalRuns={[]} workers={[worker()]} workspace={workspace} />
      )
    )
    expect(screen.queryByTestId('toast')).toBeNull()

    view.rerender(
      withNotifications(
        <WorkspaceNotifications
          terminalRuns={[]}
          workers={[worker({ pendingTaskCount: 1, status: 'idle' })]}
          workspace={workspace}
        />
      )
    )
    expect(await screen.findByText('Ada reported')).not.toBeNull()

    view.rerender(
      withNotifications(
        <WorkspaceNotifications
          terminalRuns={[]}
          workers={[worker({ pendingTaskCount: 1, status: 'stopped' })]}
          workspace={workspace}
        />
      )
    )
    expect(await screen.findByText('Ada stopped')).not.toBeNull()
  })

  test('switches language immediately and persists the choice', () => {
    render(withI18n(<LanguageToggle />))

    const toggle = screen.getByRole('button', { name: 'Switch language to 中文' })
    expect(toggle.textContent).toBe('EN')
    fireEvent.click(toggle)

    expect(screen.getByRole('button', { name: '切换语言到 English' }).textContent).toBe('中')
    expect(window.localStorage.getItem(UI_LANGUAGE_STORAGE_KEY)).toBe('zh')
  })
})
