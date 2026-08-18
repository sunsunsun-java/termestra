import * as Dialog from '@radix-ui/react-dialog'
import { Dices, Store } from 'lucide-react'
import { type FormEvent, useMemo, useState } from 'react'

import type { WorkerRole } from '../../../src/shared/types.js'
import type { CommandPreset, RoleTemplate } from '../api.js'
import { useI18n } from '../i18n.js'
import { MarketplaceDrawer } from '../marketplace/MarketplaceDrawer.js'
import { Tooltip } from '../ui/Tooltip.js'
import { useToast } from '../ui/useToast.js'
import {
  AgentCliPicker,
  RoleInstructionsField,
  RolePicker,
  RoleTemplatePicker,
  SectionLabel,
  StartupCommandField,
} from './AddWorkerDialogFields.js'

type AddWorkerDialogProps = {
  commandPresets: CommandPreset[]
  commandPresetId: string
  creating?: boolean
  customTemplates: RoleTemplate[]
  onApplyMarketplaceImport: (input: { name: string; description: string }) => void
  onClose: () => void
  onDeleteTemplate: (templateId: string) => Promise<void> | void
  onNameChange: (value: string) => void
  onPresetChange: (value: string) => void
  onRandomName: () => void
  onRoleDescriptionChange: (value: string) => void
  onRoleDescriptionReset: () => void
  onRoleChange: (value: WorkerRole) => void
  onSaveAsTemplate: (name: string) => Promise<void> | void
  onStartupCommandChange: (value: string) => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
  onTemplateChange: (templateId: string | null) => void
  roleDescription: string
  roleDescriptionDefault: string
  selectedTemplateId: string | null
  startupCommand: string
  templateBusy: boolean
  workerName: string
  workerRole: WorkerRole
  writeDisabledReason?: string
}

