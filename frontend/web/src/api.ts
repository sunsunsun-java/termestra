import type { OpenTargetId, OpenWorkspaceErrorCode } from '../../src/shared/open-targets.js'
import type {
  AgentSummary,
  TeamListItem,
  TeamListItemPayload,
  WorkerRole,
  WorkspaceSummary,
} from '../../src/shared/types.js'
import { createUiSessionFetch } from './lib/ui-session-fetch.js'
import { requireBoundedList } from './lib/bounded-list.js'
import { createSingleFlight } from './lib/single-flight.js'
import { readApiRequestError } from './lib/api-request-error.js'
import {
  MAX_TASKS_TRANSPORT_CONTENT_BYTES,
  tasksContentFitsTransport,
} from './tasks/tasks-content-limit.js'

export type { OpenTargetId, OpenWorkspaceErrorCode }
export { ApiRequestError } from './lib/api-request-error.js'

const fromPayload = (payload: TeamListItemPayload): TeamListItem => ({
  id: payload.id,
  name: payload.name,
  role: payload.role,
  status: payload.status,
  pendingTaskCount: payload.pending_task_count,
  ...(payload.last_pty_line ? { lastPtyLine: payload.last_pty_line } : {}),
  ...(payload.command_preset_id ? { commandPresetId: payload.command_preset_id } : {}),
})

const readErrorMessage = async (response: Response, fallback: string): Promise<string> => {
  try {
    const body = (await response.json()) as { error?: unknown }
    if (typeof body.error === 'string' && body.error.trim()) return body.error
  } catch {
    // Keep the original fallback when the server did not send a JSON error body.
  }
  return fallback
}

const uiSessionFetch = createUiSessionFetch((input, init) => fetch(input, init))
export const initializeUiSession = uiSessionFetch.initialize
const apiFetch = uiSessionFetch.fetch

const COLLECTION_LIMITS = {
  // Backend exposes 10 built-ins plus at most 128 custom presets.
  commandPresets: 138,
  dispatches: 100,
  filesystemEntries: 512,
  marketplaceAgents: 2048,
  marketplaceCategories: 256,
  roleTemplates: 256,
  terminalRuns: 256,
  workers: 256,
  workspaces: 256,
} as const

const HOT_QUERY_TIMEOUT_MS = 10_000
const INTERACTIVE_QUERY_TIMEOUT_MS = 15_000
const MARKETPLACE_QUERY_TIMEOUT_MS = 30_000

export const listWorkspaces = async (signal?: AbortSignal): Promise<WorkspaceSummary[]> => {
  const response = await apiFetch(
    '/api/workspaces',
    signal ? { signal } : undefined,
    INTERACTIVE_QUERY_TIMEOUT_MS
  )

  if (!response.ok) {
    throw new Error('Failed to load workspaces')
  }

  return requireBoundedList<WorkspaceSummary>(
    await response.json(),
    'workspaces',
    COLLECTION_LIMITS.workspaces
  )
}

export interface VersionInfo {
  currentVersion: string
  installHint: string
  latestVersion: string
  packageName: string
  releaseUrl: string
  updateAvailable: boolean
}

interface VersionInfoPayload {
  current_version: string
  install_hint: string
  latest_version: string
  package_name: string
  release_url: string
  update_available: boolean
}

export const getVersionInfo = async (): Promise<VersionInfo> => {
  const response = await apiFetch('/api/version', undefined, HOT_QUERY_TIMEOUT_MS)

  if (!response.ok) {
    throw new Error('Failed to load version info')
  }

  const payload = (await response.json()) as VersionInfoPayload
  return {
    currentVersion: payload.current_version,
    installHint: payload.install_hint,
    latestVersion: payload.latest_version,
    packageName: payload.package_name,
    releaseUrl: payload.release_url,
    updateAvailable: payload.update_available,
  }
}

export interface OrchestratorStartResult {
  ok: boolean
  error: string | null
  run_id: string | null
}

export interface CommandPreset {
  args: string[]
  available: boolean
  command: string
  displayName: string
  id: string
  modelPicker: {
    allowCustom: boolean
    suggestedModels: string[]
    supported: boolean
  }
  revision: number
}

export type AgentLaunchInput =
  | { type: 'inherit_orchestrator'; expected_source_revision?: number }
  | { type: 'preset'; preset_id: string; model_id?: string; expected_preset_revision?: number }
  | { type: 'startup'; startup_command: string; recovery_preset_id?: string }

