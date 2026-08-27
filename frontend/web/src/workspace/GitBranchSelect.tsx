import { Check, ChevronDown, GitBranch, LoaderCircle, Search } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import {
  listWorkspaceRegistrationOptions,
  type FsProbeResponse,
  type WorkspaceRegistrationOptions,
  type WorkspaceRevisionSelectionPayload,
} from '../api.js'
import { useI18n } from '../i18n.js'

type GitBranchSelectProps = {
  disabled?: boolean
  onChange: (selection: WorkspaceRevisionSelectionPayload) => void
  probe: FsProbeResponse
  value: WorkspaceRevisionSelectionPayload
}

export const GitBranchSelect = ({ disabled = false, onChange, probe, value }: GitBranchSelectProps) => {
  const { t } = useI18n()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [options, setOptions] = useState<WorkspaceRegistrationOptions | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const request = useRef(0)
  const inspectionToken = probe.git_inspection_token

  useEffect(() => {
    setOpen(false)
    setQuery('')
    setOptions(null)
    setError(null)
  }, [inspectionToken, probe.path])

  useEffect(() => {
    if (!open || !inspectionToken) return
    const id = ++request.current
    const controller = new AbortController()
    setLoading(true)
    setOptions(null)
    setError(null)
    const timer = window.setTimeout(() => {
      void listWorkspaceRegistrationOptions(inspectionToken, query, controller.signal)
        .then((next) => {
          if (request.current === id) setOptions(next)
        })
        .catch((reason: unknown) => {
          if (controller.signal.aborted || request.current !== id) return
          setError(reason instanceof Error ? reason.message : t('workspace.git.loadFailed'))
        })
        .finally(() => {
          if (request.current === id) setLoading(false)
        })
    }, query ? 180 : 0)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [inspectionToken, open, query, t])

  if (!inspectionToken) {
    return (
      <div className="flex items-center gap-2 text-xs" data-testid="confirm-workspace-git-badge">
        <span className="inline-flex items-center gap-1.5 rounded border px-2 py-1 text-sec">
          <GitBranch size={12} aria-hidden />
          {probe.current_branch ?? t('workspace.git.detached')}
        </span>
        <span className="text-ter">{t('workspace.git.detected')}</span>
      </div>
    )
  }

  const selectedName = value.kind === 'local_branch'
    ? value.name
    : (probe.current_branch ?? t('workspace.git.detached'))

  return (
    <div className="relative flex flex-col gap-2" data-testid="workspace-git-branch-select">
      <span className="text-xs font-medium uppercase tracking-wider text-ter">
        {t('workspace.git.branchLabel')}
      </span>
      <button
        type="button"
        aria-expanded={open}
        aria-haspopup="listbox"
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
        className="input flex items-center gap-2 text-left"
        data-testid="workspace-git-branch-trigger"
      >
        <GitBranch size={14} className="shrink-0 text-ter" aria-hidden />
        <span className="mono min-w-0 flex-1 truncate">{selectedName}</span>
        <ChevronDown size={14} className="shrink-0 text-ter" aria-hidden />
      </button>
      {open ? (
        <div
          className="elev-2 absolute top-full z-20 mt-1 w-full overflow-hidden rounded-lg border p-2"
          style={{ background: 'var(--bg-elevated)', borderColor: 'var(--border-bright)' }}
        >
          <label className="input flex items-center gap-2 px-2 py-1.5">
            <Search size={13} className="shrink-0 text-ter" aria-hidden />
            <input
              autoFocus
              aria-label={t('workspace.git.searchPlaceholder')}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              className="min-w-0 flex-1 bg-transparent text-sm outline-none"
              placeholder={t('workspace.git.searchPlaceholder')}
              data-testid="workspace-git-branch-search"
            />
            {loading ? <LoaderCircle size={13} className="animate-spin text-ter" aria-hidden /> : null}
          </label>
          <div
            role="listbox"
            aria-label={t('workspace.git.branchLabel')}
            className="mt-2 max-h-52 overflow-y-auto"
          >
            {error ? <p role="alert" className="px-2 py-3 text-xs text-red-500">{error}</p> : null}
            {!error && !loading && options?.branches.length === 0 ? (
              <p className="px-2 py-3 text-xs text-ter">{t('workspace.git.noBranches')}</p>
            ) : null}
            {options?.branches.map((branch) => {
              const selected = branch.name === selectedName
              return (
                <button
                  type="button"
                  role="option"
                  aria-selected={selected}
                  disabled={!branch.selectable || !branch.selection_token}
                  key={branch.name}
                  onClick={() => {
                    if (!branch.selection_token) return
                    onChange({
                      kind: 'local_branch',
                      name: branch.name,
                      selection_token: branch.selection_token,
                    })
                    setOpen(false)
                  }}
                  className="flex w-full items-center gap-2 rounded px-2 py-2 text-left text-sm hover:bg-3 disabled:opacity-45"
                  data-testid={`workspace-git-branch-${branch.name}`}
                >
                  <GitBranch size={13} className="shrink-0 text-ter" aria-hidden />
                  <span className="mono min-w-0 flex-1 truncate">{branch.name}</span>
                  {!branch.selectable ? (
                    <span className="text-xs text-ter">{t('workspace.git.checkedOutElsewhere')}</span>
                  ) : null}
                  {selected ? <Check size={14} aria-hidden /> : null}
                </button>
              )
            })}
          </div>
          {options?.next_cursor ? (
            <p className="border-t px-2 pt-2 text-xs text-ter" style={{ borderColor: 'var(--border)' }}>
              {t('workspace.git.refineSearch')}
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}