export const AddWorkerDialog = (props: AddWorkerDialogProps) => {
  const { t } = useI18n()
  const toast = useToast()
  const [marketplaceOpen, setMarketplaceOpen] = useState(false)
  const importedTemplateNames = useMemo(
    () => new Set(props.customTemplates.map(({ name }) => name)),
    [props.customTemplates]
  )

  const chosenPreset = props.commandPresets.find(({ id }) => id === props.commandPresetId)
  const startupOverride = props.startupCommand.trim()
  const instructionsChanged = props.roleDescription !== props.roleDescriptionDefault
  const canSaveCustomTemplate =
    props.workerRole === 'custom' &&
    props.selectedTemplateId === null &&
    props.roleDescription.trim().length > 0

  const blockingMessage = () => {
    if (props.writeDisabledReason) return props.writeDisabledReason
    if (props.workerName.trim().length === 0) return t('addWorker.enterName')
    if (!props.commandPresetId && !startupOverride) return t('addWorker.pickCliOrStartup')
    if (chosenPreset?.available === false && !startupOverride) {
      return t('addWorker.unavailable', { name: chosenPreset.displayName })
    }
    if (!props.roleDescription.trim()) return t('addWorker.emptyInstructions')
    return null
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    const message = blockingMessage()
    if (!message) {
      props.onSubmit(event)
      return
    }
    event.preventDefault()
    toast.show({ kind: 'warning', message })
  }

  const applyMarketplaceTemplate = (template: { name: string; description: string }) => {
    props.onApplyMarketplaceImport(template)
    toast.show({ kind: 'success', message: t('marketplace.imported', { name: template.name }) })
  }

  return (
    <Dialog.Root open onOpenChange={(open) => !open && props.onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="app-overlay fixed inset-0 z-40" data-testid="add-worker-overlay" />
        <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center p-4">
          <Dialog.Content
            className="dialog-scale-pop elev-2 pointer-events-auto flex max-h-[calc(100vh-32px)] w-[560px] max-w-full flex-col overflow-hidden rounded-lg border"
            data-testid="add-worker-content"
            style={{ background: 'var(--bg-elevated)', borderColor: 'var(--border-bright)' }}
          >
            <form aria-label={t('addWorker.title')} className="flex min-h-0 flex-col" onSubmit={submit}>
              <header className="shrink-0 border-b px-5 py-4" style={{ borderColor: 'var(--border)' }}>
                <Dialog.Title className="text-lg font-semibold text-pri">
                  {t('addWorker.title')}
                </Dialog.Title>
                <Dialog.Description className="mt-0.5 text-sm text-ter">
                  {t('addWorker.description', { command: 'team send' })}
                </Dialog.Description>
              </header>

              <div className="flex min-h-0 flex-col gap-5 overflow-y-auto px-5 py-4">
                <section className="flex flex-col gap-2" aria-labelledby="new-worker-name-label">
                  <div className="flex items-center justify-between gap-2">
                    <span id="new-worker-name-label">
                      <SectionLabel>{t('addWorker.name')}</SectionLabel>
                    </span>
                    <Tooltip label={t('addWorker.randomTooltip')}>
                      <button
                        aria-label={t('addWorker.randomAria')}
                        className="flex cursor-pointer items-center gap-1 rounded px-1.5 py-0.5 text-xs text-ter transition-colors hover:bg-3 hover:text-sec"
                        data-testid="random-worker-name"
                        onClick={props.onRandomName}
                        type="button"
                      >
                        <Dices aria-hidden size={12} />
                        {t('addWorker.random')}
                      </button>
                    </Tooltip>
                  </div>
                  <input
                    aria-labelledby="new-worker-name-label"
                    autoFocus
                    className="input"
                    onChange={(event) => props.onNameChange(event.currentTarget.value)}
                    placeholder={t('addWorker.namePlaceholder')}
                    value={props.workerName}
                  />
                </section>

                <RolePicker onRoleChange={props.onRoleChange} workerRole={props.workerRole} />

                <section className="flex flex-col items-start gap-3">
                  <button
                    className="marketplace-browse-btn flex cursor-pointer items-center gap-2 rounded-md border px-3 py-1.5 text-xs text-sec outline-none transition-colors focus-visible:ring-2"
                    data-testid="open-marketplace"
                    onClick={() => setMarketplaceOpen(true)}
                    style={{
                      background: 'var(--bg-0)',
                      borderColor: 'var(--border-bright)',
                      ['--tw-ring-color' as string]:
                        'color-mix(in oklab, var(--accent) 45%, transparent)',
                    }}
                    type="button"
                  >
                    <Store aria-hidden size={14} />
                    {t('marketplace.openFromAddWorker')}
                  </button>

                  {props.workerRole === 'custom' ? (
                    <div className="w-full">
                      <RoleTemplatePicker
                        customTemplates={props.customTemplates}
                        disabledReason={props.writeDisabledReason}
                        onDeleteTemplate={props.onDeleteTemplate}
                        onSelect={props.onTemplateChange}
                        selectedTemplateId={props.selectedTemplateId}
                      />
                    </div>
                  ) : null}
                </section>

                <RoleInstructionsField
                  canSaveAsTemplate={canSaveCustomTemplate}
                  modified={instructionsChanged}
                  onChange={props.onRoleDescriptionChange}
                  onReset={props.onRoleDescriptionReset}
                  onSaveAsTemplate={props.onSaveAsTemplate}
                  roleDescription={props.roleDescription}
                  templateBusy={props.templateBusy}
                  workerRole={props.workerRole}
                  writeDisabledReason={props.writeDisabledReason}
                />
                <AgentCliPicker
                  commandPresetId={props.commandPresetId}
                  commandPresets={props.commandPresets}
                  onPresetChange={props.onPresetChange}
                />
                <StartupCommandField
                  onChange={props.onStartupCommandChange}
                  value={props.startupCommand}
                />
              </div>

              <footer
                className="flex shrink-0 justify-end gap-2 border-t px-5 py-3"
                style={{ background: 'var(--bg-2)', borderColor: 'var(--border)' }}
              >
                <Dialog.Close asChild>
                  <button className="icon-btn" data-testid="add-worker-cancel" type="button">
                    {t('addWorker.cancel')}
                  </button>
                </Dialog.Close>
                <button
                  className="icon-btn icon-btn--primary"
                  data-testid="add-worker-submit"
                  disabled={props.creating || Boolean(props.writeDisabledReason)}
                  title={props.writeDisabledReason}
                  type="submit"
                >
                  {props.creating ? t('addWorker.creating') : t('addWorker.create')}
                </button>
              </footer>
            </form>
          </Dialog.Content>
        </div>
      </Dialog.Portal>

      <MarketplaceDrawer
        importedNames={importedTemplateNames}
        onClose={() => setMarketplaceOpen(false)}
        onImport={applyMarketplaceTemplate}
        open={marketplaceOpen}
      />
    </Dialog.Root>
  )
}
