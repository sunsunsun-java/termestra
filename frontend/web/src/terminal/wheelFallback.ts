type TerminalLike = {
  buffer?: { active?: { type?: string } }
  attachCustomWheelEventHandler?: (handler: (event: WheelEvent) => boolean) => void
  modes?: {
    applicationCursorKeysMode?: boolean
    mouseTrackingMode?: string
  }
}

export type TerminalWheelInputProfile = 'default' | 'opencode'

type WheelEventData = Pick<WheelEvent, 'deltaMode' | 'deltaY' | 'shiftKey'>
type WheelFallbackResult = { handled: boolean; input: string | null }
type ScrollDirection = 'up' | 'down'

// UI Events defines 0 as pixel mode. Using the protocol value keeps this
// pure resolver usable in runtimes that do not expose a WheelEvent constructor.
const DOM_DELTA_PIXEL = 0
const PIXELS_PER_LINE = 16
const TRACKPAD_SCALE = 0.3

const shouldPassThrough = (
  terminal: TerminalLike,
  profile: TerminalWheelInputProfile,
  event: WheelEventData
): boolean => {
  if (terminal.buffer?.active?.type !== 'alternate') return true
  if (event.deltaY === 0 || event.shiftKey) return true

  const tracking = terminal.modes?.mouseTrackingMode
  return profile === 'default' && tracking !== undefined && tracking !== 'none'
}

const encodeDirection = (
  terminal: TerminalLike,
  profile: TerminalWheelInputProfile,
  direction: ScrollDirection
): string => {
  if (profile === 'opencode') return direction === 'up' ? '\u001b[5~' : '\u001b[6~'

  const suffix = direction === 'up' ? 'A' : 'B'
  const prefix = terminal.modes?.applicationCursorKeysMode ? '\u001bO' : '\u001b['
  return `${prefix}${suffix}`
}

const wheelLines = (event: WheelEventData): number => {
  if (event.deltaMode !== DOM_DELTA_PIXEL) return event.deltaY
  const trackpadScale = Math.abs(event.deltaY) < 50 ? TRACKPAD_SCALE : 1
  return (event.deltaY / PIXELS_PER_LINE) * trackpadScale
}

export const createAlternateScreenWheelInputResolver = (
  terminal: TerminalLike,
  profile: TerminalWheelInputProfile = 'default'
) => {
  let carriedLines = 0

  return (event: WheelEventData): WheelFallbackResult => {
    if (shouldPassThrough(terminal, profile, event)) {
      carriedLines = 0
      return { handled: false, input: null }
    }

    if (event.deltaMode === DOM_DELTA_PIXEL) {
      carriedLines += wheelLines(event)
      const completeLines = Math.trunc(carriedLines)
      carriedLines -= completeLines
      if (completeLines === 0) return { handled: true, input: null }
      return {
        handled: true,
        input: encodeDirection(terminal, profile, completeLines < 0 ? 'up' : 'down'),
      }
    }

    carriedLines = 0
    return {
      handled: true,
      input: encodeDirection(terminal, profile, event.deltaY < 0 ? 'up' : 'down'),
    }
  }
}

export const getAlternateScreenWheelInput = (
  terminal: TerminalLike,
  event: WheelEventData,
  profile: TerminalWheelInputProfile = 'default'
): string | null => createAlternateScreenWheelInputResolver(terminal, profile)(event).input

export const attachAlternateScreenWheelFallback = ({
  element,
  profile = 'default',
  sendInput,
  terminal,
}: {
  element: HTMLElement
  profile?: TerminalWheelInputProfile
  sendInput: (chunk: string) => void
  terminal: TerminalLike
}): (() => void) => {
  const resolve = createAlternateScreenWheelInputResolver(terminal, profile)
  const intercept = (event: WheelEvent): boolean => {
    const result = resolve(event)
    if (!result.handled) return true

    event.preventDefault()
    event.stopPropagation()
    if (result.input !== null) sendInput(result.input)
    return false
  }

  if (terminal.attachCustomWheelEventHandler) {
    terminal.attachCustomWheelEventHandler(intercept)
    return () => undefined
  }

  const captureWheel = (event: WheelEvent): void => {
    if (!intercept(event)) event.stopImmediatePropagation()
  }
  const options = { capture: true, passive: false } as const
  element.addEventListener('wheel', captureWheel, options)
  return () => element.removeEventListener('wheel', captureWheel, { capture: true })
}
