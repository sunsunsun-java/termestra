import { lazy, Suspense, useEffect, useRef, useState } from 'react'

import type { TeamListItem, WorkspaceSummary } from '../../src/shared/types.js'
import {
  isWorkspaceShellRun,
  type OrchestratorStartResult,
  renameWorker,
  type TerminalRunSummary,
} from './api.js'
import { useI18n } from './i18n.js'
import { WorkspaceNotifications } from './notifications/WorkspaceNotifications.js'
import { TerminalBottomPanel } from './terminal/TerminalBottomPanel.js'
import { useTerminalPanelTabs } from './terminal/useTerminalPanelTabs.js'
import { findRunByAgentId } from './terminal/useTerminalRuns.js'
import { useWorkspaceShellLauncher } from './terminal/useWorkspaceShellLauncher.js'
import { useToast } from './ui/useToast.js'
import { usePaneSplit } from './usePaneSplit.js'
import { OrchestratorPane } from './worker/OrchestratorPane.js'
import { useOrchestratorPaneState } from './worker/useOrchestratorPaneState.js'
import type { WorkerActions } from './worker/useWorkerActions.js'
import { useWorkerComposer } from './worker/useWorkerComposer.js'
import { WelcomePane } from './worker/WelcomePane.js'
import { WorkersPane } from './worker/WorkersPane.js'

const AddWorkerDialog = lazy(() =>
  import('./worker/AddWorkerDialog.js').then((module) => ({ default: module.AddWorkerDialog }))
)
const WorkerModal = lazy(() =>
  import('./worker/WorkerModal.js').then((module) => ({ default: module.WorkerModal }))
)

type WorkspaceDetailProps = {
  onCreateWorker: WorkerActions['createWorker']
  onDeleteWorker: (workerId: string) => Promise<void>
  onDeleteWorkspace: (workspace: WorkspaceSummary) => Promise<void>
  onStartWorker: (workerId: string) => Promise<{ error: string | null; runId: string | null }>
  onOrchestratorResult: (workspaceId: string, result: OrchestratorStartResult) => void
  onRequestAddWorkspace: () => void
  onShellRunClosed?: ((workspaceId: string, runId: string) => void) | undefined
  onShellRunStarted?: ((workspaceId: string, run: TerminalRunSummary) => void) | undefined
  onTryDemo?: () => void
  welcomeDisabledReason?: string | undefined
  orchestratorAutostartError: string | null
  orchestratorAutostartRunId: string | null
  terminalRuns: TerminalRunSummary[]
  terminalRunsStale: boolean
  workers: TeamListItem[]
  workspace: WorkspaceSummary | undefined
}

