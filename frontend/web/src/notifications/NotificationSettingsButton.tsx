import { Bell, Check, Info, Play, Volume2, VolumeX } from 'lucide-react'
import type { RefObject } from 'react'
import { useCallback, useEffect, useRef, useState } from 'react'

import type { TranslationKey } from '../i18n.js'
import { useI18n } from '../i18n.js'
import { Tooltip } from '../ui/Tooltip.js'
import type {
  NotificationDetail,
  NotificationSettings,
  NotificationSound,
} from './NotificationProvider.js'
import { useNotifications } from './NotificationProvider.js'

interface SoundChoice {
  accent: string
  descriptionKey: TranslationKey
  labelKey: TranslationKey
  length: 'short' | 'long' | 'silent'
  value: NotificationSound
}

interface DetailChoice {
  descriptionKey: TranslationKey
  labelKey: TranslationKey
  value: NotificationDetail
}

const SOUND_CHOICES: readonly SoundChoice[] = [
  {
    accent: 'var(--status-green)',
    descriptionKey: 'notifications.sound.soft.description',
    labelKey: 'notifications.sound.soft.label',
    length: 'short',
    value: 'soft',
  },
  {
    accent: 'var(--status-blue)',
    descriptionKey: 'notifications.sound.ping.description',
    labelKey: 'notifications.sound.ping.label',
    length: 'short',
    value: 'ping',
  },
  {
    accent: 'var(--status-gold)',
    descriptionKey: 'notifications.sound.chime.description',
    labelKey: 'notifications.sound.chime.label',
    length: 'short',
    value: 'chime',
  },
  {
    accent: 'var(--accent)',
    descriptionKey: 'notifications.sound.cascade.description',
    labelKey: 'notifications.sound.cascade.label',
    length: 'long',
    value: 'cascade',
  },
  {
    accent: 'var(--status-orange)',
    descriptionKey: 'notifications.sound.beacon.description',
    labelKey: 'notifications.sound.beacon.label',
    length: 'long',
    value: 'beacon',
  },
  {
    accent: 'var(--status-purple)',
    descriptionKey: 'notifications.sound.resolve.description',
    labelKey: 'notifications.sound.resolve.label',
    length: 'long',
    value: 'resolve',
  },
  {
    accent: 'var(--text-tertiary)',
    descriptionKey: 'notifications.sound.off.description',
    labelKey: 'notifications.sound.off.label',
    length: 'silent',
    value: 'off',
  },
]

const DETAIL_CHOICES: readonly DetailChoice[] = [
  {
    descriptionKey: 'notifications.detail.brief.description',
    labelKey: 'notifications.detail.brief.label',
    value: 'brief',
  },
  {
    descriptionKey: 'notifications.detail.detailed.description',
    labelKey: 'notifications.detail.detailed.label',
    value: 'detailed',
  },
]

const usePopoverDismissal = (
  open: boolean,
  close: () => void,
  container: RefObject<HTMLDivElement | null>,
  trigger: RefObject<HTMLButtonElement | null>
) => {
  useEffect(() => {
    if (!open) return

    const dismissOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      close()
      trigger.current?.focus()
    }
    const dismissOutside = (event: PointerEvent) => {
      if (!container.current?.contains(event.target as Node)) close()
    }
    document.addEventListener('keydown', dismissOnEscape)
    document.addEventListener('pointerdown', dismissOutside)
    return () => {
      document.removeEventListener('keydown', dismissOnEscape)
      document.removeEventListener('pointerdown', dismissOutside)
    }
  }, [close, container, open, trigger])
}

const SectionHeading = ({ icon, label }: { icon: 'sound' | 'detail'; label: string }) => (
  <div className="mb-2 flex items-center gap-1.5 text-ter text-xs uppercase tracking-wider">
    {icon === 'sound' ? <Volume2 size={12} aria-hidden /> : <Info size={12} aria-hidden />}
    {label}
  </div>
)

interface SoundPickerProps {
  selected: NotificationSound
  onPreview: (sound: NotificationSound) => void
  onSelect: (sound: NotificationSound) => void
}