export interface RoleTemplate {
  description: string
  id: string
  isBuiltin: boolean
  name: string
  roleType: WorkerRole | 'orchestrator'
}

interface RoleTemplateInput {
  description: string
  name: string
  roleType: WorkerRole | 'orchestrator'
}

interface CommandPresetPayload {
  args: string[]
  available: boolean
  command: string
  display_name: string
  id: string
  model_picker: {
    allow_custom: boolean
    suggested_models: string[]
    supported: boolean
  }
  revision: number
}

interface AgentLaunchOptionsPayload {
  orchestrator: { preset_id: string | null; model_id: string | null; revision: number; inheritable: boolean } | null
  presets: CommandPresetPayload[]
}

export interface AgentLaunchOptions {
  orchestrator: { presetId: string | null; modelId: string | null; revision: number; inheritable: boolean } | null
  presets: CommandPreset[]
}

interface RoleTemplatePayload {
  description: string
  id: string
  is_builtin: boolean
  name: string
  role_type: WorkerRole | 'orchestrator'
}

const fromRoleTemplatePayload = (payload: RoleTemplatePayload): RoleTemplate => ({
  description: payload.description,
  id: payload.id,
  isBuiltin: payload.is_builtin,
  name: payload.name,
  roleType: payload.role_type,
})

const toRoleTemplateBody = (input: RoleTemplateInput) => ({
  name: input.name,
  role_type: input.roleType,
  description: input.description,
  default_command: '',
  default_args: [],
  default_env: {},
})

interface AgentStartResult {
  error: string | null
  ok: boolean
  runId: string | null
}

interface AgentStartPayload {
  error: string | null
  ok: boolean
  run_id: string | null
}

interface CreateWorkerResult {
  agentStart: AgentStartResult
  worker: TeamListItem
}

type CreateWorkerPayload = TeamListItemPayload & { agent_start?: AgentStartPayload }

interface AppliedScenarioWorker {
  id: string
  name: string
  role: WorkerRole
  start: {
    ok: boolean
    run_id: string | null
  }
}

interface AppliedTeamScenarioResult {
  createdWorkers: AppliedScenarioWorker[]
  injected: boolean
}

interface AppliedTeamScenarioPayload {
  created_workers: AppliedScenarioWorker[]
  injected: boolean
}

export interface CreateWorkspaceResponse extends WorkspaceSummary {
  /** Derived from the HTTP status; not part of the public JSON payload. */
  created: boolean
  orchestrator_start: OrchestratorStartResult
}

export const createWorkspace = async (input: {
  registration_id: string
  name: string
  path: string
  autostart_orchestrator?: boolean
  command_preset_id?: string | null
  startup_command?: string | null
  launch?: AgentLaunchInput
}): Promise<CreateWorkspaceResponse> => {
  const response = await apiFetch('/api/workspaces', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(input),
  })

  if (!response.ok) {
    throw await readApiRequestError(response, 'Failed to create workspace')
  }

  const payload = (await response.json()) as Omit<CreateWorkspaceResponse, 'created'>
  return { ...payload, created: response.status === 201 }
}

export const deleteWorkspace = async (workspaceId: string): Promise<void> => {
  const response = await apiFetch(`/api/workspaces/${workspaceId}`, { method: 'DELETE' })

  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to delete workspace'))
  }
}

