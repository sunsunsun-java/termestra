const MIN_PCT = 0.3
const MAX_PCT = 0.78
const MIN_ORCHESTRATOR_WIDTH = 480
const MIN_WORKERS_WIDTH = 320
const COMPACT_BREAKPOINT = MIN_ORCHESTRATOR_WIDTH + MIN_WORKERS_WIDTH
const COMPACT_MIN_PCT = 0.4
const COMPACT_MAX_PCT = 0.6

/**
 * Computes legal orchestrator shares for the available split container.
 * Below the sum of the desktop minima, neither pane can keep its pixel floor;
 * a balanced compact range prevents either pane collapsing into a text strip.
 */
export const boundsForPaneWidth = (width: number): { min: number; max: number } => {
  if (width <= 0) return { min: MIN_PCT, max: MAX_PCT }
  if (width < COMPACT_BREAKPOINT) {
    const progress = width / COMPACT_BREAKPOINT
    return {
      min: COMPACT_MIN_PCT + (COMPACT_MAX_PCT - COMPACT_MIN_PCT) * progress,
      max: COMPACT_MAX_PCT,
    }
  }
  const min = Math.max(MIN_PCT, MIN_ORCHESTRATOR_WIDTH / width)
  const max = Math.min(MAX_PCT, 1 - MIN_WORKERS_WIDTH / width)
  return { min, max: Math.max(min, max) }
}
