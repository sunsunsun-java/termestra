import {
  Code2,
  FileCode2,
  FolderOpen,
  MousePointer2,
  PanelsTopLeft,
  SquareTerminal,
  Terminal,
  type LucideIcon,
} from 'lucide-react'

import {
  getDefaultOpenTargetId,
  isOpenTargetId,
  isOpenTargetSupported,
  OPEN_TARGET_IDS_BY_PLATFORM,
  type OpenTargetId,
  type OpenTargetPlatform,
} from '../../../src/shared/open-targets.js'

export type { OpenTargetId, OpenTargetPlatform }
export { getDefaultOpenTargetId, isOpenTargetSupported, OPEN_TARGET_IDS_BY_PLATFORM }

export interface OpenTargetOption {
  id: OpenTargetId
  /**
   * i18n key for the display label. Translation lives in `i18n.tsx` so Finder
   * stays consistent with the UI language toggle.
   */
  labelKey:
    | 'openWorkspace.target.vscode'
    | 'openWorkspace.target.intellijIdea'
    | 'openWorkspace.target.cursor'
    | 'openWorkspace.target.finder.mac'
    | 'openWorkspace.target.terminal'
    | 'openWorkspace.target.ghostty'
    | 'openWorkspace.target.zed'
  /** A generic, non-branded glyph that identifies the target category. */
  Icon: LucideIcon
  tone: string
}

const FINDER_LABEL_KEY_BY_PLATFORM: Record<OpenTargetPlatform, OpenTargetOption['labelKey']> = {
  mac: 'openWorkspace.target.finder.mac',
}

const TARGET_DATA: Record<OpenTargetId, Omit<OpenTargetOption, 'id'>> = {
  vscode: {
    labelKey: 'openWorkspace.target.vscode',
    Icon: PanelsTopLeft,
    tone: 'var(--status-blue)',
  },
  'intellij-idea': {
    labelKey: 'openWorkspace.target.intellijIdea',
    Icon: FileCode2,
    tone: 'var(--status-orange)',
  },
  cursor: {
    labelKey: 'openWorkspace.target.cursor',
    Icon: MousePointer2,
    tone: 'var(--accent)',
  },
  finder: {
    labelKey: 'openWorkspace.target.finder.mac',
    Icon: FolderOpen,
    tone: 'var(--status-blue)',
  },
  terminal: {
    labelKey: 'openWorkspace.target.terminal',
    Icon: Terminal,
    tone: 'var(--text-secondary)',
  },
  ghostty: {
    labelKey: 'openWorkspace.target.ghostty',
    Icon: SquareTerminal,
    tone: 'var(--status-purple)',
  },
  zed: {
    labelKey: 'openWorkspace.target.zed',
    Icon: Code2,
    tone: 'var(--status-green)',
  },
}

const resolveLabelKey = (
  targetId: OpenTargetId,
  platform: OpenTargetPlatform
): OpenTargetOption['labelKey'] =>
  targetId === 'finder' ? FINDER_LABEL_KEY_BY_PLATFORM[platform] : TARGET_DATA[targetId].labelKey

export const getOpenTargetOption = (
  targetId: OpenTargetId,
  platform: OpenTargetPlatform
): OpenTargetOption => {
  const supportedId = isOpenTargetSupported(targetId, platform)
    ? targetId
    : getDefaultOpenTargetId()
  return {
    id: supportedId,
    ...TARGET_DATA[supportedId],
    labelKey: resolveLabelKey(supportedId, platform),
  }
}

export const getOpenTargetOptions = (platform: OpenTargetPlatform): readonly OpenTargetOption[] =>
  OPEN_TARGET_IDS_BY_PLATFORM[platform].map((targetId) => {
    return {
      id: targetId,
      ...TARGET_DATA[targetId],
      labelKey: resolveLabelKey(targetId, platform),
    }
  })

/**
 * Termestra's published runtime is macOS-only, so the browser and server share
 * one fixed target set.
 */
export const resolveOpenTargetPlatform = (): OpenTargetPlatform => 'mac'

export const PREFERRED_OPEN_TARGET_STORAGE_KEY = 'termestra.openTarget.preferred'

const readPreferredOpenTargetRaw = (): string | null => {
  try {
    return window.localStorage.getItem(PREFERRED_OPEN_TARGET_STORAGE_KEY)
  } catch {
    return null
  }
}

export const loadPersistedOpenTargetId = (platform: OpenTargetPlatform): OpenTargetId => {
  const fallback = getDefaultOpenTargetId()
  if (typeof window === 'undefined') return fallback
  const raw = readPreferredOpenTargetRaw()
  if (!raw) return fallback
  if (isOpenTargetId(raw) && isOpenTargetSupported(raw, platform)) {
    return raw
  }
  return fallback
}

export const persistOpenTargetId = (targetId: OpenTargetId): void => {
  try {
    window.localStorage.setItem(PREFERRED_OPEN_TARGET_STORAGE_KEY, targetId)
  } catch {
    // Quota exceeded / private browsing — fall back to in-memory selection.
  }
}