const SoundPicker = ({ onPreview, onSelect, selected }: SoundPickerProps) => {
  const { t } = useI18n()
  const sectionLabel = t('notifications.sound.sectionLabel')

  return (
    <section className="mb-3">
      <SectionHeading icon="sound" label={sectionLabel} />
      <div role="radiogroup" aria-label={sectionLabel} className="grid grid-cols-2 gap-2">
        {SOUND_CHOICES.map((choice) => {
          const active = selected === choice.value
          const label = t(choice.labelKey)
          return (
            <div
              key={choice.value}
              className="relative min-h-[78px] rounded border transition-colors"
              style={{
                background: active
                  ? `color-mix(in oklab, ${choice.accent} 10%, var(--bg-2))`
                  : 'var(--bg-2)',
                borderColor: active
                  ? `color-mix(in oklab, ${choice.accent} 54%, var(--border-bright))`
                  : 'var(--border)',
              }}
            >
              <label className="block h-full w-full cursor-pointer rounded px-3 py-2 pr-10 text-left transition-colors hover:bg-3 focus-within:outline-none focus-within:ring-2 focus-within:ring-[var(--ring-focus)]">
                <input
                  type="radio"
                  name="notification-sound"
                  value={choice.value}
                  checked={active}
                  className="sr-only"
                  onChange={() => onSelect(choice.value)}
                />
                <span className="mb-1 flex items-center gap-2">
                  <span
                    className="flex h-5 w-5 items-center justify-center rounded"
                    style={{
                      background: `color-mix(in oklab, ${choice.accent} 16%, transparent)`,
                      color: choice.accent,
                    }}
                  >
                    {choice.value === 'off' ? (
                      <VolumeX size={12} aria-hidden />
                    ) : (
                      <Volume2 size={12} aria-hidden />
                    )}
                  </span>
                  <span className="font-medium text-pri text-xs">{label}</span>
                  {choice.length === 'long' ? (
                    <span className="rounded border border-[var(--border-bright)] px-1.5 py-0.5 text-xs text-ter uppercase">
                      {t('notifications.sound.longerBadge')}
                    </span>
                  ) : null}
                  {active ? <Check size={12} className="ml-auto text-pri" aria-hidden /> : null}
                </span>
                <span className="block text-ter text-xs">{t(choice.descriptionKey)}</span>
              </label>
              {choice.value !== 'off' ? (
                <button
                  type="button"
                  aria-label={t('notifications.sound.previewAria', { label })}
                  className="absolute right-2 bottom-2 flex h-6 w-6 items-center justify-center rounded border text-sec transition-colors hover:bg-3 hover:text-pri"
                  onClick={() => onPreview(choice.value)}
                  style={{ borderColor: 'var(--border-bright)' }}
                >
                  <Play size={12} aria-hidden />
                </button>
              ) : null}
            </div>
          )
        })}
      </div>
    </section>
  )
}

const DetailPicker = ({
  onSelect,
  selected,
}: {
  onSelect: (detail: NotificationDetail) => void
  selected: NotificationDetail
}) => {
  const { t } = useI18n()
  const sectionLabel = t('notifications.detail.sectionLabel')

  return (
    <section className="mb-3">
      <SectionHeading icon="detail" label={sectionLabel} />
      <div
        role="radiogroup"
        aria-label={sectionLabel}
        className="grid grid-cols-2 rounded border p-1"
        style={{ background: 'var(--bg-1)', borderColor: 'var(--border)' }}
      >
        {DETAIL_CHOICES.map((choice) => {
          const active = selected === choice.value
          return (
            <label
              key={choice.value}
              className="cursor-pointer rounded px-3 py-2 text-left transition-colors hover:bg-3 focus-within:outline-none focus-within:ring-2 focus-within:ring-[var(--ring-focus)]"
              style={{
                background: active ? 'var(--bg-3)' : 'transparent',
                color: active ? 'var(--text-primary)' : 'var(--text-secondary)',
              }}
            >
              <input
                type="radio"
                name="notification-detail"
                value={choice.value}
                checked={active}
                className="sr-only"
                onChange={() => onSelect(choice.value)}
              />
              <span className="block font-medium text-xs">{t(choice.labelKey)}</span>
              <span className="block text-ter text-xs">{t(choice.descriptionKey)}</span>
            </label>
          )
        })}
      </div>
    </section>
  )
}

