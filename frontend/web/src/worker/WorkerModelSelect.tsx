import type { ModelSelectionMode } from '../launch/AgentModelSelect.js'
import { useI18n } from '../i18n.js'

type WorkerModelSelectProps = {
  disabled?: boolean
  modelId: string
  models: string[]
  onChange: (mode: ModelSelectionMode, modelId: string) => void
}

export const WorkerModelSelect = ({
  disabled = false,
  modelId,
  models,
  onChange,
}: WorkerModelSelectProps) => {
  const { language } = useI18n()
  const defaultLabel = language === 'zh' ? '使用 CLI 默认模型' : 'Use CLI default model'

  return (
    <div className="flex flex-col gap-2" data-testid="worker-model-select">
      <span className="text-xs font-medium uppercase tracking-wider text-ter">
        {language === 'zh' ? '模型' : 'Model'}
      </span>
      {models.length > 0 ? (
        <select
          aria-label={language === 'zh' ? '模型' : 'Model'}
          className="input mono"
          data-testid="worker-model-picker"
          disabled={disabled}
          onChange={(event) => {
            const value = event.currentTarget.value
            onChange(value ? 'explicit' : 'default', value)
          }}
          value={modelId}
        >
          <option value="">{defaultLabel}</option>
          {models.map((model) => <option key={model} value={model}>{model}</option>)}
        </select>
      ) : (
        <div className="input flex items-center text-ter" data-testid="worker-model-default">
          {defaultLabel}
        </div>
      )}
      {disabled ? (
        <span className="text-xs text-ter">
          {language === 'zh' ? '自定义启动命令启用时，请在命令中指定模型。' : 'Specify the model in the custom startup command.'}
        </span>
      ) : null}
    </div>
  )
}
