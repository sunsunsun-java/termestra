import { ApiRequestError } from '../lib/api-request-error.js'
import type { UiLanguage } from '../uiLanguage.js'

const BUSY_MESSAGE = {
  en: 'This workspace is temporarily busy. Try starting the Orchestrator again in about 1 second.',
  zh: '此 Workspace 暂时繁忙，请约 1 秒后再次启动 Orchestrator。',
} satisfies Record<UiLanguage, string>

const TIMEOUT_MESSAGE = {
  en: 'The start request timed out, so its result is unknown. Refresh to confirm the Orchestrator status before deciding whether to try again.',
  zh: '启动请求已超时，结果可能未知。请先刷新确认 Orchestrator 状态，再决定是否重试。',
} satisfies Record<UiLanguage, string>

const FALLBACK_MESSAGE = {
  en: 'Failed to start the Orchestrator.',
  zh: 'Orchestrator 启动失败。',
} satisfies Record<UiLanguage, string>

const isTimeout = (error: unknown): boolean =>
  error instanceof DOMException && error.name === 'TimeoutError'

/** Maps runtime start failures to actionable, localized product guidance. */
export const presentAgentStartError = (error: unknown, language: UiLanguage): string => {
  if (error instanceof ApiRequestError && error.code === 'RUNTIME_OPERATION_BUSY') {
    return BUSY_MESSAGE[language]
  }
  if (isTimeout(error)) return TIMEOUT_MESSAGE[language]
  return error instanceof Error && error.message.trim()
    ? error.message
    : FALLBACK_MESSAGE[language]
}
