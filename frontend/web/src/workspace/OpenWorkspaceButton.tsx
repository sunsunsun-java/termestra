import { Check, ChevronDown, LoaderCircle } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { WorkspaceSummary } from '../../../src/shared/types.js'
import { type OpenWorkspaceResult, openWorkspaceInEditor } from '../api.js'
import type { TranslationKey } from '../i18n.js'
import { useI18n } from '../i18n.js'
import { Tooltip } from '../ui/Tooltip.js'
import { useToast } from '../ui/useToast.js'
import {
  getOpenTargetOption,
  getOpenTargetOptions,
  loadPersistedOpenTargetId,
  type OpenTargetId,
  persistOpenTargetId,
  resolveOpenTargetPlatform,
} from './open-targets.js'

interface OpenWorkspaceButtonProps {
  workspace: WorkspaceSummary | null | undefined
}

interface OpenTargetMarkProps {
  option: ReturnType<typeof getOpenTargetOption>
  size?: 'compact' | 'menu'
}

const OpenTargetMark = ({ option, size = 'compact' }: OpenTargetMarkProps) => {
  const isMenu = size === 'menu'
  const { Icon } = option
  const iconSize = isMenu ? 24 : 20
  return (
    <span
      aria-hidden
      data-testid={`open-target-mark-${option.id}`}
      className={`flex shrink-0 items-center justify-center ${isMenu ? 'h-6 w-6' : 'h-5 w-5'}`}
    >
      <Icon
        aria-hidden
        className="select-none"
        focusable={false}
        size={iconSize}
        strokeWidth={1.8}
        style={{ color: option.tone }}
      />
    </span>
  )
}

const ERROR_TOAST_KEY: Record<
  Exclude<OpenWorkspaceResult & { ok: false }, never>['errorCode'],
  TranslationKey
> = {
  'app-not-installed': 'openWorkspace.error.appNotInstalled',
  'command-not-in-path': 'openWorkspace.error.commandNotInPath',
  'invalid-path': 'openWorkspace.error.invalidPath',
  'invalid-target': 'openWorkspace.error.invalidTarget',
  unknown: 'openWorkspace.error.unknown',
}

