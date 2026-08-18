import * as Dialog from '@radix-ui/react-dialog'
import {
  ArrowLeft,
  ArrowUp,
  ChevronDown,
  ChevronRight,
  Folder,
  LoaderCircle,
  X,
} from 'lucide-react'
import { useEffect, useState } from 'react'

import type { CommandPreset } from '../api.js'
import { useI18n } from '../i18n.js'
import { FsEntryList } from './FsEntryList.js'
import { FsSelectionPreview } from './FsSelectionPreview.js'
import { buildBreadcrumbs } from './path-breadcrumbs.js'
import { useFsBrowser } from './useFsBrowser.js'
import { WorkspaceCommandPresetSelect } from './WorkspaceCommandPresetSelect.js'
import {
  buildWorkspaceCreateInput,
  type WorkspaceCreateInput,
} from './workspace-create-input.js'

type ServerBrowseDialogProps = {
  commandPresetError: string | null
  commandPresetId: string
  commandPresets: CommandPreset[]
  onClose: () => void
  onBack: () => void
  onCommandPresetChange: (value: string) => void
  onCreate: (input: WorkspaceCreateInput) => void
  open: boolean
  submitError?: string | null
  submitting?: boolean
}

/**
 * Server-side filesystem browser dialog — the kanban-style "remote" picker.
 * Served via the `▸ Advanced: browse server filesystem` affordance on the
 * compact confirm dialog. The **default** workspace-add flow is the native
 * OS folder picker (`pickFolder()`); this surface exists for SSH / headless
 * runtime scenarios where no OS dialog is available.
 */
