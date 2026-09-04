import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'

import type { TeamListItem, WorkerRole } from '../../../src/shared/types.js'
import {
  type AgentLaunchOptions,
  ApiRequestError,
  type CommandPreset,
  createRoleTemplate,
  deleteRoleTemplate,
  getWorkerLaunchOptions,
  getWorkerModels,
  listRoleTemplates,
  type RoleTemplate,
} from '../api.js'
import type { ModelSelectionMode } from '../launch/AgentModelSelect.js'
import { useI18n } from '../i18n.js'
import type { UiLanguage } from '../uiLanguage.js'
import { generateWorkerName } from './randomWorkerName.js'
import type { CreateWorkerActionInput, WorkerActions } from './useWorkerActions.js'

interface UseWorkerComposerInput {
  createWorker: WorkerActions['createWorker']
  open: boolean
  scopeKey: string
  workers: TeamListItem[]
}

type ModelSelection = { mode: ModelSelectionMode; modelId: string }
const DEFAULT_MODEL_SELECTION: ModelSelection = { mode: 'default', modelId: '' }

interface WorkerComposerState {
  availableModels: string[]
  commandPresets: CommandPreset[]
  commandPresetId: string
  modelId: string
  modelMode: ModelSelectionMode
  createWorkerError: string | null
  creating: boolean
  customTemplates: RoleTemplate[]
  roleDescription: string
  roleDescriptionDefault: string
  selectedTemplateId: string | null
  startupCommand: string
  templateBusy: boolean
  templateError: string | null
  workerName: string
  workerRole: WorkerRole
  setCommandPresetId: (value: string) => void
  setModelSelection: (mode: ModelSelectionMode, modelId: string) => void
  setRoleDescription: (value: string) => void
  setStartupCommand: (value: string) => void
  setWorkerName: (value: string) => void
  setWorkerRole: (value: WorkerRole) => void
  selectTemplate: (templateId: string | null) => void
  saveAsTemplate: (name: string) => Promise<void>
  deleteTemplate: (templateId: string) => Promise<void>
  randomizeWorkerName: () => void
  resetRoleDescription: () => void
  resetError: () => void
  applyMarketplaceImport: (input: { name: string; description: string }) => void
  submit: (event: FormEvent<HTMLFormElement>, onSuccess: () => void) => void
}

const fallbackRoleDescriptions: Record<UiLanguage, Record<WorkerRole, string>> = {
  en: {
    coder: [
      'You are a Coder. Turn clearly scoped tasks into the smallest correct code change.',
      'Working style:',
      '- Read the relevant files and local patterns before editing.',
      '- Prefer small changes; avoid unrelated refactors and scope creep.',
      '- Run validation that covers the risk. If you cannot validate, explain why.',
      'Report changed files, verification, remaining risk, and blockers.',
    ].join('\n'),
    custom: [
      "You are a custom team member. Rewrite this into the member's operating contract.",
      'Recommended shape:',
      '- Goal: what this member owns.',
      '- Boundaries: what to do and what to avoid.',
      '- Working style: how to inspect, edit, verify, or review.',
      '- Done means: what results, risks, and blockers to report.',
    ].join('\n'),
    reviewer: [
      'You are a Reviewer. Focus on quality review; do not replace the Orchestrator or edit by default.',
      'Working style:',
      '- Prioritize real bugs, regressions, edge cases, and test gaps.',
      '- For each issue, include severity, file/line, trigger condition, and minimal fix.',
      '- If no high-risk issue exists, state residual risk and unverified scope.',
      'Report blocking issues first, ordered by severity.',
    ].join('\n'),
    tester: [
      'You are a Tester. Reproduce, test, and produce concrete verification evidence.',
      'Working style:',
      '- Clarify the behavior, entry point, and failure condition under test.',
      '- Prefer real commands or real paths. Add a minimal test when useful.',
      '- Record commands, results, key output, and uncovered scenarios.',
      'Report pass/fail/unverified separately, then suggest the next step.',
    ].join('\n'),
  },
  zh: {
    coder: [
      '你是实现型 Coder，负责把明确任务落成最小正确代码改动。',
      '工作方式：',
      '- 先阅读相关文件和现有模式，再动手。',
      '- 优先小步修改，避免无关重构和范围扩张。',
      '- 改动后运行能覆盖风险的验证命令；不能验证时说明原因。',
      '交付说明要包含：改动文件、验证结果、剩余风险或阻塞。',
    ].join('\n'),
    custom: [
      '你是自定义成员。请把这段改成该成员的行为契约。',
      '建议包含：',
      '- 目标：这个成员主要负责什么。',
      '- 边界：哪些事可以做，哪些事不要做。',
      '- 工作方式：如何调查、修改、验证或审查。',
      '- 完成标准：交付时需要说明哪些结果、风险和阻塞。',
    ].join('\n'),
    reviewer: [
      '你是监工型 Reviewer，负责质量审查，不替代 Orchestrator，也不默认改代码。',
      '工作方式：',
      '- 优先找真实 bug、回归风险、边界条件和测试缺口。',
      '- 发现问题时给出严重度、文件/行号、触发条件和最小修复建议。',
      '- 没有高风险问题时明确说清剩余风险和未验证范围。',
      '交付说明按严重度排序，先列 blocking 问题。',
    ].join('\n'),
    tester: [
      '你是验证型 Tester，负责复现、测试和证据化验证。',
      '工作方式：',
      '- 先明确要验证的行为、入口和失败条件。',
      '- 优先跑真实命令或真实链路；必要时补充最小测试。',
      '- 记录命令、结果、关键输出和不能覆盖的场景。',
      '交付说明要区分通过、失败、未验证和建议下一步。',
    ].join('\n'),
  },
}

