import * as Dialog from '@radix-ui/react-dialog'
import { Play, X } from 'lucide-react'

import type { TeamListItem } from '../../../src/shared/types.js'
import type { TerminalRunSummary } from '../api.js'
import { isRunActive, runStartupPhase } from '../terminal/run-startup.js'
import { StartupStatus } from '../terminal/StartupStatus.js'
import { useI18n } from '../i18n.js'
import { Tooltip } from '../ui/Tooltip.js'
import { CliAgentAvatar } from './CliAgentAvatar.js'
import { useWorkerModalResize, WORKER_MODAL_MIN } from './useWorkerModalResize.js'
import {
  presentRuntimeStatus,
  roleTranslationKey,
  statusTranslationKey,
} from './presentation.js'

type WorkerModalProps = {
  onClose: () => void
  onStart: (worker: TeamListItem) => void
  run?: TerminalRunSummary | undefined
  onStop?: (() => void) | undefined
  created?: boolean
  starting: boolean
  worker: TeamListItem
}

/** Worker terminal with startup progress, setup access, retained failure output and retry. */
export const WorkerModal = ({
  onClose,
  onStart,
  run,
  onStop,
  created = false,
  starting,
  worker,
}: WorkerModalProps) => {
  const { t } = useI18n()
  const runId = run?.run_id
  const phase = starting ? 'initializing' : runStartupPhase(run)
  const ptyRunning = isRunActive(run)
  const status = presentRuntimeStatus(ptyRunning)
  const resize = useWorkerModalResize()

  const handleOpenChange = (open: boolean) => {
    if (!open) onClose()
  }

  return (
    <Dialog.Root open onOpenChange={handleOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay
          data-testid="worker-modal-overlay"
          className="app-overlay fixed inset-0 z-40"
        />
        <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center">
          <Dialog.Content
            data-testid="worker-modal"
            aria-label={t('worker.detail', { name: worker.name })}
            className="dialog-scale-pop pointer-events-auto relative flex h-screen max-h-screen max-w-full flex-col overflow-hidden"
            onEscapeKeyDown={(event) => event.preventDefault()}
            style={{
              background: 'var(--bg-1)',
              width: `${resize.width}px`,
            }}
          >
            {/* biome-ignore lint/a11y/useSemanticElements: aria role="separator" is the canonical resize-handle role */}
            <div
              role="separator"
              aria-orientation="vertical"
              aria-label={t('worker.widthResize')}
              aria-valuemin={WORKER_MODAL_MIN}
              aria-valuenow={Math.round(resize.width)}
              className="modal-resize-handle modal-resize-handle--left"
              tabIndex={-1}
              data-resizing={resize.resizing || undefined}
              onPointerDown={resize.beginResize('left')}
            />
            {/* biome-ignore lint/a11y/useSemanticElements: aria role="separator" is the canonical resize-handle role */}
            <div
              role="separator"
              aria-orientation="vertical"
              aria-label={t('worker.widthResize')}
              aria-valuemin={WORKER_MODAL_MIN}
              aria-valuenow={Math.round(resize.width)}
              className="modal-resize-handle modal-resize-handle--right"
              tabIndex={-1}
              data-resizing={resize.resizing || undefined}
              onPointerDown={resize.beginResize('right')}
            />
            <Dialog.Title className="sr-only">{worker.name}</Dialog.Title>
            <Dialog.Description className="sr-only">
              {t(roleTranslationKey(worker.role))} agent — status {t(statusTranslationKey(status.kind))}
            </Dialog.Description>

            {created ? <p className="shrink-0 px-4 py-2 text-xs text-sec">{t('startup.created')}</p> : null}
            {phase ? <StartupStatus phase={phase} message={starting ? null : run?.startup_message}
              onRetry={() => onStart(worker)} onStop={onStop} pending={starting} /> : null}

            <div
              className="relative flex min-h-0 flex-1 flex-col p-3"
              data-testid="worker-modal-terminal-slot"
            >
              <Tooltip label={t('common.close')}>
                <Dialog.Close asChild>
                  <button
                    type="button"
                    aria-label="Close worker detail"
                    className="float-action absolute top-4 right-4 z-10"
                  >
                    <X size={14} aria-hidden />
                  </button>
                </Dialog.Close>
              </Tooltip>

              <div
                className="flex min-h-0 flex-1 rounded-lg border"
                style={{ background: 'var(--bg-crust)', borderColor: 'var(--border)' }}
              >
                {runId ? (
                  <div
                    id={`worker-pty-${runId}`}
                    className="flex h-full w-full"
                    data-pty-slot="worker"
                  />
                ) : (
                  <div className="m-auto flex max-w-[400px] flex-col items-center gap-3 px-6 text-center">
                    <CliAgentAvatar
                      commandPresetId={worker.commandPresetId}
                      workerRole={worker.role}
                      size={48}
                    />
                    <div className="text-sm text-pri">{worker.name}</div>
                    <div className="text-xs text-ter">
                      {worker.status === 'stopped'
                        ? t('worker.terminalStopped')
                        : t('worker.terminalNotStarted')}
                      {worker.pendingTaskCount > 0
                        ? t('worker.pendingResume', { count: worker.pendingTaskCount })
                        : t('worker.startAgent')}
                    </div>
                    <button
                      type="button"
                      onClick={() => onStart(worker)}
                      disabled={starting}
                      className="icon-btn icon-btn--primary"
                      data-testid="worker-start-empty"
                    >
                      <Play size={12} aria-hidden />{' '}
                      {starting ? t('common.starting') : t('common.start')}
                    </button>
                  </div>
                )}
              </div>
            </div>
          </Dialog.Content>
        </div>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