interface DesktopPreferenceProps {
  checked: boolean
  unsupported: boolean
  onChange: (enabled: boolean) => void
}

const DesktopPreference = ({ checked, onChange, unsupported }: DesktopPreferenceProps) => {
  const { t } = useI18n()
  return (
    <label className="mb-3 flex items-start gap-2 rounded border p-2 text-sec text-xs">
      <input
        type="checkbox"
        aria-label={t('notifications.desktop.aria')}
        checked={checked}
        className="mt-0.5"
        disabled={unsupported}
        onChange={(event) => onChange(event.currentTarget.checked)}
      />
      <span>
        <span className="block font-medium text-pri">{t('notifications.desktop.label')}</span>
        <span className="text-ter">
          {unsupported
            ? t('notifications.desktop.unsupported')
            : t('notifications.desktop.helper')}
        </span>
      </span>
    </label>
  )
}

const SettingsIntroduction = () => {
  const { t } = useI18n()
  return (
    <div className="mb-3 flex items-start gap-2">
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded bg-3 text-sec">
        <Bell size={16} aria-hidden />
      </div>
      <div className="min-w-0">
        <div className="text-sm font-semibold text-pri">{t('notifications.settings.heading')}</div>
        <div className="text-ter text-xs">{t('notifications.settings.subtitle')}</div>
      </div>
    </div>
  )
}

export const NotificationSettingsButton = () => {
  const { t } = useI18n()
  const notifications = useNotifications()
  const [open, setOpen] = useState(false)
  const container = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const close = useCallback(() => setOpen(false), [])
  const desktopUnsupported = typeof window !== 'undefined' && !('Notification' in window)

  usePopoverDismissal(open, close, container, trigger)

  const update = (patch: Partial<NotificationSettings>) => notifications.updateSettings(patch)
  const setDesktop = (enabled: boolean) => {
    if (enabled) void notifications.requestDesktopNotifications()
    else update({ desktop: false })
  }
  const sendTestNotification = () => {
    notifications.notify({
      brief: t('notifications.test.brief'),
      detail: t('notifications.test.detail'),
      kind: 'success',
      title: t('notifications.test.title'),
    })
  }

  return (
    <div ref={container} className="relative">
      <Tooltip label={t('notifications.settings.tooltip')}>
        <button
          ref={trigger}
          type="button"
          aria-expanded={open}
          aria-haspopup="dialog"
          aria-label={t('notifications.settings.aria')}
          className="flex h-7 w-7 cursor-pointer items-center justify-center rounded text-sec hover:bg-3 hover:text-pri"
          data-testid="topbar-settings"
          onClick={() => setOpen((current) => !current)}
        >
          <Bell size={14} aria-hidden />
        </button>
      </Tooltip>

      {open ? (
        <div
          role="dialog"
          aria-label={t('notifications.settings.aria')}
          className="elev-2 absolute top-8 right-0 z-50 w-[380px] rounded border p-3"
          data-testid="notification-settings"
          style={{ background: 'var(--bg-elevated)', borderColor: 'var(--border-bright)' }}
        >
          <SettingsIntroduction />
          <SoundPicker
            onPreview={notifications.previewSound}
            onSelect={(sound) => update({ sound })}
            selected={notifications.settings.sound}
          />
          <DetailPicker
            onSelect={(detail) => update({ detail })}
            selected={notifications.settings.detail}
          />
          <DesktopPreference
            checked={notifications.settings.desktop}
            onChange={setDesktop}
            unsupported={desktopUnsupported}
          />
          <div
            className="flex justify-end gap-2 border-t pt-3"
            style={{ borderColor: 'var(--border)' }}
          >
            <button type="button" className="icon-btn" onClick={close}>
              {t('common.close')}
            </button>
            <button
              type="button"
              className="icon-btn icon-btn--primary"
              onClick={sendTestNotification}
            >
              {t('notifications.test.button')}
            </button>
          </div>
        </div>
      ) : null}
    </div>
  )
}
