import { AlertTriangle, RefreshCw, Terminal, UserPlus } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'

import type { TeamListItem } from '../../../src/shared/types.js'
import type { TerminalRunSummary } from '../api.js'
import { useI18n } from '../i18n.js'
import { Confirm } from '../ui/Confirm.js'
import { EmptyState } from '../ui/EmptyState.js'
import { Tooltip } from '../ui/Tooltip.js'
import { RenameWorkerDialog } from './RenameWorkerDialog.js'
import { ScenarioTeamCards } from './ScenarioTeamCards.js'
import { WorkerCard, type WorkerCardActionKind } from './WorkerCard.js'
import { presentWorkerStatus, type WorkerStatusKind } from './presentation.js'
import { useDispatchDeliveryIssues } from './useDispatchDeliveryIssues.js'

type WorkersPaneProps = {
  onAddWorkerClick: () => void
  onDeleteWorker: (worker: TeamListItem) => void
  onOpenShellTerminal: () => void
  onOpenWorker: (worker: TeamListItem) => void
  onRenameWorker: (worker: TeamListItem, newName: string) => Promise<{ error: string | null }>
  onStartWorker: (worker: TeamListItem) => void
  shellTerminalAvailable?: boolean
  startingWorkerId: string | null
  terminalRuns: TerminalRunSummary[]
  workers: TeamListItem[]
  workspaceId: string
  readOnly?: boolean
}

const SECTION_ORDER: WorkerStatusKind[] = ['working', 'idle', 'stopped']
const statusKey = (status: WorkerStatusKind) => {
  if (status === 'working') return 'common.running'
  if (status === 'idle') return 'common.idle'
  return 'common.stopped'
}

const summarizeWorkers = (workers: TeamListItem[]) => {
  const buckets: Record<WorkerStatusKind, TeamListItem[]> = {
    idle: [],
    working: [],
    stopped: [],
  }
  for (const worker of workers) buckets[presentWorkerStatus(worker).kind].push(worker)
  return {
    sections: SECTION_ORDER.filter((kind) => buckets[kind].length > 0).map((kind) => ({
      kind,
      workers: buckets[kind],
    })),
    summary: {
      idle: buckets.idle.length,
      stopped: buckets.stopped.length,
      working: buckets.working.length,
    },
  }
}

