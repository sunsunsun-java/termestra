export const MAX_TERMINAL_TABS = 64
const MAX_TERMINAL_TAB_ID_CHARS = 512

const isTerminalTabId = (value: unknown): value is string =>
  typeof value === 'string' &&
  value.length > 0 &&
  value.length <= MAX_TERMINAL_TAB_ID_CHARS &&
  (value.startsWith('worker:') || value.startsWith('shell:'))

export const sanitizeTerminalTabIds = (value: unknown): string[] => {
  if (!Array.isArray(value)) return []
  const recent: string[] = []
  const seen = new Set<string>()
  for (let index = value.length - 1; index >= 0 && recent.length < MAX_TERMINAL_TABS; index--) {
    const id = value[index]
    if (!isTerminalTabId(id) || seen.has(id)) continue
    seen.add(id)
    recent.unshift(id)
  }
  return recent
}

export const appendBoundedTerminalTab = (current: readonly string[], id: string): string[] => {
  if (!isTerminalTabId(id)) return [...current]
  if (current.includes(id)) return [...current]
  const next = [...current, id]
  return next.slice(-MAX_TERMINAL_TABS)
}
