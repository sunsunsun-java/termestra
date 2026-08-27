/**
 * Cross-cutting types for the macOS "Open workspace in editor/app" feature.
 */

export type OpenTargetId =
  | 'vscode'
  | 'intellij-idea'
  | 'cursor'
  | 'finder'
  | 'terminal'
  | 'ghostty'
  | 'zed'

export type OpenTargetPlatform = 'mac'

export const OPEN_TARGET_IDS_BY_PLATFORM: Record<OpenTargetPlatform, readonly OpenTargetId[]> = {
  mac: ['vscode', 'intellij-idea', 'cursor', 'finder', 'terminal', 'ghostty', 'zed'],
}

const ALL_TARGET_IDS = new Set<OpenTargetId>(OPEN_TARGET_IDS_BY_PLATFORM.mac)

export const isOpenTargetId = (value: unknown): value is OpenTargetId =>
  typeof value === 'string' && ALL_TARGET_IDS.has(value as OpenTargetId)

export const isOpenTargetSupported = (
  targetId: OpenTargetId,
  platform: OpenTargetPlatform
): boolean => OPEN_TARGET_IDS_BY_PLATFORM[platform].includes(targetId)

export const getDefaultOpenTargetId = (): OpenTargetId => 'finder'

export type OpenWorkspaceErrorCode =
  | 'invalid-path'
  | 'invalid-target'
  | 'app-not-installed'
  | 'command-not-in-path'
  | 'unknown'