export const startAgentRun = async (
  workspaceId: string,
  agentId: string
): Promise<{ runId: string }> => {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/agents/${agentId}/start`, {
    method: 'POST',
  })
  if (!response.ok) {
    throw await readApiRequestError(response, 'Failed to start agent run')
  }
  const body = (await response.json()) as { run_id: string }
  return { runId: body.run_id }
}

export const stopAgentRun = async (runId: string): Promise<void> => {
  const response = await apiFetch(`/api/runtime/runs/${runId}/stop`, {
    method: 'POST',
  })
  if (!response.ok) {
    throw new Error('Failed to stop agent run')
  }
}

export const getActiveWorkspaceId = async (signal?: AbortSignal): Promise<string | null> => {
  const response = await apiFetch(
    '/api/settings/app-state/active_workspace_id',
    signal ? { signal } : undefined,
    INTERACTIVE_QUERY_TIMEOUT_MS
  )

  if (!response.ok) {
    throw new Error('Failed to load active workspace')
  }

  const payload = (await response.json()) as { key: string; value: string | null }
  return payload.value
}

export const saveActiveWorkspaceId = async (workspaceId: string | null): Promise<void> => {
  const response = await apiFetch('/api/settings/app-state/active_workspace_id', {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ value: workspaceId }),
  })

  if (!response.ok) {
    throw new Error('Failed to save active workspace')
  }
}

export const listWorkers = async (
  workspaceId: string,
  signal?: AbortSignal
): Promise<TeamListItem[]> => {
  const response = await apiFetch(`/api/ui/workspaces/${workspaceId}/team`, {
    mode: 'same-origin',
    ...(signal ? { signal } : {}),
  }, HOT_QUERY_TIMEOUT_MS)

  if (!response.ok) {
    throw new Error('Failed to load workers')
  }

  const payload = requireBoundedList<TeamListItemPayload>(
    await response.json(),
    'workers',
    COLLECTION_LIMITS.workers
  )
  return payload.map(fromPayload)
}

type DispatchDeliveryState =
  | 'pending'
  | 'delivering'
  | 'retry_wait'
  | 'submitted'
  | 'uncertain'
  | 'failed'
  | 'closed'

export interface DispatchSummary {
  id: string
  toAgentId: string
  text: string
  state: 'queued' | 'submitted' | 'reported' | 'cancelled'
  deliveryState: DispatchDeliveryState | null
  deliveryAttemptCount: number
  deliveryError: string | null
  deliveryNextAttemptAt: number | null
  deliveryInputAttempted: boolean
}

interface DispatchSummaryPayload {
  id: string
  to_agent_id: string
  text: string
  state: DispatchSummary['state']
  delivery_state: DispatchDeliveryState | null
  delivery_attempt_count: number
  delivery_error: string | null
  delivery_next_attempt_at: number | null
  delivery_input_attempted: boolean
}

export const listDispatchDeliveryIssues = async (
  workspaceId: string,
  signal?: AbortSignal
): Promise<DispatchSummary[]> => {
  const response = await apiFetch(
    `/api/ui/workspaces/${encodeURIComponent(workspaceId)}/dispatch-delivery-issues?limit=${COLLECTION_LIMITS.dispatches}`,
    signal ? { signal } : undefined,
    HOT_QUERY_TIMEOUT_MS
  )
  if (!response.ok) throw new Error('Failed to load dispatch delivery status')
  return requireBoundedList<DispatchSummaryPayload>(
    await response.json(),
    'dispatches',
    COLLECTION_LIMITS.dispatches
  ).map((item) => ({
    id: item.id,
    toAgentId: item.to_agent_id,
    text: item.text,
    state: item.state,
    deliveryState: item.delivery_state,
    deliveryAttemptCount: item.delivery_attempt_count,
    deliveryError: item.delivery_error,
    deliveryNextAttemptAt: item.delivery_next_attempt_at,
    deliveryInputAttempted: item.delivery_input_attempted,
  }))
}

export const retryDispatchDelivery = async (
  workspaceId: string,
  dispatchId: string
): Promise<void> => {
  const response = await apiFetch(
    `/api/ui/workspaces/${encodeURIComponent(workspaceId)}/dispatches/${encodeURIComponent(dispatchId)}/retry`,
    { method: 'POST' },
    INTERACTIVE_QUERY_TIMEOUT_MS
  )
  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to retry dispatch delivery'))
  }
}

export const listCommandPresets = async (): Promise<CommandPreset[]> => {
  const response = await apiFetch(
    '/api/ui/settings/command-presets',
    undefined,
    INTERACTIVE_QUERY_TIMEOUT_MS
  )

  if (!response.ok) {
    throw new Error('Failed to load command presets')
  }

  return requireBoundedList<CommandPresetPayload>(
    await response.json(),
    'command presets',
    COLLECTION_LIMITS.commandPresets
  ).map(mapCommandPreset)
}

const mapCommandPreset = (preset: CommandPresetPayload): CommandPreset => ({
  args: preset.args ?? [],
  available: preset.available,
  command: preset.command ?? '',
  displayName: preset.display_name,
  id: preset.id,
  modelPicker: {
    allowCustom: preset.model_picker.allow_custom,
    suggestedModels: preset.model_picker.suggested_models,
    supported: preset.model_picker.supported,
  },
  revision: preset.revision,
})

export const getWorkerLaunchOptions = async (workspaceId: string): Promise<AgentLaunchOptions> => {
  const response = await apiFetch(
    `/api/ui/workspaces/${encodeURIComponent(workspaceId)}/agent-launch-options`,
    undefined,
    INTERACTIVE_QUERY_TIMEOUT_MS
  )
  if (!response.ok) throw new Error('Failed to load agent launch options')
  const payload = (await response.json()) as AgentLaunchOptionsPayload
  return {
    orchestrator: payload.orchestrator
      ? {
          presetId: payload.orchestrator.preset_id,
          modelId: payload.orchestrator.model_id,
          revision: payload.orchestrator.revision,
          inheritable: payload.orchestrator.inheritable,
        }
      : null,
    presets: requireBoundedList<CommandPresetPayload>(
      payload.presets,
      'command presets',
      COLLECTION_LIMITS.commandPresets
    ).map(mapCommandPreset),
  }
}

export type TerminalInputProfile = 'default' | 'opencode'

export interface TerminalRunSummary {
  agent_id: string
  agent_name: string
  run_id: string
  status: string
  terminal_input_profile?: TerminalInputProfile
}

const workspaceShellAgentId = (workspaceId: string): string => `${workspaceId}:shell`

export const isWorkspaceShellRun = (run: TerminalRunSummary, workspaceId: string): boolean =>
  run.agent_id === workspaceShellAgentId(workspaceId)

export const startWorkspaceShell = async (workspaceId: string): Promise<TerminalRunSummary> => {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/shell/start`, {
    method: 'POST',
  })

  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to start workspace terminal'))
  }

  return (await response.json()) as TerminalRunSummary
}