export const OpenWorkspaceButton = ({ workspace }: OpenWorkspaceButtonProps) => {
  const { t } = useI18n()
  const toast = useToast()
  const platform = useMemo(() => resolveOpenTargetPlatform(), [])
  const options = useMemo(() => getOpenTargetOptions(platform), [platform])
  const [selectedId, setSelectedId] = useState<OpenTargetId>(() =>
    loadPersistedOpenTargetId(platform)
  )
  const [popoverOpen, setPopoverOpen] = useState(false)
  const [isOpening, setIsOpening] = useState(false)
  const openingRef = useRef(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const mainButtonRef = useRef<HTMLButtonElement>(null)

  const selectedOption = useMemo(
    () => getOpenTargetOption(selectedId, platform),
    [platform, selectedId]
  )
  const selectedLabel = t(selectedOption.labelKey)

  useEffect(() => {
    if (!popoverOpen) return
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setPopoverOpen(false)
    }
    const handlePointer = (event: PointerEvent) => {
      const root = containerRef.current
      if (root && !root.contains(event.target as Node)) setPopoverOpen(false)
    }
    document.addEventListener('keydown', handleKey)
    document.addEventListener('pointerdown', handlePointer)
    return () => {
      document.removeEventListener('keydown', handleKey)
      document.removeEventListener('pointerdown', handlePointer)
    }
  }, [popoverOpen])

  const handleSelect = useCallback((targetId: OpenTargetId) => {
    setSelectedId(targetId)
    persistOpenTargetId(targetId)
    setPopoverOpen(false)
    mainButtonRef.current?.focus()
  }, [])

  const handleOpen = useCallback(async () => {
    if (!workspace || openingRef.current) return
    openingRef.current = true
    setIsOpening(true)
    try {
      const result = await openWorkspaceInEditor(workspace.id, selectedId)
      if (!result.ok) {
        const labelKey = getOpenTargetOption(result.effectiveTargetId, platform).labelKey
        toast.show({
          kind: 'error',
          message: t(ERROR_TOAST_KEY[result.errorCode], { app: t(labelKey) }),
        })
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      toast.show({ kind: 'error', message })
    } finally {
      openingRef.current = false
      setIsOpening(false)
    }
  }, [platform, selectedId, t, toast, workspace])

  const disabled = !workspace
  const disabledTooltip = t('openWorkspace.noWorkspace')
  const mainTooltip = workspace
    ? t('openWorkspace.openIn', { app: selectedLabel, workspace: workspace.name })
    : disabledTooltip

  const mainAriaLabel = workspace
    ? t('openWorkspace.openInAria', { app: selectedLabel, workspace: workspace.name })
    : disabledTooltip

  return (
    <div ref={containerRef} className="relative flex">
      <div
        className="flex overflow-hidden rounded-xl border"
        style={{
          background: 'var(--bg-1)',
          borderColor: 'var(--border)',
          boxShadow: '0 1px 0 color-mix(in oklab, white 3%, transparent)',
        }}
      >
        <Tooltip label={mainTooltip}>
          <span className="flex">
            <button
              ref={mainButtonRef}
              type="button"
              aria-label={mainAriaLabel}
              data-testid="topbar-open-workspace"
              disabled={disabled || isOpening}
              onClick={() => void handleOpen()}
              className="flex h-8 w-10 items-center justify-center text-ter transition-colors hover:bg-3 hover:text-pri focus-visible:z-10 focus-visible:outline focus-visible:outline-1 focus-visible:outline-offset-[-2px] focus-visible:outline-[var(--accent)] disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-transparent"
            >
              {isOpening ? (
                <LoaderCircle size={16} className="animate-spin" aria-hidden />
              ) : (
                <OpenTargetMark option={selectedOption} />
              )}
              <span className="sr-only">{t('openWorkspace.open')}</span>
            </button>
          </span>
        </Tooltip>
        <Tooltip label={t('openWorkspace.selectTarget')}>
          <button
            type="button"
            aria-label={t('openWorkspace.selectTarget')}
            aria-haspopup="menu"
            aria-expanded={popoverOpen}
            data-testid="topbar-open-workspace-chevron"
            disabled={disabled}
            onClick={() => setPopoverOpen((value) => !value)}
            className="flex h-8 w-8 items-center justify-center border-l px-0 text-ter transition-colors hover:bg-3 hover:text-pri focus-visible:z-10 focus-visible:outline focus-visible:outline-1 focus-visible:outline-offset-[-2px] focus-visible:outline-[var(--accent)] disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-transparent"
            style={{ borderColor: 'var(--border)' }}
          >
            <ChevronDown
              size={15}
              aria-hidden
              className={`transition-transform ${popoverOpen ? 'rotate-180' : ''}`}
            />
          </button>
        </Tooltip>
      </div>
      {popoverOpen ? (
        <div
          role="menu"
          aria-label={t('openWorkspace.selectTarget')}
          className="elev-2 absolute top-10 right-0 z-50 w-[224px] overflow-hidden rounded-xl border p-1"
          style={{
            background: 'color-mix(in oklab, var(--bg-elevated) 96%, transparent)',
            borderColor: 'var(--border-bright)',
            boxShadow: '0 18px 45px rgba(0, 0, 0, 0.38), 0 2px 10px rgba(0, 0, 0, 0.24)',
          }}
          data-testid="topbar-open-workspace-menu"
        >
          {options.map((option) => {
            const isSelected = option.id === selectedId
            return (
              <button
                key={option.id}
                role="menuitemradio"
                aria-checked={isSelected}
                type="button"
                onClick={() => handleSelect(option.id)}
                className="flex h-9 w-full cursor-pointer items-center gap-2.5 rounded-lg px-2 text-left text-[13px] font-medium text-pri transition-colors hover:bg-3 focus-visible:outline focus-visible:outline-1 focus-visible:outline-offset-[-2px] focus-visible:outline-[var(--accent)]"
                style={
                  isSelected
                    ? {
                        background: 'color-mix(in oklab, var(--accent) 13%, var(--bg-3))',
                      }
                    : undefined
                }
                data-testid={`topbar-open-workspace-option-${option.id}`}
              >
                <OpenTargetMark option={option} size="menu" />
                <span className="flex-1">{t(option.labelKey)}</span>
                {isSelected ? (
                  <span
                    className="flex h-4 w-4 items-center justify-center rounded-full"
                    style={{
                      background: 'color-mix(in oklab, var(--accent) 22%, transparent)',
                      color: 'var(--accent-hover)',
                    }}
                  >
                    <Check size={11} strokeWidth={2.4} aria-hidden />
                  </span>
                ) : null}
              </button>
            )
          })}
        </div>
      ) : null}
    </div>
  )
}