export const ServerBrowseDialog = ({
  commandPresetError,
  commandPresetId,
  commandPresets,
  onClose,
  onBack,
  onCommandPresetChange,
  onCreate,
  open,
  submitError = null,
  submitting = false,
}: ServerBrowseDialogProps) => {
  const { t } = useI18n()
  const { browse, loading, navigate, probe, selectEntry, selected } = useFsBrowser(open)
  const [name, setName] = useState('')
  const [advanced, setAdvanced] = useState(false)
  const [manualPath, setManualPath] = useState('')
  const [startupExpanded, setStartupExpanded] = useState(false)
  const [startupCommand, setStartupCommand] = useState('')

  useEffect(() => {
    if (!open) {
      setName('')
      setAdvanced(false)
      setManualPath('')
      setStartupExpanded(false)
      setStartupCommand('')
    }
  }, [open])

  useEffect(() => {
    if (probe?.suggested_name) setName(probe.suggested_name)
  }, [probe?.suggested_name])

  if (!open) return null

  const breadcrumbs = buildBreadcrumbs(browse.current_path, browse.root_path)
  const selectedPreset = commandPresets.find((preset) => preset.id === commandPresetId)
  const startupClean = startupCommand.trim()
  const presetsLoading = commandPresets.length === 0 && !commandPresetError
  const genericPresetNeedsStartup = !commandPresetId && startupClean.length === 0
  const selectedPresetUnavailable = selectedPreset?.available === false && startupClean.length === 0
  const presetAvailabilityError = genericPresetNeedsStartup
    ? t('workspace.preset.genericRequiresStartup')
    : selectedPresetUnavailable
      ? t('workspace.preset.notInstalled', { name: selectedPreset.displayName })
      : null
  const canCreate =
    name.trim().length > 0 &&
    (probe?.is_dir === true || (advanced && manualPath.trim().length > 0)) &&
    !presetsLoading &&
    !genericPresetNeedsStartup &&
    !selectedPresetUnavailable

  const handleCreate = () => {
    if (!canCreate || submitting) return
    const path = advanced && manualPath.trim().length > 0 ? manualPath.trim() : (probe?.path ?? '')
    if (!path) return
    onCreate(buildWorkspaceCreateInput({
      commandPresetId,
      name,
      path,
      startupCommand,
    }))
  }

  return (
    <Dialog.Root open onOpenChange={(next) => !next && !submitting && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay
          data-testid="server-browse-overlay"
          className="app-overlay fixed inset-0 z-40"
        />
        {/* Grid place-items-center is more robust than transform-based */}
        {/* centering when the document has containment contexts (e.g. the */}
        {/* sidebar's container-type) that can shift the fixed positioning */}
        {/* containing-block. Mirrors ConfirmWorkspaceDialog. */}
        <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center p-4">
          <Dialog.Content asChild>
            <form
              data-testid="add-workspace-dialog"
              aria-busy={submitting}
              onSubmit={(event) => {
                event.preventDefault()
                handleCreate()
              }}
              className="dialog-scale-pop elev-2 pointer-events-auto flex w-[760px] max-w-[calc(100vw-32px)] flex-col rounded-lg border"
              style={{
                height: 'min(600px, calc(100vh - 64px))',
                background: 'var(--bg-elevated)',
                borderColor: 'var(--border-bright)',
              }}
            >
            <fieldset disabled={submitting} className="contents">
            <div
              className="flex shrink-0 items-center gap-3 border-b px-5 py-4"
              style={{ borderColor: 'var(--border)' }}
            >
              <div
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded"
                style={{
                  background: 'color-mix(in oklab, var(--accent) 12%, transparent)',
                  color: 'var(--accent)',
                }}
              >
                <Folder size={18} aria-hidden />
              </div>
              <div className="min-w-0 flex-1">
                <Dialog.Title className="text-lg font-semibold text-pri">
                  {t('workspace.browse.title')}
                </Dialog.Title>
                <Dialog.Description
                  className="mono truncate text-xs text-ter"
                  data-testid="fs-root-path"
                >
                  {browse.root_path
                    ? t('workspace.browse.root', { path: browse.root_path })
                    : t('workspace.browse.rootLoading')}
                </Dialog.Description>
              </div>
              <Dialog.Close asChild>
                <button
                  type="button"
                  disabled={submitting}
                  aria-label={t('common.closeDialog')}
                  className="flex h-7 w-7 items-center justify-center rounded text-sec hover:bg-3 hover:text-pri"
                >
                  <X size={14} aria-hidden />
                </button>
              </Dialog.Close>
            </div>

            <nav
              className="flex shrink-0 items-center gap-1 border-b px-4 py-2 text-xs"
              style={{ borderColor: 'var(--border)' }}
              aria-label={t('workspace.browse.breadcrumb')}
              data-testid="fs-breadcrumb"
            >
              <button
                type="button"
                onClick={() => (browse.parent_path ? navigate(browse.parent_path) : null)}
                disabled={!browse.parent_path || submitting}
                aria-label={t('workspace.browse.parentAria')}
                className="flex items-center gap-1 rounded px-2 py-0.5 text-sec hover:bg-3 hover:text-pri disabled:opacity-40"
              >
                <ArrowUp size={12} aria-hidden /> {t('workspace.browse.up')}
              </button>
              <div className="mx-2 h-4 w-px" style={{ background: 'var(--border)' }} />
              {breadcrumbs.map((segment, index) => {
                const isLast = index === breadcrumbs.length - 1
                return (
                  <span key={segment.path} className="flex items-center gap-0.5">
                    {index > 0 ? <span className="text-ter">/</span> : null}
                    {isLast ? (
                      <span className="px-1 py-0.5 font-medium text-pri">{segment.label}</span>
                    ) : (
                      <button
                        type="button"
                        onClick={() => navigate(segment.path)}
                        className="rounded px-1 py-0.5 text-sec hover:bg-3 hover:text-pri"
                      >
                        {segment.label}
                      </button>
                    )}
                  </span>
                )
              })}
            </nav>

            <div className="flex min-h-0 flex-1 flex-col sm:flex-row">
              <div className="flex min-h-0 flex-1 flex-col">
                <FsEntryList
                  entries={browse.entries}
                  error={browse.ok ? null : browse.error}
                  loading={loading}
                  onNavigate={navigate}
                  onSelect={selectEntry}
                  selected={selected}
                  truncated={browse.truncated}
                />
              </div>
              <div
                className="flex w-full shrink-0 flex-col gap-3 border-t p-4 sm:w-[280px] sm:border-t-0 sm:border-l"
                style={{ borderColor: 'var(--border)' }}
              >
                <FsSelectionPreview
                  onSuggestedNameChange={setName}
                  probe={probe}
                  suggestedName={name}
                />
                <WorkspaceCommandPresetSelect
                  disabled={submitting}
                  error={commandPresetError ?? presetAvailabilityError}
                  onChange={onCommandPresetChange}
                  presets={commandPresets}
                  value={commandPresetId}
                />
                <button
                  type="button"
                  disabled={submitting}
                  onClick={() => setStartupExpanded((v) => !v)}
                  className="flex items-center gap-1.5 text-left text-xs uppercase tracking-wider text-ter hover:text-sec"
                >
                  {startupExpanded ? (
                    <ChevronDown size={12} aria-hidden />
                  ) : (
                    <ChevronRight size={12} aria-hidden />
                  )}
                  {t('workspace.advanced.startup')}
                </button>
                {startupExpanded ? (
                  <label className="flex flex-col gap-2 text-xs uppercase tracking-wider text-ter">
                    {t('workspace.field.startup')}
                    <input
                      type="text"
                      disabled={submitting}
                      value={startupCommand}
                      onChange={(event) => setStartupCommand(event.target.value)}
                      placeholder={t('workspace.field.startupPlaceholder')}
                      className="input mono"
                      data-testid="fs-startup-command"
                    />
                    <span className="text-xs normal-case tracking-normal text-ter">
                      {t('workspace.startup.hintShort')}
                    </span>
                  </label>
                ) : null}
                <button
                  type="button"
                  disabled={submitting}
                  onClick={() => setAdvanced((v) => !v)}
                  className="flex items-center gap-1.5 text-left text-xs uppercase tracking-wider text-ter hover:text-sec"
                >
                  {advanced ? (
                    <ChevronDown size={12} aria-hidden />
                  ) : (
                    <ChevronRight size={12} aria-hidden />
                  )}
                  {t('workspace.advanced.pastePath')}
                </button>
                {advanced ? (
                  <label className="flex flex-col gap-2 text-xs uppercase tracking-wider text-ter">
                    {t('workspace.field.absolutePath')}
                    <input
                      type="text"
                      disabled={submitting}
                      value={manualPath}
                      onChange={(event) => setManualPath(event.target.value)}
                      placeholder={t('workspace.field.absolutePathPlaceholder')}
                      className="input mono"
                      data-testid="fs-manual-path"
                    />
                  </label>
                ) : null}
              </div>
            </div>

            <div
              className="flex shrink-0 items-center gap-3 border-t px-5 py-3"
              style={{ borderColor: 'var(--border)' }}
            >
              <button
                type="button"
                disabled={submitting}
                onClick={onBack}
                className="icon-btn icon-btn--tertiary"
                data-testid="server-browse-back"
              >
                <ArrowLeft size={14} aria-hidden />
                {t('common.previous')}
              </button>
              <span
                role={submitError ? 'alert' : undefined}
                data-testid={submitError ? 'workspace-create-error' : undefined}
                className="min-w-0 flex-1 truncate text-xs"
                style={{ color: submitError ? 'var(--status-red)' : undefined }}
                title={submitError ?? undefined}
              >
                {submitError}
              </span>
              <div className="flex items-center gap-2">
                <button type="button" onClick={onClose} disabled={submitting} className="icon-btn">
                  {t('common.cancel')}
                </button>
                <button
                  type="submit"
                  disabled={!canCreate || submitting}
                  data-testid="add-workspace-create"
                  className="icon-btn icon-btn--primary"
                >
                  {submitting ? (
                    <>
                      <LoaderCircle size={14} className="animate-spin" aria-hidden />
                      {t('workspace.confirm.creating')}
                    </>
                  ) : (
                    t('workspace.confirm.create')
                  )}
                </button>
              </div>
            </div>
            </fieldset>
            </form>
          </Dialog.Content>
        </div>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
