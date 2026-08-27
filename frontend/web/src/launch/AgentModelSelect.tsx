import { useId } from 'react'

import type { CommandPreset } from '../api.js'
import { useI18n } from '../i18n.js'

export type ModelSelectionMode = 'inherit' | 'default' | 'explicit'

type AgentModelSelectProps = {
  disabled?: boolean
  inheritLabel?: string | null
  mode: ModelSelectionMode
  modelId: string
  onChange: (mode: ModelSelectionMode, modelId: string) => void
  preset: CommandPreset | undefined
}

export const AgentModelSelect = ({
  disabled = false,
  inheritLabel = null,
  mode,
  modelId,
  onChange,
  preset,
}: AgentModelSelectProps) => {
  const { language } = useI18n()
  const listId = useId()
  const zh = language === 'zh'
  const supported = preset?.modelPicker.supported === true
  const effectiveMode = !supported && mode === 'explicit' ? 'default' : mode

  return (
    <div className="flex flex-col gap-2" data-testid="agent-model-select">
      <label className="flex flex-col gap-2">
        <span className="text-xs font-medium uppercase tracking-wider text-ter">
          {zh ? '模型' : 'Model'}
        </span>
        <select
          className="input"
          disabled={disabled}
          value={effectiveMode}
          onChange={(event) => {
            const next = event.target.value as ModelSelectionMode
            onChange(next, next === 'explicit' ? (modelId || preset?.modelPicker.suggestedModels[0] || '') : '')
          }}
        >
          {inheritLabel ? <option value="inherit">{inheritLabel}</option> : null}
          <option value="default">{zh ? '使用 CLI 默认模型' : 'Use CLI default model'}</option>
          {supported ? <option value="explicit">{zh ? '指定模型' : 'Choose a model'}</option> : null}
        </select>
      </label>
      {effectiveMode === 'explicit' && supported ? (
        <label className="flex flex-col gap-2">
          {preset.modelPicker.allowCustom ? (
            <>
              <input
                aria-label={zh ? '模型 ID' : 'Model ID'}
                className="input mono"
                data-testid="agent-model-id"
                disabled={disabled}
                list={listId}
                maxLength={128}
                onChange={(event) => onChange('explicit', event.target.value)}
                placeholder={zh ? '输入模型 ID' : 'Enter model ID'}
                value={modelId}
              />
              <datalist id={listId}>
                {preset.modelPicker.suggestedModels.map((model) => <option key={model} value={model} />)}
              </datalist>
            </>
          ) : (
            <select
              aria-label={zh ? '模型 ID' : 'Model ID'}
              className="input mono"
              data-testid="agent-model-id"
              disabled={disabled}
              onChange={(event) => onChange('explicit', event.target.value)}
              value={modelId}
            >
              {preset.modelPicker.suggestedModels.map((model) => <option key={model} value={model}>{model}</option>)}
            </select>
          )}
        </label>
      ) : null}
      {!supported ? (
        <span className="text-xs text-ter">
          {zh ? '该 CLI 暂不支持结构化模型选择，将使用 CLI 默认模型。' : 'This CLI uses its default model.'}
        </span>
      ) : null}
      {disabled ? (
        <span className="text-xs text-ter">
          {zh ? '自定义启动命令启用时，请在命令中指定模型。' : 'Specify the model in the custom startup command.'}
        </span>
      ) : null}
    </div>
  )
}
