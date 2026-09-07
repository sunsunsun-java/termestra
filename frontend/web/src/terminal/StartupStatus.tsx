import type { StartupPhase } from '../api.js'
import { useI18n } from '../i18n.js'

/** This message belongs to the displayed run, not a workspace-wide error. */
export const StartupStatus = ({ phase, message, onRetry, onStop, pending = false }: {
  phase: StartupPhase
  message?: string | null | undefined
  onRetry?: (() => void) | undefined
  onStop?: (() => void) | undefined
  pending?: boolean
}) => {
  const { t } = useI18n()
  return (
    <div
      role={phase === 'failed' ? 'alert' : 'status'}
      data-startup-phase={phase}
      className="flex shrink-0 items-center gap-3 border-b px-4 py-2 text-xs"
      style={{
        borderColor: 'var(--border)',
        color: phase === 'failed' ? 'var(--status-red)' : 'var(--text-secondary)',
      }}
    >
      <div className="min-w-0 flex-1 break-words">
        <span>{t(`startup.${phase}`)}</span>
        {message ? <p className="mt-1 whitespace-pre-wrap">{message}</p> : null}
      </div>
      {phase === 'failed' && onRetry ? (
        <button type="button" className="icon-btn icon-btn--primary" disabled={pending} onClick={onRetry}>
          {t('common.retry')}
        </button>
      ) : null}
      {(phase === 'initializing' || phase === 'waiting_for_user') && onStop ? (
        <button type="button" className="icon-btn icon-btn--tertiary" disabled={pending} onClick={onStop}>
          {t('common.stop')}
        </button>
      ) : null}
    </div>
  )
}
