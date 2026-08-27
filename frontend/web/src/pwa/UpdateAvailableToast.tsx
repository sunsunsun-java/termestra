import { RefreshCw } from 'lucide-react'
import { useEffect, useState } from 'react'

import type { TeamListItem } from '../../../src/shared/types.js'
import { useI18n } from '../i18n.js'
import {
  type ServiceWorkerUpdateApply,
  subscribeServiceWorkerUpdate,
} from './service-worker-registration.js'
import { isServiceWorkerReloadSafe } from './service-worker-update-policy.js'
import { useGlobalTerminalRunSafety } from './useGlobalTerminalRunSafety.js'

interface UpdateAvailableToastProps {
  workersByWorkspaceId: Readonly<Record<string, readonly TeamListItem[]>>
  workspaceIds: readonly string[]
}

/**
 * Bottom-right banner shown when a newer service worker has finished
 * installing while an older one was still controlling the page. The reload
 * trigger is gated on every terminal run being `stopped`, because reloading
 * during a working PTY would orphan the in-flight agent (the page-close guard
 * at `useBeforeUnloadGuard` would also intercept it). The toast stays mounted
 * with the button disabled until agents drain.
 */
export const UpdateAvailableToast = ({
  workersByWorkspaceId,
  workspaceIds,
}: UpdateAvailableToastProps) => {
  const { t } = useI18n()
  const [apply, setApply] = useState<ServiceWorkerUpdateApply | null>(null)
  const [applying, setApplying] = useState(false)
  const terminalSafety = useGlobalTerminalRunSafety(apply !== null, workspaceIds)

  // useState's setter treats raw function values as updater callbacks and
  // would invoke our apply() immediately. Wrap in a second arrow to store the
  // function as a value instead.
  useEffect(
    () =>
      subscribeServiceWorkerUpdate((next) => {
        setApply(() => next)
      }),
    []
  )

  if (!apply) return null

  const canReload = isServiceWorkerReloadSafe(
    terminalSafety.snapshot.runs,
    terminalSafety.snapshot.ready,
    workersByWorkspaceId,
    workspaceIds
  )
  const disabled = !canReload || applying
  const label = applying
    ? t('pwa.reloading')
    : canReload
      ? t('pwa.reloadToActivate')
      : t('pwa.waitForAgents')

  return (
    <div
      className="elev-2 fixed right-4 bottom-8 z-50 flex items-center gap-3 rounded border px-3 py-2"
      style={{ background: 'var(--bg-elevated)', borderColor: 'var(--border-bright)' }}
      data-testid="update-available-toast"
      role="status"
      aria-live="polite"
    >
      <RefreshCw size={14} className="text-sec" aria-hidden />
      <div className="flex flex-col">
        <span className="font-medium text-pri text-xs">{t('pwa.appShellUpdated')}</span>
        <span className="text-ter text-xs">
          {canReload ? t('pwa.reloadToActivate') : t('pwa.waitForAgents')}
        </span>
      </div>
      <button
        type="button"
        className="icon-btn icon-btn--primary"
        data-testid="update-available-reload"
        disabled={disabled}
        onClick={() => {
          setApplying(true)
          void terminalSafety.verifyNow().then((snapshot) => {
            const stillSafe = isServiceWorkerReloadSafe(
              snapshot.runs,
              snapshot.ready,
              workersByWorkspaceId,
              workspaceIds
            )
            if (!stillSafe) {
              setApplying(false)
              return
            }
            apply()
          })
        }}
      >
        {label}
      </button>
    </div>
  )
}