export const WorkspaceDetail = ({
  onCreateWorker,
  onDeleteWorker,
  onDeleteWorkspace,
  onStartWorker,
  onOrchestratorResult,
  onRequestAddWorkspace,
  onShellRunClosed,
  onShellRunStarted,
  onTryDemo,
  welcomeDisabledReason,
  orchestratorAutostartError,
  orchestratorAutostartRunId,
  terminalRuns,
  terminalRunsStale,
  workers,
  workspace,
}: WorkspaceDetailProps) => {
  const { t } = useI18n()
  const [activeWorkerId, setActiveWorkerId] = useState<string | null>(null)
  const [composerOpen, setComposerOpen] = useState(false)
  const [deleteWorkerError, setDeleteWorkerError] = useState<string | null>(null)
  const [startWorkerError, setStartWorkerError] = useState<string | null>(null)
  const [startingWorkerId, setStartingWorkerId] = useState<string | null>(null)
  const [terminalPanelHidden, setTerminalPanelHidden] = useState(false)
  const workspaceId = workspace?.id ?? ''
  const workspaceIdRef = useRef(workspaceId)
  workspaceIdRef.current = workspaceId
  const deleteOperationsRef = useRef(new Set<string>())
  const startOperationRef = useRef<{ workspaceId: string; workerId: string } | null>(null)
  const toast = useToast()
  const composer = useWorkerComposer({
    createWorker: onCreateWorker,
    open: composerOpen,
    scopeKey: workspaceId,
    workers,
  })
  const orchestrator = useOrchestratorPaneState({
    workspaceId: workspace?.id ?? '',
    terminalRuns,
    autostartError: orchestratorAutostartError,
    suppressAutostartRunId: orchestratorAutostartRunId,
    onClearAutostartError: () => {
      if (workspace) onOrchestratorResult(workspace.id, { ok: true, error: null, run_id: null })
    },
    onAfterStart: (result) => {
      if (workspace) onOrchestratorResult(workspace.id, result)
    },
  })
  const split = usePaneSplit()
  const activeWorker: TeamListItem | null =
    workers.find((worker) => worker.id === activeWorkerId) ?? null
  useEffect(() => {
    if (activeWorkerId && !activeWorker) setActiveWorkerId(null)
  }, [activeWorkerId, activeWorker])
  const panelTabs = useTerminalPanelTabs({
    workspaceId: workspace?.id ?? '',
    workers,
    terminalRuns,
  })
  const shellPanelTabs = panelTabs.tabs.filter((tab) => tab.kind === 'shell')
  const shellRuns = workspace
    ? terminalRuns.filter((run) => isWorkspaceShellRun(run, workspace.id))
    : []
  const { closeShellTab, openShell, shellError, shellStarting, startNewShell } =
    useWorkspaceShellLauncher({
      onCloseFailed: (message) =>
        toast.show({ kind: 'error', message: t('shellTerminal.closeFailed', { message }) }),
      onShellRunClosed,
      onShellRunStarted,
      panelTabs,
      shellRuns,
      workspaceId: workspace?.id ?? null,
    })

  // Surface composer / delete errors as toasts instead of inline alert bands.
  useEffect(() => {
    if (composer.createWorkerError)
      toast.show({ kind: 'error', message: composer.createWorkerError })
  }, [composer.createWorkerError, toast])

  useEffect(() => {
    if (deleteWorkerError) toast.show({ kind: 'error', message: deleteWorkerError })
  }, [deleteWorkerError, toast])

  // Start failures no longer have a modal banner to display them — surface
  // via toast to keep parity with delete-error feedback.
  useEffect(() => {
    if (startWorkerError) toast.show({ kind: 'error', message: startWorkerError })
  }, [startWorkerError, toast])

  // Shell-start failures no longer have a dialog banner — surface via toast.
  useEffect(() => {
    if (shellError) toast.show({ kind: 'error', message: shellError })
  }, [shellError, toast])

  // B2: when the user switches workspace, clear local error state so we don't
  // surface a stale error from the previous workspace as a fresh toast.
  // biome-ignore lint/correctness/useExhaustiveDependencies: effect intentionally fires only on workspace switch
  useEffect(() => {
    setActiveWorkerId(null)
    setDeleteWorkerError(null)
    setStartWorkerError(null)
    setStartingWorkerId(null)
    setTerminalPanelHidden(false)
    setComposerOpen(false)
  }, [workspace?.id])

  if (!workspace) {
    const welcomeProps: {
      onAddWorkspace: () => void
      onTryDemo?: () => void
      disabledReason?: string
    } = { onAddWorkspace: onRequestAddWorkspace }
    if (onTryDemo) welcomeProps.onTryDemo = onTryDemo
    if (welcomeDisabledReason) welcomeProps.disabledReason = welcomeDisabledReason
    return <WelcomePane {...welcomeProps} />
  }

  const activeWorkerRun = activeWorker ? findRunByAgentId(terminalRuns, activeWorker.id) : undefined

  const handleDeleteWorker = (worker: TeamListItem) => {
    const requestWorkspaceId = workspace.id
    const operationKey = `${requestWorkspaceId}\0${worker.id}`
    if (deleteOperationsRef.current.has(operationKey)) return
    deleteOperationsRef.current.add(operationKey)
    setDeleteWorkerError(null)
    void onDeleteWorker(worker.id)
      .then(() => {
        if (workspaceIdRef.current === requestWorkspaceId) setActiveWorkerId(null)
      })
      .catch((error) => {
        if (workspaceIdRef.current === requestWorkspaceId) {
          setDeleteWorkerError(error instanceof Error ? error.message : String(error))
        }
      })
      .finally(() => deleteOperationsRef.current.delete(operationKey))
  }

  const handleStartWorker = (worker: TeamListItem) => {
    const requestWorkspaceId = workspace.id
    const operation = { workspaceId: requestWorkspaceId, workerId: worker.id }
    if (startOperationRef.current?.workspaceId === requestWorkspaceId) return
    startOperationRef.current = operation
    setStartWorkerError(null)
    setStartingWorkerId(worker.id)
    void onStartWorker(worker.id)
      .then(({ error }) => {
        if (error && workspaceIdRef.current === requestWorkspaceId) setStartWorkerError(error)
      })
      .catch((error) => {
        if (workspaceIdRef.current === requestWorkspaceId) {
          setStartWorkerError(error instanceof Error ? error.message : String(error))
        }
      })
      .finally(() => {
        if (startOperationRef.current === operation) {
          startOperationRef.current = null
          if (workspaceIdRef.current === requestWorkspaceId) setStartingWorkerId(null)
        }
      })
  }

  const handleRenameWorker = async (
    worker: TeamListItem,
    newName: string
  ): Promise<{ error: string | null }> => {
    try {
      const requestWorkspaceId = workspace.id
      await renameWorker(workspace.id, worker.id, newName)
      if (workspaceIdRef.current === requestWorkspaceId) {
        toast.show({
          kind: 'success',
          message: t('worker.renameSuccess', { name: newName }),
        })
      }
      return { error: null }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      if (workspaceIdRef.current === workspace.id) {
        toast.show({ kind: 'error', message: t('worker.renameFailed', { message }) })
      }
      return { error: message }
    }
  }

  const orchWidth = `${(split.orchPct * 100).toFixed(2)}%`
  const openShellTerminal = () => {
    setTerminalPanelHidden(false)
    openShell()
  }
  const startNewShellFromPanel = () => {
    setTerminalPanelHidden(false)
    startNewShell()
  }

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col" style={{ background: 'var(--bg-2)' }}>
      <span className="sr-only" data-terminal-runs-stale={terminalRunsStale || undefined}>
        {terminalRunsStale ? t('runtime.staleTitle') : ''}
      </span>
      <WorkspaceNotifications terminalRuns={terminalRuns} workers={workers} workspace={workspace} />
      <div ref={split.containerRef} className="relative flex min-h-0 flex-1">
        <div
          className="flex min-w-0 shrink-0 flex-col"
          style={{ width: orchWidth }}
          data-testid="orchestrator-pane-shell"
        >
          <OrchestratorPane
            state={orchestrator.state}
            onRemoveWorkspace={() => {
              void onDeleteWorkspace(workspace).catch((error: unknown) => {
                const message = error instanceof Error ? error.message : String(error)
                toast.show({ kind: 'error', message: `Delete failed: ${message}` })
              })
            }}
            onStart={orchestrator.start}
            onRestart={orchestrator.restart}
          />
        </div>
        {/* biome-ignore lint/a11y/useSemanticElements: <hr> can't host pointer/keyboard handlers and the visible accent line; aria role="separator" is the canonical resize-handle role */}
        <div
          role="separator"
          aria-orientation="vertical"
          aria-label={t('workerPane.resize')}
          aria-valuenow={Math.round(split.orchPct * 100)}
          aria-valuemin={Math.round(split.minPct * 100)}
          aria-valuemax={Math.round(split.maxPct * 100)}
          tabIndex={0}
          className="pane-splitter"
          style={{ left: `calc(${orchWidth} - 6px)` }}
          data-dragging={split.dragging || undefined}
          data-testid="pane-splitter"
          onPointerDown={split.beginDrag}
          onKeyDown={split.onKeyDown}
        />
        <div className="relative flex min-w-0 flex-1 flex-col">
          <WorkersPane
            key={workspace.id}
            workspaceId={workspace.id}
            onAddWorkerClick={() => setComposerOpen(true)}
            onDeleteWorker={handleDeleteWorker}
            onOpenShellTerminal={openShellTerminal}
            onOpenWorker={(worker) => setActiveWorkerId(worker.id)}
            onRenameWorker={handleRenameWorker}
            onStartWorker={handleStartWorker}
            startingWorkerId={startingWorkerId}
            terminalRuns={terminalRuns}
            workers={workers}
          />
          {terminalPanelHidden ? null : (
            <TerminalBottomPanel
              tabs={shellPanelTabs}
              activeId={panelTabs.activeId}
              onSelect={panelTabs.setActive}
              onClose={(tabId) => {
                if (tabId.startsWith('shell:')) {
                  closeShellTab(tabId.slice('shell:'.length))
                }
                panelTabs.closeTab(tabId)
              }}
              onClosePanel={() => setTerminalPanelHidden(true)}
              onNewShell={startNewShellFromPanel}
              newShellPending={shellStarting}
              onStartWorker={(workerId) => {
                const worker = workers.find((w) => w.id === workerId)
                if (worker) handleStartWorker(worker)
              }}
              startingWorkerId={startingWorkerId}
            />
          )}
        </div>
      </div>
      {activeWorker ? (
        <Suspense fallback={null}>
          <WorkerModal
            onClose={() => setActiveWorkerId(null)}
            onStart={handleStartWorker}
            runId={activeWorkerRun?.run_id ?? null}
            startError={startWorkerError}
            starting={startingWorkerId === activeWorker.id}
            worker={activeWorker}
          />
        </Suspense>
      ) : null}
      {composerOpen ? (
        <Suspense fallback={null}>
          <AddWorkerDialog
            availableModels={composer.availableModels}
            commandPresets={composer.commandPresets}
            commandPresetId={composer.commandPresetId}
            modelId={composer.modelId}
            modelMode={composer.modelMode}
            creating={composer.creating}
            customTemplates={composer.customTemplates}
            onApplyMarketplaceImport={composer.applyMarketplaceImport}
            onClose={() => setComposerOpen(false)}
            onDeleteTemplate={composer.deleteTemplate}
            onNameChange={composer.setWorkerName}
            onPresetChange={composer.setCommandPresetId}
            onModelChange={composer.setModelSelection}
            onRandomName={composer.randomizeWorkerName}
            onRoleDescriptionChange={composer.setRoleDescription}
            onRoleDescriptionReset={composer.resetRoleDescription}
            onRoleChange={composer.setWorkerRole}
            onSaveAsTemplate={composer.saveAsTemplate}
            onSubmit={(event) => composer.submit(event, () => setComposerOpen(false))}
            onStartupCommandChange={composer.setStartupCommand}
            onTemplateChange={composer.selectTemplate}
            roleDescription={composer.roleDescription}
            roleDescriptionDefault={composer.roleDescriptionDefault}
            selectedTemplateId={composer.selectedTemplateId}
            startupCommand={composer.startupCommand}
            templateBusy={composer.templateBusy}
            workerName={composer.workerName}
            workerRole={composer.workerRole}
          />
        </Suspense>
      ) : null}
    </div>
  )
}
