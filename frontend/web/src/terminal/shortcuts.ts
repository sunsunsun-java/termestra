export type TerminalShortcutAction =
  | { kind: 'send'; bytes: string }
  | { kind: 'clear' }
  | { kind: 'block' }
  | { kind: 'passthrough' }

export const SHORTCUT_BYTES = {
  killToLineStart: '\x15',
  lineStart: '\x01',
  lineEnd: '\x05',
  killWordBack: '\x1b\x7f',
  wordBack: '\x1bb',
  wordForward: '\x1bf',
  shiftEnter: '\x1b[13;2u',
} as const

const PASS_THROUGH: TerminalShortcutAction = { kind: 'passthrough' }

const MAC_CHORDS: Readonly<Record<string, TerminalShortcutAction>> = {
  'alt:ArrowLeft': { kind: 'send', bytes: SHORTCUT_BYTES.wordBack },
  'alt:ArrowRight': { kind: 'send', bytes: SHORTCUT_BYTES.wordForward },
  'alt:Backspace': { kind: 'send', bytes: SHORTCUT_BYTES.killWordBack },
  'meta:ArrowLeft': { kind: 'send', bytes: SHORTCUT_BYTES.lineStart },
  'meta:ArrowRight': { kind: 'send', bytes: SHORTCUT_BYTES.lineEnd },
  'meta:Backspace': { kind: 'send', bytes: SHORTCUT_BYTES.killToLineStart },
  'meta:k': { kind: 'clear' },
}

export const isMacPlatform = (): boolean =>
  typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)

const macChord = (event: KeyboardEvent): string | null => {
  if (event.metaKey && !event.altKey && !event.ctrlKey && !event.shiftKey) {
    const key = event.key.length === 1 ? event.key.toLowerCase() : event.key
    return `meta:${key}`
  }
  if (event.altKey && !event.metaKey && !event.ctrlKey && !event.shiftKey) {
    return `alt:${event.key}`
  }
  return null
}

export const resolveTerminalShortcut = (
  event: KeyboardEvent,
  options: { isMac?: boolean } = {}
): TerminalShortcutAction => {
  if (event.isComposing) return PASS_THROUGH

  if (event.key === 'Enter' && event.shiftKey) {
    if (event.type === 'keydown') return { kind: 'block' }
    if (event.type === 'keypress') return { kind: 'send', bytes: SHORTCUT_BYTES.shiftEnter }
    return PASS_THROUGH
  }

  if (!(options.isMac ?? isMacPlatform()) || event.type !== 'keydown') return PASS_THROUGH
  const chord = macChord(event)
  return (chord && MAC_CHORDS[chord]) || PASS_THROUGH
}
