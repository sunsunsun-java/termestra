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
  inputProfile: NonNullable<TerminalRunSummary['terminal_input_profile']>
  runId: string
  title: string
}

const describeTerminal = (run: TerminalRunSummary): TerminalDescriptor => ({
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
    describeTerminal
  )

  return (
    <section
      aria-hidden={hidden ? true : undefined}
      aria-label={t('terminalPanels.aria')}
      data-terminal-workspace={workspaceId}
      hidden={hidden}
    >
      {terminals.map(({ inputProfile, runId, title }) => (
        <TerminalView inputProfile={inputProfile} key={runId} runId={runId} title={title} />
      ))}
    </section>
  )
}