export const closeWorkspaceShell = async (workspaceId: string, runId: string): Promise<void> => {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/shell/${runId}`, {
    method: 'DELETE',
  })

  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to close workspace terminal'))
  }
}

export const listRoleTemplates = async (): Promise<RoleTemplate[]> => {
  const response = await apiFetch('/api/ui/settings/role-templates', {
    mode: 'same-origin',
  }, INTERACTIVE_QUERY_TIMEOUT_MS)

  if (!response.ok) {
    throw new Error('Failed to load role templates')
  }

  const payload = requireBoundedList<RoleTemplatePayload>(
    await response.json(),
    'role templates',
    COLLECTION_LIMITS.roleTemplates
  )
  return payload.map(fromRoleTemplatePayload)
}

export const createRoleTemplate = async (input: RoleTemplateInput): Promise<RoleTemplate> => {
  const response = await apiFetch('/api/settings/role-templates', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(toRoleTemplateBody(input)),
  })

  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to create role template'))
  }

  return fromRoleTemplatePayload((await response.json()) as RoleTemplatePayload)
}

export const deleteRoleTemplate = async (templateId: string): Promise<void> => {
  const response = await apiFetch(`/api/settings/role-templates/${templateId}`, {
    method: 'DELETE',
  })

  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to delete role template'))
  }
}

export type MarketplaceLanguage = 'en' | 'zh'

export interface MarketplaceAgentEntry {
  path: string
  category: string
  name: string
  displayName?: string
  nameOverflows?: boolean
  description: string
  emoji: string | null
  color: string | null
  vibe: string | null
}

export interface MarketplaceManifest {
  source: {
    repo: string
    commit: string
    fetched_at: string
  }
  language: MarketplaceLanguage
  categories: string[]
  agents: MarketplaceAgentEntry[]
}

export interface MarketplaceAgentDetail {
  path: string
  frontmatter: Record<string, unknown>
  body: string
}

export const fetchMarketplaceManifest = async (
  lang: MarketplaceLanguage
): Promise<MarketplaceManifest> => {
  const response = await apiFetch(`/api/marketplace/manifest?lang=${lang}`, {
    mode: 'same-origin',
  }, MARKETPLACE_QUERY_TIMEOUT_MS)
  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to load marketplace manifest'))
  }
  const payload = (await response.json()) as MarketplaceManifest
  return {
    ...payload,
    agents: requireBoundedList<MarketplaceAgentEntry>(
      payload.agents,
      'marketplace agents',
      COLLECTION_LIMITS.marketplaceAgents
    ),
    categories: requireBoundedList<string>(
      payload.categories,
      'marketplace categories',
      COLLECTION_LIMITS.marketplaceCategories
    ),
  }
}

export const fetchMarketplaceAgent = async (
  lang: MarketplaceLanguage,
  path: string
): Promise<MarketplaceAgentDetail> => {
  const response = await apiFetch(
    `/api/marketplace/agent?lang=${lang}&path=${encodeURIComponent(path)}`,
    { mode: 'same-origin' },
    MARKETPLACE_QUERY_TIMEOUT_MS
  )
  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to load marketplace agent'))
  }
  return (await response.json()) as MarketplaceAgentDetail
}

export const listTerminalRuns = async (
  workspaceId: string,
  signal?: AbortSignal
): Promise<TerminalRunSummary[]> => {
  const response = await apiFetch(`/api/ui/workspaces/${workspaceId}/runs`, {
    mode: 'same-origin',
    ...(signal ? { signal } : {}),
  }, HOT_QUERY_TIMEOUT_MS)

  if (!response.ok) {
    throw new Error('Failed to load terminal runs')
  }

  return requireBoundedList<TerminalRunSummary>(
    await response.json(),
    'terminal runs',
    COLLECTION_LIMITS.terminalRuns
  ).map(
    ({ agent_id, agent_name, run_id, status, terminal_input_profile }) => ({
      agent_id,
      agent_name,
      run_id,
      status,
      terminal_input_profile: terminal_input_profile ?? 'default',
    })
  )
}

export const createWorker = async (
  workspaceId: string,
  input: Pick<AgentSummary, 'name'> & {
    autostart?: boolean
    command_preset_id?: string | null
    description?: string
    role: WorkerRole
    startup_command?: string | null
    launch?: AgentLaunchInput
  }
): Promise<CreateWorkerResult> => {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/workers`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(input),
  })

  if (!response.ok) {
    throw await readApiRequestError(response, 'Failed to create worker')
  }

  const payload = (await response.json()) as CreateWorkerPayload
  return {
    agentStart: {
      error: payload.agent_start?.error ?? null,
      ok: payload.agent_start?.ok ?? false,
      runId: payload.agent_start?.run_id ?? null,
    },
    worker: fromPayload(payload),
  }
}

