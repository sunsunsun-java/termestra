import {
  getDefaultOpenTargetIdForPlatform,
  isOpenTargetId,
  isOpenTargetSupported,
  OPEN_TARGET_IDS_BY_PLATFORM,
  type OpenTargetId,
  type OpenTargetPlatform,
} from '../../../src/shared/open-targets.js'

export type { OpenTargetId, OpenTargetPlatform }
export { getDefaultOpenTargetIdForPlatform, isOpenTargetSupported, OPEN_TARGET_IDS_BY_PLATFORM }

export interface OpenTargetOption {
  id: OpenTargetId
  /**
   * i18n key for the display label. Translation lives in `i18n.tsx` so that
   * "Finder" → "File Explorer" / "File Manager" stays consistent with the UI
   * language toggle rather than being keyed off the OS platform.
   */
  labelKey:
    | 'openWorkspace.target.vscode'
    | 'openWorkspace.target.intellijIdea'
    | 'openWorkspace.target.cursor'
    | 'openWorkspace.target.finder.mac'
    | 'openWorkspace.target.finder.windows'
    | 'openWorkspace.target.finder.linux'
    | 'openWorkspace.target.terminal'
    | 'openWorkspace.target.ghostty'
    | 'openWorkspace.target.zed'
  /** Official product/application artwork served from Vite's public directory. */
  iconSrc: string
}

const FINDER_LABEL_KEY_BY_PLATFORM: Record<OpenTargetPlatform, OpenTargetOption['labelKey']> = {
  mac: 'openWorkspace.target.finder.mac',
  windows: 'openWorkspace.target.finder.windows',
  linux: 'openWorkspace.target.finder.linux',
  other: 'openWorkspace.target.finder.linux',
}

const TARGET_DATA: Record<OpenTargetId, Omit<OpenTargetOption, 'id'>> = {
  vscode: {
    labelKey: 'openWorkspace.target.vscode',
    iconSrc: '/open-target-icons/vscode.png',
  },
  'intellij-idea': {
    labelKey: 'openWorkspace.target.intellijIdea',
    iconSrc: '/open-target-icons/intellij-idea.png',
  },
  cursor: {
    labelKey: 'openWorkspace.target.cursor',
    iconSrc: '/open-target-icons/cursor.png',
  },
  finder: {
    labelKey: 'openWorkspace.target.finder.mac',
    iconSrc: '/open-target-icons/finder-mac.png',
  },
  terminal: {
    labelKey: 'openWorkspace.target.terminal',
    iconSrc: '/open-target-icons/terminal-mac.png',
  },
  ghostty: {
    labelKey: 'openWorkspace.target.ghostty',
    iconSrc: '/open-target-icons/ghostty.png',
  },
  zed: { labelKey: 'openWorkspace.target.zed', iconSrc: '/open-target-icons/zed.png' },
}

const FINDER_ICON_BY_PLATFORM: Record<OpenTargetPlatform, string> = {
  mac: '/open-target-icons/finder-mac.png',
  windows: '/open-target-icons/finder-windows.svg',
  linux: '/open-target-icons/finder-linux.svg',
  other: '/open-target-icons/finder-linux.svg',
}

const resolveIconSrc = (targetId: OpenTargetId, platform: OpenTargetPlatform): string =>
  targetId === 'finder' ? FINDER_ICON_BY_PLATFORM[platform] : TARGET_DATA[targetId].iconSrc

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
    : getDefaultOpenTargetIdForPlatform(platform)
  return {
    id: supportedId,
    iconSrc: resolveIconSrc(supportedId, platform),
    labelKey: resolveLabelKey(supportedId, platform),
  }
}

export const getOpenTargetOptions = (platform: OpenTargetPlatform): readonly OpenTargetOption[] =>
  OPEN_TARGET_IDS_BY_PLATFORM[platform].map((targetId) => {
    return {
      id: targetId,
      iconSrc: resolveIconSrc(targetId, platform),
      labelKey: resolveLabelKey(targetId, platform),
    }
  })

/**
 * Browser-side platform detection. Server already validates the requested
 * target against its own platform, so a misdetection here at worst shows an
 * impossible option in the dropdown — the server falls back gracefully.
 */
export const resolveOpenTargetPlatform = (): OpenTargetPlatform => {
  if (typeof navigator === 'undefined') return 'other'
  const source = `${navigator.userAgent} ${navigator.platform}`.toLowerCase()
  if (source.includes('mac') || source.includes('darwin')) return 'mac'
  if (source.includes('win')) return 'windows'
  if (source.includes('linux') || source.includes('x11')) return 'linux'
  return 'other'
}

export const PREFERRED_OPEN_TARGET_STORAGE_KEY = 'termestra.openTarget.preferred'

const readPreferredOpenTargetRaw = (): string | null => {
  try {
    return window.localStorage.getItem(PREFERRED_OPEN_TARGET_STORAGE_KEY)
  } catch {
    return null
  }
}

export const loadPersistedOpenTargetId = (platform: OpenTargetPlatform): OpenTargetId => {
  const fallback = getDefaultOpenTargetIdForPlatform(platform)
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
