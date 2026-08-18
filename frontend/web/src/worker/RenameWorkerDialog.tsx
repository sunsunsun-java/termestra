import * as Dialog from '@radix-ui/react-dialog'
import { PencilLine } from 'lucide-react'
import { type FormEvent, useEffect, useId, useRef, useState } from 'react'

import type { TeamListItem } from '../../../src/shared/types.js'
import { useI18n } from '../i18n.js'

type RenameWorkerDialogProps = {
  worker: TeamListItem | null
  busy?: boolean
  onClose: () => void
  onSubmit: (worker: TeamListItem, name: string) => void
}

export const RenameWorkerDialog = ({
  busy = false,
  onClose,
  onSubmit,
  worker,
}: RenameWorkerDialogProps) => {
  const { t } = useI18n()
  const [name, setName] = useState(worker?.name ?? '')
  const inputRef = useRef<HTMLInputElement>(null)
  const nameFieldId = useId()

  useEffect(() => {
    if (worker) setName(worker.name)
  }, [worker])

  if (!worker) return null

  const nextName = name.trim()
  const changed = nextName !== worker.name
  const saveEnabled = nextName.length > 0 && changed && !busy

  const submitRename = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (saveEnabled) onSubmit(worker, nextName)
  }

  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay
          className="app-overlay fixed inset-0 z-40"
          data-testid="rename-worker-overlay"
        />
        <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center p-4">
          <Dialog.Content
            className="dialog-scale-pop elev-2 pointer-events-auto w-[420px] max-w-full overflow-hidden rounded-lg border"
            data-testid="rename-worker-dialog"
            onOpenAutoFocus={(event) => {
              event.preventDefault()
              inputRef.current?.focus()
              inputRef.current?.select()
            }}
            style={{
              background: 'var(--bg-elevated)',
              borderColor: 'var(--border-bright)',
            }}
          >
            <form
              aria-label={t('worker.renameTitle')}
              className="flex flex-col"
              onSubmit={submitRename}
            >
              <header
                className="flex items-start gap-3 border-b px-5 py-4"
                style={{ borderColor: 'var(--border)' }}
              >
                <span
                  aria-hidden
                  className="flex h-9 w-9 shrink-0 items-center justify-center rounded border"
                  style={{
                    background: 'color-mix(in oklab, var(--accent) 12%, transparent)',
                    borderColor: 'color-mix(in oklab, var(--accent) 30%, transparent)',
                    color: 'var(--accent)',
                  }}
                >
                  <PencilLine size={18} />
                </span>
                <span className="min-w-0">
                  <Dialog.Title className="text-lg font-semibold text-pri">
                    {t('worker.renameTitle')}
                  </Dialog.Title>
                  <Dialog.Description className="mt-1 text-sm text-ter">
                    {t('worker.renameDesc')}
                  </Dialog.Description>
                </span>
              </header>

              <div className="flex flex-col gap-2 px-5 py-4">
                <label
                  className="text-xs font-medium uppercase tracking-wider text-ter"
                  htmlFor={nameFieldId}
                >
                  {t('addWorker.name')}
                </label>
                <input
                  className="input"
                  data-testid="rename-worker-input"
                  id={nameFieldId}
                  maxLength={64}
                  onChange={(event) => setName(event.currentTarget.value)}
                  ref={inputRef}
                  type="text"
                  value={name}
                />
              </div>

              <footer
                className="flex justify-end gap-2 border-t px-5 py-3"
                style={{ background: 'var(--bg-2)', borderColor: 'var(--border)' }}
              >
                <Dialog.Close asChild>
                  <button className="icon-btn" data-testid="rename-worker-cancel" type="button">
                    {t('common.cancel')}
                  </button>
                </Dialog.Close>
                <button
                  className="icon-btn icon-btn--primary"
                  data-testid="rename-worker-save"
                  disabled={!saveEnabled}
                  type="submit"
                >
                  {busy ? t('common.saving') : t('common.save')}
                </button>
              </footer>
            </form>
          </Dialog.Content>
        </div>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