export const applyTeamScenario = async (
  workspaceId: string,
  scenarioId: string,
  goal: string,
  locale: 'en' | 'zh'
): Promise<AppliedTeamScenarioResult> => {
  const response = await apiFetch(
    `/api/workspaces/${workspaceId}/scenarios/${encodeURIComponent(scenarioId)}/apply`,
    {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ goal, locale }),
    }
  )

  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to assemble the scenario team'))
  }

  const payload = (await response.json()) as AppliedTeamScenarioPayload
  return {
    createdWorkers: payload.created_workers,
    injected: payload.injected,
  }
}

export const deleteWorker = async (workspaceId: string, workerId: string): Promise<void> => {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/workers/${workerId}`, {
    method: 'DELETE',
  })

  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to delete worker'))
  }
}

export const renameWorker = async (
  workspaceId: string,
  workerId: string,
  name: string
): Promise<void> => {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/workers/${workerId}`, {
    body: JSON.stringify({ name }),
    headers: { 'content-type': 'application/json' },
    method: 'PATCH',
  })

  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to rename worker'))
  }
}

export interface TasksSnapshot {
  content: string
  revision?: string
}

interface TasksConflictPayload {
  content?: unknown
  error?: unknown
  error_code?: unknown
  revision?: unknown
}

const requireBoundedTasksContent = (content: string): string => {
  if (!tasksContentFitsTransport(content)) {
    throw new Error(`Tasks content exceeds ${MAX_TASKS_TRANSPORT_CONTENT_BYTES} transport bytes`)
  }
  return content
}

export class TasksRevisionConflictError extends Error {
  readonly snapshot: TasksSnapshot | null

  constructor(message: string, snapshot: TasksSnapshot | null) {
    super(message)
    this.name = 'TasksRevisionConflictError'
    this.snapshot = snapshot
  }
}

const toTasksSnapshot = (payload: { content: unknown; revision?: unknown }): TasksSnapshot => {
  if (typeof payload.content !== 'string') throw new Error('Invalid tasks response')
  return {
    content: requireBoundedTasksContent(payload.content),
    ...(typeof payload.revision === 'string' ? { revision: payload.revision } : {}),
  }
}

export const getWorkspaceTasks = async (
  workspaceId: string,
  signal?: AbortSignal
): Promise<TasksSnapshot> => {
  const response = await apiFetch(
    `/api/workspaces/${workspaceId}/tasks`,
    signal ? { signal } : undefined,
    INTERACTIVE_QUERY_TIMEOUT_MS
  )

  if (!response.ok) {
    throw new Error('Failed to load tasks')
  }

  return toTasksSnapshot((await response.json()) as { content: unknown; revision?: unknown })
}

