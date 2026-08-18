import { useEffect, useRef } from 'react'

import type { TeamListItem, WorkerRole, WorkspaceSummary } from '../../../src/shared/types.js'
import type { TerminalRunSummary } from '../api.js'
import type { TranslationKey } from '../i18n.js'
import { useI18n } from '../i18n.js'
import type { NotifyOptions } from './NotificationProvider.js'
import { useNotifications } from './NotificationProvider.js'

interface WorkspaceNotificationsProps {
  terminalRuns: TerminalRunSummary[]
  workers: TeamListItem[]
  workspace: WorkspaceSummary | undefined
}

type WorkerSnapshot = Pick<TeamListItem, 'id' | 'name' | 'pendingTaskCount' | 'role' | 'status'>

interface WorkspaceSnapshot {
  workspaceId: string
  workers: ReadonlyMap<string, WorkerSnapshot>
}

type WorkerEvent =
  | { kind: 'stopped'; worker: WorkerSnapshot }
  | { kind: 'started'; worker: WorkerSnapshot }
  | { kind: 'reported'; worker: WorkerSnapshot }

const ROLE_KEYS: Record<WorkerRole, TranslationKey> = {
  coder: 'role.coder',
  custom: 'role.custom',
  reviewer: 'role.reviewer',
  tester: 'role.tester',
}

const snapshotWorkers = (workers: TeamListItem[]): ReadonlyMap<string, WorkerSnapshot> =>
  new Map(
    workers.map(({ id, name, pendingTaskCount, role, status }) => [
      id,
      { id, name, pendingTaskCount, role, status },
    ])
  )

const classifyTransition = (
  before: WorkerSnapshot,
  after: WorkerSnapshot
): WorkerEvent | null => {
  if (before.status !== 'stopped' && after.status === 'stopped') {
    return { kind: 'stopped', worker: after }
  }
  if (before.status === 'stopped' && after.status !== 'stopped') {
    return { kind: 'started', worker: after }
  }
  const completedWork =
    after.pendingTaskCount < before.pendingTaskCount ||
    (before.status === 'working' && after.status === 'idle')
  return completedWork ? { kind: 'reported', worker: after } : null
}

const transitionsSince = (
  previous: WorkspaceSnapshot,
  workers: ReadonlyMap<string, WorkerSnapshot>
): WorkerEvent[] => {
  const events: WorkerEvent[] = []
  for (const current of workers.values()) {
    const prior = previous.workers.get(current.id)
    if (!prior) continue
    const event = classifyTransition(prior, current)
    if (event) events.push(event)
  }
  return events
}

type Translator = ReturnType<typeof useI18n>['t']

const describeEvent = (
  event: WorkerEvent,
  workspace: WorkspaceSummary,
  t: Translator
): NotifyOptions => {
  const { worker } = event
  if (event.kind === 'stopped') {
    return {
      brief: t('notifications.workerStopped.brief', { name: worker.name }),
      detail: t('notifications.workerStopped.detail', {
        count: worker.pendingTaskCount,
        name: worker.name,
        workspace: workspace.name,
      }),
      kind: 'error',
      title: t('notifications.workerStopped.title'),
    }
  }
  if (event.kind === 'started') {
    return {
      brief: t('notifications.workerStarted.brief', { name: worker.name }),
      detail: t('notifications.workerStarted.detail', {
        name: worker.name,
        role: t(ROLE_KEYS[worker.role]),
        workspace: workspace.name,
      }),
      kind: 'success',
      title: t('notifications.workerStarted.title'),
    }
  }
  return {
    brief: t('notifications.workerReported.brief', { name: worker.name }),
    detail: t('notifications.workerReported.detail', {
      count: worker.pendingTaskCount,
      name: worker.name,
      workspace: workspace.name,
    }),
    kind: 'success',
    title: t('notifications.workerReported.title'),
  }
}

/** Observes the bounded Team projection and emits only transitions visible to the user. */
export const WorkspaceNotifications = ({
  terminalRuns: _terminalRuns,
  workers,
  workspace,
}: WorkspaceNotificationsProps) => {
  const { notify } = useNotifications()
  const { t } = useI18n()
  const previous = useRef<WorkspaceSnapshot | null>(null)

  useEffect(() => {
    if (!workspace) {
      previous.current = null
      return
    }

    const workersNow = snapshotWorkers(workers)
    const prior = previous.current
    previous.current = { workspaceId: workspace.id, workers: workersNow }
    if (!prior || prior.workspaceId !== workspace.id) return

    for (const event of transitionsSince(prior, workersNow)) {
      notify(describeEvent(event, workspace, t))
    }
  }, [notify, t, workers, workspace])

  return null
}
