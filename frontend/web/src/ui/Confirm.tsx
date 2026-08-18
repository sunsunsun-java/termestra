import * as Dialog from '@radix-ui/react-dialog'
import { AlertTriangle, HelpCircle } from 'lucide-react'
import { type ComponentType, useRef } from 'react'

import { useI18n } from '../i18n.js'

type ConfirmKind = 'default' | 'danger'

type ConfirmProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: string
  confirmLabel: string
  confirmKind?: ConfirmKind
  cancelLabel?: string
  onConfirm: () => void
}

type KindPresentation = {
  actionClass: string
  color: string
  Icon: ComponentType<{ size?: number; 'aria-hidden'?: boolean }>
  role: 'alertdialog' | 'dialog'
}

const PRESENTATION: Record<ConfirmKind, KindPresentation> = {
  danger: {
    actionClass: 'icon-btn icon-btn--danger-solid',
    color: 'var(--status-red)',
    Icon: AlertTriangle,
    role: 'alertdialog',
  },
  default: {
    actionClass: 'icon-btn icon-btn--primary',
    color: 'var(--accent)',
    Icon: HelpCircle,
    role: 'dialog',
  },
}

export const Confirm = ({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  confirmKind = 'default',
  cancelLabel,
  onConfirm,
}: ConfirmProps) => {
  const { t } = useI18n()
  const cancelButton = useRef<HTMLButtonElement>(null)
  const confirmButton = useRef<HTMLButtonElement>(null)
  const appearance = PRESENTATION[confirmKind]

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay data-testid="confirm-overlay" className="app-overlay fixed inset-0 z-40" />
        <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center p-4">
          <Dialog.Content
            data-testid="confirm-content"
            role={appearance.role}
            onOpenAutoFocus={(event) => {
              event.preventDefault()
              const target = confirmKind === 'danger' ? cancelButton.current : confirmButton.current
              target?.focus()
            }}
            className="dialog-scale-pop elev-2 pointer-events-auto w-[440px] max-w-[calc(100vw-32px)] rounded-lg border p-5"
            style={{
              background: 'var(--bg-elevated)',
              borderColor: 'var(--border-bright)',
            }}
          >
            <form
              onSubmit={(event) => {
                event.preventDefault()
                onConfirm()
                onOpenChange(false)
              }}
            >
              <div className="flex items-start gap-3">
                <div
                  aria-hidden
                  className="flex h-9 w-9 shrink-0 items-center justify-center rounded"
                  style={{
                    background: `color-mix(in oklab, ${appearance.color} 14%, transparent)`,
                    border: `1px solid color-mix(in oklab, ${appearance.color} 30%, transparent)`,
                    color: appearance.color,
                  }}
                >
                  <appearance.Icon size={18} aria-hidden />
                </div>
                <div className="min-w-0 flex-1">
                  <Dialog.Title data-testid="confirm-title" className="text-lg font-semibold text-pri">
                    {title}
                  </Dialog.Title>
                  <Dialog.Description
                    data-testid="confirm-description"
                    className="mt-1.5 text-sm text-sec"
                  >
                    {description}
                  </Dialog.Description>
                </div>
              </div>
              <div className="mt-5 flex justify-end gap-2">
                <Dialog.Close asChild>
                  <button
                    ref={cancelButton}
                    type="button"
                    data-testid="confirm-cancel"
                    className="icon-btn"
                  >
                    {cancelLabel ?? t('common.cancel')}
                  </button>
                </Dialog.Close>
                <button
                  ref={confirmButton}
                  type="submit"
                  data-testid="confirm-action"
                  className={appearance.actionClass}
                >
                  {confirmLabel}
                </button>
              </div>
            </form>
          </Dialog.Content>
        </div>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
