import { Sparkles } from 'lucide-react'

import { useI18n } from '../i18n.js'

interface DemoBannerProps {
  onExit: () => void
}

const bannerStyle = {
  background: 'var(--status-yellow-bg, #3a2c1c)',
  borderColor: 'var(--border)',
}

export const DemoBanner = ({ onExit }: DemoBannerProps) => {
  const { t } = useI18n()

  return (
    <section
      aria-label="Demo mode"
      className="flex shrink-0 items-center justify-between border-b px-4 py-2 text-xs"
      data-testid="demo-banner"
      style={bannerStyle}
    >
      <p className="flex items-center gap-2 text-pri">
        <Sparkles size={14} aria-hidden />
        <span>{t('demo.banner')}</span>
      </p>
      <button className="icon-btn icon-btn--ghost" type="button" onClick={onExit}>
        {t('demo.exit')}
      </button>
    </section>
  )
}