export const WorkersPane = ({
  onAddWorkerClick,
  onDeleteWorker,
  onOpenShellTerminal,
  onOpenWorker,
  onRenameWorker,
  onStartWorker,
  shellTerminalAvailable = true,
  startingWorkerId,
  terminalRuns,
  workers,
  workspaceId,
  readOnly = false,
}: WorkersPaneProps) => {
  const { t } = useI18n()
  const delivery = useDispatchDeliveryIssues(workspaceId)
  const { sections, summary } = useMemo(() => summarizeWorkers(workers), [workers])
  const runIdsByAgentId = useMemo(
    () => new Map(terminalRuns.map((run) => [run.agent_id, run.run_id] as const)),
    [terminalRuns]
  )
  const [pendingDelete, setPendingDelete] = useState<TeamListItem | null>(null)
  const [renameTarget, setRenameTarget] = useState<TeamListItem | null>(null)
  const [renameBusy, setRenameBusy] = useState(false)
  const renameInFlightRef = useRef(false)

  const handleAction = (kind: WorkerCardActionKind, worker: TeamListItem) => {
    if (kind === 'start') {
      onStartWorker(worker)
      return
    }
    if (kind === 'rename') {
      setRenameTarget(worker)
      return
    }
    if (kind === 'delete') {
      setPendingDelete(worker)
    }
  }

  const confirmDelete = () => {
    if (!pendingDelete) return
    onDeleteWorker(pendingDelete)
    setPendingDelete(null)
  }

  const submitRename = (worker: TeamListItem, newName: string) => {
    if (renameInFlightRef.current) return
    renameInFlightRef.current = true
    setRenameBusy(true)
    void onRenameWorker(worker, newName).finally(() => {
      renameInFlightRef.current = false
      setRenameBusy(false)
      setRenameTarget(null)
    })
  }

  return (
    <div
      className="workers-pane flex min-w-0 flex-1 flex-col"
      style={{ background: 'var(--bg-2)' }}
    >
      <div
        className="workers-pane-header flex shrink-0 flex-col gap-1 px-4 pt-3 pb-2.5"
        style={{
          boxShadow: 'inset 0 -1px 0 var(--border)',
        }}
      >
        <div className="workers-pane-header__main flex items-center gap-2.5">
          <div className="workers-pane-header__identity flex min-w-0 items-center gap-2.5">
            <span className="shrink-0 whitespace-nowrap text-lg font-semibold text-pri">
              {t('worker.teamMembers')}
            </span>
            <span className="mono inline-flex min-w-7 shrink-0 items-center justify-center rounded bg-3 px-2.5 py-1 text-base leading-none text-sec">
              {workers.length}
            </span>
          </div>
          <div className="workers-pane-header__actions ml-auto flex shrink-0 items-center gap-2.5">
            {shellTerminalAvailable && !readOnly ? (
              <Tooltip label={t('shellTerminal.open')} side="bottom">
                <button
                  type="button"
                  onClick={onOpenShellTerminal}
                  className="workers-pane-header__action icon-btn icon-btn--tertiary whitespace-nowrap"
                  aria-label={t('shellTerminal.openAria')}
                  data-testid="open-workspace-shell"
                >
                  <Terminal className="shrink-0" size={14} aria-hidden />
                  <span className="workers-pane-header__action-label">
                    {t('shellTerminal.open')}
                  </span>
                </button>
              </Tooltip>
            ) : null}
            {!readOnly ? (
              <Tooltip label={t('addWorker.create')} side="bottom">
                <button
                  type="button"
                  onClick={onAddWorkerClick}
                  className="workers-pane-header__action icon-btn icon-btn--primary whitespace-nowrap"
                  aria-label={t('addWorker.create')}
                  data-testid="add-worker-trigger"
                >
                  <UserPlus className="shrink-0" size={14} aria-hidden />
                  <span className="workers-pane-header__action-label">{t('addWorker.create')}</span>
                </button>
              </Tooltip>
            ) : null}
          </div>
        </div>
        {workers.length > 0 ? (
          <div className="flex items-center gap-3 text-xs text-ter">
            <span className="inline-flex items-center gap-1.5">
              <span className="status-dot status-dot--working" aria-hidden />
              <span className="text-sec">{summary.working}</span> {t('common.running')}
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="status-dot status-dot--idle" aria-hidden />
              <span className="text-sec">{summary.idle}</span> {t('common.idle')}
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="status-dot status-dot--stopped" aria-hidden />
              <span className="text-sec">{summary.stopped}</span> {t('common.stopped')}
            </span>
          </div>
        ) : null}
      </div>

      {delivery.issues.length > 0 ? (
        <section
          className="mx-3 mt-3 rounded-lg border px-3 py-2.5 text-sm"
          style={{ borderColor: 'var(--status-orange)', background: 'color-mix(in srgb, var(--status-orange) 9%, transparent)' }}
          data-testid="dispatch-delivery-issues"
        >
          <div className="mb-2 flex items-center gap-2 font-medium text-pri">
            <AlertTriangle size={15} aria-hidden />
            {t('dispatch.issueTitle', { count: delivery.issues.length })}
          </div>
          <ul className="space-y-2">
            {delivery.issues.map((dispatch) => {
              const worker = workers.find((item) => item.id === dispatch.toAgentId)
              return (
                <li key={dispatch.id} className="flex min-w-0 items-center gap-2">
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sec">
                      {worker?.name ?? dispatch.toAgentId} · {dispatch.deliveryState === 'uncertain'
                        ? t('dispatch.uncertain')
                        : t('dispatch.failed')}
                    </div>
                    {dispatch.deliveryError ? (
                      <div className="truncate text-xs text-ter" title={dispatch.deliveryError}>
                        {dispatch.deliveryError}
                      </div>
                    ) : null}
                  </div>
                  <button
                    type="button"
                    className="icon-btn icon-btn--tertiary shrink-0"
                    disabled={delivery.retryingIds.has(dispatch.id)}
                    onClick={() => void delivery.retry(dispatch.id)}
                  >
                    <RefreshCw size={13} aria-hidden /> {t('common.retry')}
                  </button>
                </li>
              )
            })}
          </ul>
          {delivery.error ? <div className="mt-2 text-xs text-danger">{delivery.error}</div> : null}
        </section>
      ) : null}

      <div className="workers-pane-body scroll-y flex-1 px-2 py-2">
        {workers.length === 0 ? (
          <>
            <EmptyState
              icon={<UserPlus size={28} />}
              title={t('worker.emptyTitle')}
              description={t('worker.emptyDesc')}
              action={readOnly ? undefined : (
                <button
                  type="button"
                  onClick={onAddWorkerClick}
                  className="icon-btn icon-btn--primary"
                  data-testid="add-worker-empty"
                >
                  <UserPlus size={14} aria-hidden /> {t('worker.emptyAdd')}
                </button>
              )}
            />
            {!readOnly ? <ScenarioTeamCards workspaceId={workspaceId} /> : null}
          </>
        ) : (
          <div data-testid="worker-grid">
            {sections.map((section) => (
              <section key={section.kind} className="mb-3 last:mb-0">
                <div className="px-2 py-1 text-xs font-medium uppercase tracking-wider text-ter">
                  {t(statusKey(section.kind))}
                  <span className="mono ml-1.5 text-ter">{section.workers.length}</span>
                </div>
                <ul
                  aria-label={`${t(statusKey(section.kind))} team members`}
                  className="worker-card-grid"
                >
                  {section.workers.map((worker) => (
                    <li key={worker.id}>
                      <WorkerCard
                        readOnly={readOnly}
                        hasRun={runIdsByAgentId.has(worker.id)}
                        isPending={startingWorkerId === worker.id}
                        onClick={readOnly ? () => {} : onOpenWorker}
                        worker={worker}
                        {...(!readOnly ? { onAction: handleAction } : {})}
                      />
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
        )}
      </div>

      <Confirm
        open={pendingDelete !== null}
        onOpenChange={(open) => {
          if (!open) setPendingDelete(null)
        }}
        title={pendingDelete ? t('worker.deleteConfirm', { name: pendingDelete.name }) : ''}
        description={
          pendingDelete ? t('worker.deleteDescription', { name: pendingDelete.name }) : ''
        }
        confirmLabel={t('worker.deleteMember')}
        confirmKind="danger"
        onConfirm={confirmDelete}
      />
      <RenameWorkerDialog
        worker={renameTarget}
        busy={renameBusy}
        onClose={() => setRenameTarget(null)}
        onSubmit={submitRename}
      />
    </div>
  )
}
