import { Loader2, PlayCircle, RefreshCw, ServerCrash } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'

import { useI18n } from '../i18n.js'
import {
  createVisibleSingleFlightProbe,
  type VisibleSingleFlightProbe,
} from '../lib/visible-single-flight-probe.js'
import { silentReload } from '../useBeforeUnloadGuard.js'

const RECONNECT_INTERVAL_MS = 3000
const RECONNECT_TIMEOUT_MS = 8000

interface RuntimeOfflinePageProps {
  /**
   * Engage demo mode without leaving this view. Daemon-offline is exactly when
   * users most want to evaluate Termestra without installing a CLI, so we surface a
   * Try Demo escape hatch alongside Retry.
   */
  onTryDemo?: () => void
}

/**
 * Full-screen replacement for the workspace content area when the Termestra runtime
 * is unreachable at bootstrap. Pings `/api/version` on a 3s timer and reloads
 * the page when the daemon comes back, so the user doesn't have to manually
 * refresh after starting `termestra` in their terminal.
 */
export const RuntimeOfflinePage = ({ onTryDemo }: RuntimeOfflinePageProps = {}) => {
  const { t } = useI18n()
  const [retrying, setRetrying] = useState(false)
  const probeLoopRef = useRef<VisibleSingleFlightProbe | null>(null)
  const retryFeedbackTimerRef = useRef<number | undefined>(undefined)
  const mountedRef = useRef(false)

  // silentReload arms the beforeunload guard so this auto-recovery reload
  // doesn't surface the "Reload site?" close-confirmation dialog. Without it
  // both the Retry click and the 3s polling reload would be intercepted by
  // the always-on guard mounted in AppInner.
  const reload = useCallback(() => {
    if (typeof window === 'undefined') return
    silentReload()
  }, [])

  const probe = useCallback(async (signal: AbortSignal): Promise<boolean> => {
    try {
      const response = await fetch('/api/version', {
        cache: 'no-store',
        credentials: 'include',
        signal,
      })
      return response.ok
    } catch {
      return false
    }
  }, [])

  useEffect(() => {
    mountedRef.current = true
    const loop = createVisibleSingleFlightProbe({
      intervalMs: RECONNECT_INTERVAL_MS,
      onOnline: reload,
      probe,
      timeoutMs: RECONNECT_TIMEOUT_MS,
    })
    probeLoopRef.current = loop
    return () => {
      mountedRef.current = false
      if (probeLoopRef.current === loop) probeLoopRef.current = null
      loop.dispose()
      if (retryFeedbackTimerRef.current !== undefined) {
        window.clearTimeout(retryFeedbackTimerRef.current)
        retryFeedbackTimerRef.current = undefined
      }
    }
  }, [probe, reload])

  const handleRetry = async () => {
    setRetrying(true)
    try {
      await probeLoopRef.current?.checkNow()
    } finally {
      if (!mountedRef.current) return
      // Brief delay so users see the "retrying…" feedback even on a fast no-op.
      if (retryFeedbackTimerRef.current !== undefined) {
        window.clearTimeout(retryFeedbackTimerRef.current)
      }
      retryFeedbackTimerRef.current = window.setTimeout(() => {
        retryFeedbackTimerRef.current = undefined
        setRetrying(false)
      }, 400)
    }
  }

  return (
    <div className="flex flex-1 items-center justify-center p-8" data-testid="runtime-offline-page">
      <div
        className="elev-1 flex max-w-md flex-col items-center gap-3 rounded border p-6 text-center"
        style={{ background: 'var(--bg-elevated)', borderColor: 'var(--border)' }}
      >
        <div
          className="flex h-12 w-12 items-center justify-center rounded-full"
          style={{ background: 'var(--bg-3)', color: 'var(--status-orange)' }}
        >
          <ServerCrash size={24} aria-hidden />
        </div>
        <div className="font-semibold text-pri">{t('pwa.runtimeOffline.title')}</div>
        <div className="text-sec text-sm leading-relaxed">{t('pwa.runtimeOffline.body')}</div>
        <div className="mt-2 flex flex-wrap items-center justify-center gap-2">
          <button
            type="button"
            className="icon-btn icon-btn--primary flex items-center gap-2"
            data-testid="runtime-offline-retry"
            disabled={retrying}
            onClick={() => {
              void handleRetry()
            }}
          >
            {retrying ? (
              <Loader2 size={12} className="animate-spin" aria-hidden />
            ) : (
              <RefreshCw size={12} aria-hidden />
            )}
            {retrying ? t('pwa.runtimeOffline.retrying') : t('pwa.runtimeOffline.retry')}
          </button>
          {onTryDemo ? (
            <button
              type="button"
              className="icon-btn flex items-center gap-2"
              data-testid="runtime-offline-try-demo"
              onClick={onTryDemo}
            >
              <PlayCircle size={12} aria-hidden />
              {t('pwa.runtimeOffline.tryDemo')}
            </button>
          ) : null}
        </div>
        <div className="text-ter text-xs">{t('pwa.runtimeOffline.autoReconnect')}</div>
      </div>
    </div>
  )
}
