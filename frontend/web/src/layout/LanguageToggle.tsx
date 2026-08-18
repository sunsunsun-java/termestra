import { Languages } from 'lucide-react'

import { useI18n } from '../i18n.js'
import { Tooltip } from '../ui/Tooltip.js'
import type { UiLanguage } from '../uiLanguage.js'

const LANGUAGE_ACTION: Record<
  UiLanguage,
  { next: UiLanguage; currentKey: 'language.currentEn' | 'language.currentZh'; actionKey: 'language.switchToEn' | 'language.switchToZh' }
> = {
  en: {
    actionKey: 'language.switchToZh',
    currentKey: 'language.currentEn',
    next: 'zh',
  },
  zh: {
    actionKey: 'language.switchToEn',
    currentKey: 'language.currentZh',
    next: 'en',
  },
}

export const LanguageToggle = () => {
  const { language, setLanguage, t } = useI18n()
  const action = LANGUAGE_ACTION[language]
  const actionLabel = t(action.actionKey)

  return (
    <Tooltip label={actionLabel}>
      <span>
        <button
          type="button"
          aria-label={actionLabel}
          className="flex h-7 items-center gap-1 rounded border px-2 text-xs font-medium text-ter transition-colors hover:bg-3 hover:text-pri focus-visible:outline focus-visible:outline-1 focus-visible:outline-offset-2 focus-visible:outline-[var(--accent)]"
          onClick={() => setLanguage(action.next)}
          style={{ borderColor: 'var(--border)', background: 'var(--bg-1)' }}
        >
          <Languages size={13} aria-hidden />
          <span>{t(action.currentKey)}</span>
        </button>
      </span>
    </Tooltip>
  )
}
