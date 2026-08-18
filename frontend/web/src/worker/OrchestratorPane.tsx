import { Copy, Crown, LoaderCircle, Play, RotateCcw } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { useI18n } from '../i18n.js'
import { EmptyState } from '../ui/EmptyState.js'
import { Tooltip } from '../ui/Tooltip.js'

export type OrchestratorPaneState =
  | { kind: 'starting' }
  | { kind: 'running'; runId: string }
  | { kind: 'stopped' }
  | { kind: 'failed'; error: string }

type OrchestratorPaneProps = {
  state: OrchestratorPaneState
  /** Kept for API stability; M6-B will surface stop via the ⌘K palette. */
  onStop: () => void
  onRemoveWorkspace: () => void
  onStart: () => void
  onRestart: () => void
}

const WAITING_FOR_INPUT_COPY_DELAY_MS = 4_000

const StartingBody = () => {
  const { t } = useI18n()
  const titleId = useId()
  const descriptionId = useId()
  const hintId = useId()
  const [waitingForInput, setWaitingForInput] = useState(false)

  useEffect(() => {
    const timer = window.setTimeout(() => setWaitingForInput(true), WAITING_FOR_INPUT_COPY_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [])

  return (
    <div data-testid="orchestrator-starting-body" className="flex flex-1">
      <section
        role="status"
        aria-live="polite"
        aria-atomic="true"
        aria-labelledby={titleId}
        aria-describedby={`${descriptionId} ${hintId}`}
        className="m-auto flex w-full max-w-[420px] flex-col items-center gap-3 px-6 py-8 text-center"
      >
        <span
          aria-hidden
          className="flex h-12 w-12 items-center justify-center rounded-lg border text-sec"
          style={{ background: 'var(--bg-2)', borderColor: 'var(--border-bright)' }}
        >
          <LoaderCircle size={24} className="animate-spin" />
        </span>
        <h2 id={titleId} className="text-lg font-semibold text-pri">
          {t('orchestrator.startingTitle')}
        </h2>
        <p id={descriptionId} className="text-sm text-ter">
          {t('orchestrator.startingDesc')}
        </p>
        <div
          className="mt-1 w-full rounded-lg border px-4 py-3 text-left"
          style={{ background: 'var(--bg-2)', borderColor: 'var(--border)' }}
        >
          <div className="flex items-start gap-2.5 text-sm text-sec">
            <LoaderCircle size={14} className="mt-0.5 shrink-0 animate-spin" aria-hidden />
            <span data-testid="orchestrator-starting-phase">
              {t(
                waitingForInput
                  ? 'orchestrator.startingPhaseWaiting'
                  : 'orchestrator.startingPhaseInitial'
              )}
            </span>
          </div>
          <p id={hintId} className="mt-2 pl-6 text-xs leading-5 text-ter">
            {t('orchestrator.startingHint')}
          </p>
        </div>
      </section>
    </div>
  )
}

const StoppedBody = ({ onStart }: { onStart: () => void }) => {
  const { t } = useI18n()
  return (
    <div data-testid="orchestrator-stopped-body" className="flex flex-1">
      <EmptyState
        icon={<Crown size={24} />}
        title={t('orchestrator.stoppedTitle')}
        description={t('orchestrator.stoppedDesc')}
        action={
          <button
            type="button"
            onClick={onStart}
            className="icon-btn icon-btn--primary"
            data-testid="orchestrator-start"
          >
            <Play size={12} aria-hidden /> {t('orchestrator.start')}
          </button>
        }
      />
    </div>
  )
}

const FailedBody = ({
  error,
  onRemoveWorkspace,
  onRestart,
}: {
  error: string
  onRemoveWorkspace: () => void
  onRestart: () => void
}) => {
  const { t } = useI18n()
  const titleId = useId()
  const [copied, setCopied] = useState(false)
  const copyResetTimerRef = useRef<number | undefined>(undefined)
  const mountedRef = useRef(false)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      if (copyResetTimerRef.current !== undefined) {
        window.clearTimeout(copyResetTimerRef.current)
        copyResetTimerRef.current = undefined
      }
    }
  }, [])
  const copyError = () => {
    void navigator.clipboard
      ?.writeText(error)
      .then(() => {
        if (!mountedRef.current) return
        setCopied(true)
        if (copyResetTimerRef.current !== undefined) {
          window.clearTimeout(copyResetTimerRef.current)
        }
        copyResetTimerRef.current = window.setTimeout(() => {
          copyResetTimerRef.current = undefined
          setCopied(false)
        }, 1500)
      })
      .catch((clipboardError: unknown) => {
        console.error('[termestra] clipboard write failed', clipboardError)
      })
  }
  return (
    <section
      role="alert"
      aria-labelledby={titleId}
      data-testid="orchestrator-failed-body"
      className="m-auto flex w-full max-w-[560px] flex-col items-center gap-3 px-6 py-8"
    >
      <div
        aria-hidden
        className="flex h-12 w-12 items-center justify-center rounded text-sec"
        style={{ background: 'var(--bg-2)', border: '1px solid var(--border-bright)' }}
      >
        <Crown size={24} />
      </div>
      <h2 id={titleId} className="text-lg font-semibold text-pri">
        {t('orchestrator.failed')}
      </h2>
      <div
        role="region"
        aria-label={t('orchestrator.errorDetails')}
        className="w-full overflow-hidden rounded-lg border"
        style={{
          background: 'color-mix(in oklab, var(--status-red) 8%, var(--bg-2))',
          borderColor: 'color-mix(in oklab, var(--status-red) 24%, transparent)',
        }}
      >
        <div
          className="flex min-h-9 items-center justify-between gap-3 border-b px-3"
          style={{ borderColor: 'color-mix(in oklab, var(--status-red) 18%, transparent)' }}
        >
          <span className="text-xs font-medium text-ter">{t('orchestrator.errorDetails')}</span>
          <Tooltip label={copied ? t('common.copied') : t('common.copyError')}>
            <button
              type="button"
              onClick={copyError}
              aria-label={t('orchestrator.copyErrorAria')}
              className="icon-btn icon-btn--ghost h-6 shrink-0 px-1.5"
              data-testid="orchestrator-copy-error"
            >
              <Copy size={12} aria-hidden />
            </button>
          </Tooltip>
        </div>
        <pre
          data-testid="orchestrator-error-message"
          className="mono m-0 w-full max-h-48 overflow-auto whitespace-pre-wrap break-words p-3 text-left text-xs leading-5 [overflow-wrap:anywhere]"
          style={{
            color: 'var(--text-secondary)',
          }}
        >
          {error}
        </pre>
      </div>
      <div className="flex flex-wrap items-center justify-center gap-2">
        <button
          type="button"
          onClick={onRestart}
          className="icon-btn icon-btn--primary"
          data-testid="orchestrator-retry"
        >
          <RotateCcw size={12} aria-hidden /> {t('common.retry')}
        </button>
        <button
          type="button"
          onClick={onRemoveWorkspace}
          className="icon-btn icon-btn--danger"
          data-testid="orchestrator-remove-workspace"
        >
          {t('orchestrator.removeWorkspace')}
        </button>
      </div>
      {/* Header retry was a duplicate; alias kept for back-compat. */}
      <span data-testid="orchestrator-retry-header" className="sr-only">
        {t('common.retry')}
      </span>
    </section>
  )
}

export const OrchestratorPane = ({
  state,
  onRemoveWorkspace,
  onRestart,
  onStart,
}: OrchestratorPaneProps) => (
  <div
    className="relative flex h-full w-full min-w-0 flex-col"
    style={{
      background: 'var(--bg-crust)',
      borderRight: '1px solid var(--border)',
    }}
    data-testid="orchestrator-terminal-slot"
  >
    {state.kind === 'running' ? (
      <div
        id={`orch-pty-${state.runId}`}
        className="flex h-full w-full"
        data-pty-slot="orchestrator"
      />
    ) : state.kind === 'failed' ? (
      <FailedBody error={state.error} onRemoveWorkspace={onRemoveWorkspace} onRestart={onRestart} />
    ) : state.kind === 'stopped' ? (
      <StoppedBody onStart={onStart} />
    ) : (
      <StartingBody />
    )}
  </div>
)