const getDefaultDescription = (
  role: WorkerRole,
  roleTemplates: RoleTemplate[],
  language: UiLanguage
) =>
  language === 'zh'
    ? (roleTemplates.find((template) => template.roleType === role)?.description ??
      fallbackRoleDescriptions.zh[role])
    : fallbackRoleDescriptions.en[role]

export const useWorkerComposer = ({
  createWorker,
  open,
  scopeKey,
  workers,
}: UseWorkerComposerInput): WorkerComposerState => {
  const { language } = useI18n()
  const [workerName, setWorkerName] = useState('')
  const [workerRole, setWorkerRole] = useState<WorkerRole>('coder')
  const [roleTemplates, setRoleTemplates] = useState<RoleTemplate[]>([])
  const [selectedTemplateId, setSelectedTemplateId] = useState<string | null>(null)
  const [templateBusy, setTemplateBusy] = useState(false)
  const templateBusyRef = useRef(false)
  const [templateError, setTemplateError] = useState<string | null>(null)
  const [roleDescription, setRoleDescriptionState] = useState(
    fallbackRoleDescriptions[language].coder
  )
  const [commandPresets, setCommandPresets] = useState<CommandPreset[]>([])
  const [commandPresetId, setCommandPresetId] = useState('claude')
  const [availableModels, setAvailableModels] = useState<string[]>([])
  const [modelSelection, setModelSelection] = useState<ModelSelection>(DEFAULT_MODEL_SELECTION)
  const [startupCommand, setStartupCommand] = useState('')
  const [createWorkerError, setCreateWorkerError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const createWorkerRef = useRef(createWorker)
  createWorkerRef.current = createWorker
  const scopeRef = useRef(scopeKey)
  scopeRef.current = scopeKey
  const createInFlightByScopeRef = useRef(
    new Map<string, Promise<Awaited<ReturnType<WorkerActions['createWorker']>>>>()
  )
  const workerNameGeneratedRef = useRef(false)
  const roleDescriptionEditedRef = useRef(false)
  const roleDescriptionDefault = getDefaultDescription(workerRole, roleTemplates, language)
  const customTemplates = useMemo(
    () => roleTemplates.filter((template) => !template.isBuiltin),
    [roleTemplates]
  )

  const applyLaunchOptions = useCallback((options: AgentLaunchOptions) => {
    const presets = options.presets
    setCommandPresets(presets)
    const orchestratorPresetId =
      options.orchestrator?.inheritable === true && options.orchestrator.presetId
        ? options.orchestrator.presetId
        : null
    setModelSelection(DEFAULT_MODEL_SELECTION)
    if (orchestratorPresetId && presets.some((preset) => preset.id === orchestratorPresetId && preset.available)) {
      setCommandPresetId(orchestratorPresetId)
      return
    }
    setCommandPresetId((current) => {
      if (presets.some((preset) => preset.id === current && preset.available)) return current
      return (
        presets.find((preset) => preset.id === 'claude' && preset.available)?.id ??
        presets.find((preset) => preset.available)?.id ??
        presets[0]?.id ??
        ''
      )
    })
  }, [])

  useEffect(() => {
    setCreating(createInFlightByScopeRef.current.has(scopeKey))
    setCreateWorkerError(null)
  }, [scopeKey])

  useEffect(() => {
    if (!open) return
    let cancelled = false
    void getWorkerLaunchOptions(scopeKey)
      .then((options) => {
        if (cancelled) return
        applyLaunchOptions(options)
      })
      .catch((error) => {
        if (!cancelled) {
          setCreateWorkerError(error instanceof Error ? error.message : String(error))
        }
      })
    return () => {
      cancelled = true
    }
  }, [applyLaunchOptions, open, scopeKey])

  useEffect(() => {
    const selectedPreset = commandPresets.find(
      (preset) => preset.id === commandPresetId && preset.available
    )
    setAvailableModels([])
    if (!open) {
      setModelSelection(DEFAULT_MODEL_SELECTION)
      return
    }
    if (!scopeKey || !selectedPreset) return
    const controller = new AbortController()
    void getWorkerModels(scopeKey, selectedPreset.id, controller.signal)
      .then((models) => {
        if (!controller.signal.aborted) setAvailableModels(models)
      })
      .catch(() => {
        if (!controller.signal.aborted) setAvailableModels([])
      })
    return () => {
      controller.abort()
    }
  }, [commandPresetId, commandPresets, open, scopeKey])

  useEffect(() => {
    if (!open) return
    let cancelled = false
    void listRoleTemplates()
      .then((templates) => {
        if (cancelled) return
        setRoleTemplates(templates)
      })
      .catch((error) => {
        if (!cancelled) {
          setCreateWorkerError(error instanceof Error ? error.message : String(error))
        }
      })
    return () => {
      cancelled = true
    }
  }, [open])

  useEffect(() => {
    if (selectedTemplateId !== null) return
    if (!roleDescriptionEditedRef.current) {
      setRoleDescriptionState(getDefaultDescription(workerRole, roleTemplates, language))
    }
  }, [language, roleTemplates, workerRole, selectedTemplateId])

  const setRoleDescription = (value: string) => {
    roleDescriptionEditedRef.current = true
    setRoleDescriptionState(value)
  }

  const setWorkerNameFromUser = (value: string) => {
    workerNameGeneratedRef.current = false
    setWorkerName(value)
  }

  const usedNames = useMemo(() => new Set(workers.map((w) => w.name)), [workers])

  const randomizeWorkerName = () => {
    workerNameGeneratedRef.current = true
    setWorkerName(generateWorkerName({ language, role: workerRole, usedNames }))
  }

  useEffect(() => {
    if (workerNameGeneratedRef.current) {
      setWorkerName(generateWorkerName({ language, role: workerRole, usedNames }))
    }
  }, [language, workerRole, usedNames])

  const selectWorkerRole = (value: WorkerRole) => {
    setWorkerRole(value)
    setSelectedTemplateId(null)
    roleDescriptionEditedRef.current = false
    setRoleDescriptionState(getDefaultDescription(value, roleTemplates, language))
  }

  const selectTemplate = (templateId: string | null) => {
    if (templateId === null) {
      // Clear selection but stay on the Custom role with the blank default.
      setWorkerRole('custom')
      setSelectedTemplateId(null)
      roleDescriptionEditedRef.current = false
      setRoleDescriptionState(fallbackRoleDescriptions[language].custom)
      return
    }
    const template = roleTemplates.find((entry) => entry.id === templateId)
    if (!template || template.isBuiltin) return
    setWorkerRole('custom')
    setSelectedTemplateId(templateId)
    roleDescriptionEditedRef.current = false
    setRoleDescriptionState(template.description)
  }

  const saveAsTemplate = async (name: string) => {
    const trimmedName = name.trim()
    const trimmedDescription = roleDescription.trim()
    if (!trimmedName || !trimmedDescription || templateBusyRef.current) return
    templateBusyRef.current = true
    setTemplateBusy(true)
    setTemplateError(null)
    try {
      const created = await createRoleTemplate({
        name: trimmedName,
        roleType: 'custom',
        description: trimmedDescription,
      })
      setRoleTemplates((current) => [...current, created])
      setSelectedTemplateId(created.id)
      setWorkerRole('custom')
      roleDescriptionEditedRef.current = false
      setRoleDescriptionState(created.description)
    } catch (error) {
      setTemplateError(error instanceof Error ? error.message : String(error))
      throw error
    } finally {
      templateBusyRef.current = false
      setTemplateBusy(false)
    }
  }

  const deleteTemplate = async (templateId: string) => {
    const template = roleTemplates.find((entry) => entry.id === templateId)
    if (!template || template.isBuiltin || templateBusyRef.current) return
    templateBusyRef.current = true
    setTemplateBusy(true)
    setTemplateError(null)
    try {
      await deleteRoleTemplate(templateId)
      setRoleTemplates((current) => current.filter((entry) => entry.id !== templateId))
      if (selectedTemplateId === templateId) {
        setSelectedTemplateId(null)
        roleDescriptionEditedRef.current = false
        setRoleDescriptionState(fallbackRoleDescriptions[language].custom)
      }
    } catch (error) {
      setTemplateError(error instanceof Error ? error.message : String(error))
      throw error
    } finally {
      templateBusyRef.current = false
      setTemplateBusy(false)
    }
  }

  const resetRoleDescription = () => {
    roleDescriptionEditedRef.current = false
    setRoleDescriptionState(roleDescriptionDefault)
  }

  // Apply an imported marketplace template. Atomic against the form-state
  // racing surface — selectWorkerRole resets description + flips the edited
  // ref, and the role-change useEffect would clobber description on commit.
  // We sequence the raw setters and then forcibly mark the description as
  // user-edited so neither overwrites the imported value.
  const applyMarketplaceImport = ({ name, description }: { name: string; description: string }) => {
    workerNameGeneratedRef.current = false
    setWorkerName(name)
    setSelectedTemplateId(null)
    setWorkerRole('custom')
    roleDescriptionEditedRef.current = true
    setRoleDescriptionState(description)
  }

  const selectCommandPresetId = (value: string) => {
    setCommandPresetId(value)
    setAvailableModels([])
    setModelSelection(DEFAULT_MODEL_SELECTION)
  }

  const selectModel = (mode: ModelSelectionMode, modelId: string) => {
    setModelSelection({ mode, modelId })
  }

  const submit = (event: FormEvent<HTMLFormElement>, onSuccess: () => void) => {
    event.preventDefault()
    if (!scopeKey || createInFlightByScopeRef.current.has(scopeKey)) return
    const requestScope = scopeKey
    const execute = createWorkerRef.current
    const selectedPreset = commandPresets.find((preset) => preset.id === commandPresetId)
    const selectedModelId = modelSelection.modelId.trim()
    const explicitModelId = modelSelection.mode === 'explicit' && availableModels.includes(selectedModelId)
      ? selectedModelId
      : null
    const input: CreateWorkerActionInput = {
      commandPresetId,
      launch: startupCommand.trim()
        ? {
            type: 'startup',
            startup_command: startupCommand.trim(),
            ...(commandPresetId ? { recovery_preset_id: commandPresetId } : {}),
          }
        : {
            type: 'preset',
            preset_id: commandPresetId,
            ...(explicitModelId ? { model_id: explicitModelId } : {}),
            ...(selectedPreset?.revision === undefined
              ? {}
              : { expected_preset_revision: selectedPreset.revision }),
          },
      name: workerName,
      role: workerRole,
      roleDescription,
    }
    setCreating(true)
    setCreateWorkerError(null)
    const operation = Promise.resolve().then(() => execute(input))
    createInFlightByScopeRef.current.set(requestScope, operation)
    void operation
      .then(({ error }) => {
        if (scopeRef.current !== requestScope) return
        setWorkerName('')
        workerNameGeneratedRef.current = false
        selectWorkerRole('coder')
        setSelectedTemplateId(null)
        setCommandPresetId('claude')
        setModelSelection(DEFAULT_MODEL_SELECTION)
        setStartupCommand('')
        onSuccess()
        if (error) setCreateWorkerError(error)
      })
      .catch((error) => {
        if (scopeRef.current === requestScope) {
          setCreateWorkerError(error instanceof Error ? error.message : String(error))
          if (
            error instanceof ApiRequestError &&
            (error.code === 'COMMAND_PRESET_CHANGED' ||
              error.code === 'ORCHESTRATOR_LAUNCH_CHANGED')
          ) {
            void getWorkerLaunchOptions(requestScope)
              .then((options) => {
                if (scopeRef.current === requestScope) applyLaunchOptions(options)
              })
              .catch(() => {
                // Preserve the original conflict; the next dialog open retries the query.
              })
          }
        }
      })
      .finally(() => {
        if (createInFlightByScopeRef.current.get(requestScope) === operation) {
          createInFlightByScopeRef.current.delete(requestScope)
        }
        if (scopeRef.current === requestScope) setCreating(false)
      })
  }

  return {
    availableModels,
    commandPresets,
    commandPresetId,
    modelId: modelSelection.modelId,
    modelMode: modelSelection.mode,
    createWorkerError,
    creating,
    customTemplates,
    roleDescription,
    roleDescriptionDefault,
    selectedTemplateId,
    startupCommand,
    templateBusy,
    templateError,
    workerName,
    workerRole,
    setCommandPresetId: selectCommandPresetId,
    setModelSelection: selectModel,
    setRoleDescription,
    setStartupCommand,
    setWorkerName: setWorkerNameFromUser,
    setWorkerRole: selectWorkerRole,
    selectTemplate,
    saveAsTemplate,
    deleteTemplate,
    randomizeWorkerName,
    resetRoleDescription,
    resetError: () => setCreateWorkerError(null),
    applyMarketplaceImport,
    submit,
  }
}
