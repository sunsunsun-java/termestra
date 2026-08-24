import type { TerminalRunSummary } from './api.js'
import { useI18n } from './i18n.js'
import { TerminalView } from './terminal/TerminalView.js'
import { mergeTerminalRuns } from './terminal/useOptimisticTerminalRuns.js'

type WorkspaceTerminalPanelsProps = {
  hidden?: boolean
  optimisticRuns?: TerminalRunSummary[]
  terminalRuns: TerminalRunSummary[]
  workspaceId: string
}

type TerminalDescriptor = {
  bookmarksEnabled: boolean
  inputProfile: NonNullable<TerminalRunSummary['terminal_input_profile']>
  runId: string
  title: string
}

const describeTerminal = (run: TerminalRunSummary, workspaceId: string): TerminalDescriptor => ({
  bookmarksEnabled: run.agent_id === `${workspaceId}:orchestrator`,
  inputProfile: run.terminal_input_profile ?? 'default',
  runId: run.run_id,
  title: `${run.agent_name} (${run.status})`,
})

/**
 * Keeps every live PTY represented at workspace scope. TerminalView handles
 * parking and re-parenting its host into whichever panel slot is currently
 * visible, so changing tabs does not tear down the terminal process view.
 */
export const WorkspaceTerminalPanels = ({
  hidden = false,
  optimisticRuns = [],
  terminalRuns,
  workspaceId,
}: WorkspaceTerminalPanelsProps) => {
  const { t } = useI18n()
  const terminals = mergeTerminalRuns(terminalRuns, optimisticRuns, workspaceId).map(
    (run) => describeTerminal(run, workspaceId)
  )

  return (
    <section
      aria-hidden={hidden ? true : undefined}
      aria-label={t('terminalPanels.aria')}
      data-terminal-workspace={workspaceId}
      hidden={hidden}
    >
      {terminals.map(({ bookmarksEnabled, inputProfile, runId, title }) => (
        <TerminalView
          bookmarksEnabled={bookmarksEnabled}
          inputProfile={inputProfile}
          key={runId}
          runId={runId}
          title={title}
        />
      ))}
    </section>
  )
}
