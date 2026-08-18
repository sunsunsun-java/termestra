import { AlertTriangle, CheckCircle2, X, XCircle } from 'lucide-react'
import type { ComponentType, CSSProperties } from 'react'

import { useI18n } from '../i18n.js'
import type { ToastEntry, ToastKind } from './useToast.js'
import { useToast, useToastList } from './useToast.js'

type ToastPresentation = {
  accent: string
  Icon: ComponentType<{ size?: number; 'aria-hidden'?: boolean }>
}

const PRESENTATION: Record<ToastKind, ToastPresentation> = {
  success: { accent: 'var(--status-green)', Icon: CheckCircle2 },
  warning: { accent: 'var(--status-orange)', Icon: AlertTriangle },
  error: { accent: 'var(--status-red)', Icon: XCircle },
}

type ToastApi = ReturnType<typeof useToast>

const ToastCard = ({ toast, api }: { toast: ToastEntry; api: ToastApi }) => {
  const { t } = useI18n()
  const { accent, Icon } = PRESENTATION[toast.kind]
  const duration = api.getDuration(toast.id)

  return (
    <li
      data-testid="toast"
      data-kind={toast.kind}
      onMouseEnter={() => api.pauseDismiss(toast.id)}
      onMouseLeave={() => api.resumeDismiss(toast.id)}
      className="toast elev-2 toast-pop pointer-events-auto relative flex min-w-[260px] max-w-[400px] items-start gap-3 overflow-hidden rounded-lg border px-3 py-2.5"
      style={{
        background: 'var(--bg-elevated)',
        borderColor: `color-mix(in oklab, ${accent} 35%, var(--border))`,
      }}
    >
      <span className="mt-0.5 shrink-0" style={{ color: accent }} aria-hidden>
        <Icon size={14} aria-hidden />
      </span>
      <span className="min-w-0 flex-1 break-words text-sm text-pri">{toast.message}</span>
      <button
        type="button"
        data-testid="toast-close"
        onClick={() => api.dismiss(toast.id)}
        className="-mt-0.5 -mr-1 flex h-6 w-6 shrink-0 cursor-pointer items-center justify-center rounded text-ter transition-colors hover:bg-3 hover:text-pri"
        aria-label={t('toast.dismissAria')}
      >
        <X size={14} aria-hidden />
      </button>
      {duration > 0 ? (
        <span
          className="toast-progress-bar"
          style={{ background: accent, animationDuration: `${duration}ms` } as CSSProperties}
          aria-hidden
        />
      ) : null}
    </li>
  )
}

const ToastRegion = ({
  api,
  live,
  role,
  toasts,
}: {
  api: ToastApi
  live: 'assertive' | 'polite'
  role: 'alert' | 'status'
  toasts: ToastEntry[]
}) => (
  <div role={role} aria-live={live} aria-atomic="false">
    <ol className="flex list-none flex-col gap-2 p-0">
      {toasts.map((toast) => (
        <ToastCard key={toast.id} toast={toast} api={api} />
      ))}
    </ol>
  </div>
)

export const Toaster = () => {
  const toasts = useToastList()
  const api = useToast()
  if (toasts.length === 0) return null

  return (
    <div
      className="pointer-events-none fixed right-4 bottom-8 z-50 flex flex-col gap-2"
      data-testid="toaster"
    >
      <ToastRegion
        api={api}
        live="polite"
        role="status"
        toasts={toasts.filter((toast) => toast.kind !== 'error')}
      />
      <ToastRegion
        api={api}
        live="assertive"
        role="alert"
        toasts={toasts.filter((toast) => toast.kind === 'error')}
      />
    </div>
  )
}