export const saveWorkspaceTasks = async (
  workspaceId: string,
  input: { content: string; revision?: string }
): Promise<TasksSnapshot> => {
  requireBoundedTasksContent(input.content)
  const response = await apiFetch(`/api/workspaces/${workspaceId}/tasks`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(input),
  })

  if (response.status === 409) {
    const payload = (await response.json()) as TasksConflictPayload
    const snapshot =
      typeof payload.content === 'string'
        ? toTasksSnapshot({ content: payload.content, revision: payload.revision })
        : null
    throw new TasksRevisionConflictError(
      typeof payload.error === 'string' ? payload.error : 'Tasks changed on disk',
      snapshot
    )
  }
  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to save tasks'))
  }

  return toTasksSnapshot((await response.json()) as { content: unknown; revision?: unknown })
}

export interface FsBrowseEntryPayload {
  is_dir: true
  is_git_repository: boolean
  name: string
  path: string
}

export interface FsBrowseResponse {
  current_path: string
  entries: FsBrowseEntryPayload[]
  error: string | null
  ok: boolean
  parent_path: string | null
  root_path: string
  truncated: boolean
}

export interface FsProbeResponse {
  current_branch: string | null
  exists: boolean
  is_dir: boolean
  is_git_repository: boolean
  ok: boolean
  path: string
  suggested_name: string
}

export const browseFs = async (path: string, signal?: AbortSignal): Promise<FsBrowseResponse> => {
  const query = path ? `?path=${encodeURIComponent(path)}` : ''
  const response = await apiFetch(
    `/api/fs/browse${query}`,
    { mode: 'same-origin', ...(signal ? { signal } : {}) },
    INTERACTIVE_QUERY_TIMEOUT_MS
  )
  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to browse server filesystem'))
  }
  const body = (await response.json()) as FsBrowseResponse
  return {
    ...body,
    entries: requireBoundedList<FsBrowseEntryPayload>(
      body.entries,
      'filesystem entries',
      COLLECTION_LIMITS.filesystemEntries
    ),
  }
}

export const probeFs = async (path: string, signal?: AbortSignal): Promise<FsProbeResponse> => {
  const response = await apiFetch(`/api/fs/probe?path=${encodeURIComponent(path)}`, {
    mode: 'same-origin',
    ...(signal ? { signal } : {}),
  }, INTERACTIVE_QUERY_TIMEOUT_MS)
  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to inspect server path'))
  }
  return (await response.json()) as FsProbeResponse
}

export interface PickFolderResponse {
  canceled: boolean
  error: string | null
  path: string | null
  probe: FsProbeResponse | null
  supported: boolean
}

const nativeFolderPicker = createSingleFlight(async (): Promise<PickFolderResponse> => {
  const response = await apiFetch('/api/fs/pick-folder', {
    method: 'POST',
    mode: 'same-origin',
  }, 0)
  if (!response.ok) {
    throw new Error(await readErrorMessage(response, 'Failed to open folder picker'))
  }
  return (await response.json()) as PickFolderResponse
})

/**
 * The operating-system picker is a process-wide singleton. React StrictMode
 * may replay the effect that asks for it, so every concurrent caller must
 * share the same HTTP request until the native dialog settles.
 */
export const pickFolder = (): Promise<PickFolderResponse> => nativeFolderPicker.run()

export type OpenWorkspaceResult =
  | { ok: true; effectiveTargetId: OpenTargetId }
  | { ok: false; effectiveTargetId: OpenTargetId; errorCode: OpenWorkspaceErrorCode }

interface OpenWorkspaceSuccessPayload {
  ok: true
  effective_target_id: OpenTargetId
}

interface OpenWorkspaceFailurePayload {
  ok: false
  effective_target_id: OpenTargetId
  error_code: OpenWorkspaceErrorCode
}

export const openWorkspaceInEditor = async (
  workspaceId: string,
  targetId: OpenTargetId
): Promise<OpenWorkspaceResult> => {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/open`, {
    body: JSON.stringify({ target_id: targetId }),
    headers: { 'content-type': 'application/json' },
    method: 'POST',
  })

  // 200 success and 502 service failure both return structured JSON we can
  // surface; only true transport / 4xx failures (workspace gone, target id
  // tampered) throw.
  if (response.status === 200) {
    const body = (await response.json()) as OpenWorkspaceSuccessPayload
    return { ok: true, effectiveTargetId: body.effective_target_id }
  }
  if (response.status === 502) {
    const body = (await response.json()) as OpenWorkspaceFailurePayload
    return {
      ok: false,
      effectiveTargetId: body.effective_target_id,
      errorCode: body.error_code,
    }
  }
  throw new Error(await readErrorMessage(response, 'Failed to open workspace'))
}
