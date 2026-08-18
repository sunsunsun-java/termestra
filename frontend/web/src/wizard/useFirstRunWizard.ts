import { useCallback, useState } from 'react'

import type { WorkspaceSummary } from '../../../src/shared/types.js'
import { useFirstRunFlag } from './useFirstRunFlag.js'

type FirstRunWizardState = {
  closeWizard: (shouldMarkSeen?: boolean) => void
  wizardOpen: boolean
}

/**
 * Derives first-run visibility from loaded workspace state and the persisted
 * seen flag. A page-local dismissal latch prevents polling snapshots from
 * reopening the wizard during the action the user just chose.
 */
export const useFirstRunWizard = (
  workspaces: WorkspaceSummary[] | null
): FirstRunWizardState => {
  const { markSeen, seen } = useFirstRunFlag()
  const [dismissedForSession, setDismissedForSession] = useState(false)

  const closeWizard = useCallback(
    (shouldMarkSeen = true): void => {
      setDismissedForSession(true)
      if (shouldMarkSeen) markSeen()
    },
    [markSeen]
  )

  const bootstrapComplete = workspaces !== null
  const needsFirstWorkspace = bootstrapComplete && workspaces.length === 0
  const wizardOpen = needsFirstWorkspace && !seen && !dismissedForSession

  return { closeWizard, wizardOpen }
}
