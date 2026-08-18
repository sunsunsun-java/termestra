export interface WorkspaceColor {
  token: string
  label: string
}

const WORKSPACE_COLORS = [
  ['accent', 'var(--accent)'],
  ['blue', 'var(--status-blue)'],
  ['purple', 'var(--status-purple)'],
  ['orange', 'var(--status-orange)'],
  ['green', 'var(--status-green)'],
  ['gold', 'var(--status-gold)'],
] as const

/** FNV-1a gives stable distribution for UUIDs as well as short test IDs. */
const hashIdentity = (identity: string): number => {
  let hash = 0x811c9dc5
  for (const character of identity) {
    hash ^= character.codePointAt(0) ?? 0
    hash = Math.imul(hash, 0x01000193)
  }
  return hash >>> 0
}

export const pickWorkspaceColor = (workspaceId: string): WorkspaceColor => {
  const [label, token] = WORKSPACE_COLORS[hashIdentity(workspaceId) % WORKSPACE_COLORS.length] as (
    typeof WORKSPACE_COLORS
  )[number]
  return { label, token }
}

const firstGrapheme = (text: string): string | undefined => {
  if (typeof Intl.Segmenter !== 'function') return Array.from(text)[0]
  const iterator = new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text)[
    Symbol.iterator
  ]()
  return iterator.next().value?.segment
}

export const deriveInitial = (name: string): string => {
  const visibleName = name.trim()
  if (!visibleName) return '?'
  return firstGrapheme(visibleName)?.toLocaleUpperCase() ?? '?'
}
