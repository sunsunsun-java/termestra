import type { ReactNode } from 'react'
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'

import { useI18n } from '../i18n.js'
import type { ToastKind } from '../ui/useToast.js'
import { useToast } from '../ui/useToast.js'

export type NotificationSound = 'off' | 'soft' | 'ping' | 'chime' | 'cascade' | 'beacon' | 'resolve'
export type NotificationDetail = 'brief' | 'detailed'

export interface NotificationSettings {
  desktop: boolean
  detail: NotificationDetail
  sound: NotificationSound
}

export interface NotifyOptions {
  brief: string
  detail?: string
  kind: ToastKind
  title: string
}

interface NotificationApi {
  notify: (options: NotifyOptions) => void
  previewSound: (sound: NotificationSound) => void
  requestDesktopNotifications: () => Promise<boolean>
  settings: NotificationSettings
  updateSettings: (patch: Partial<NotificationSettings>) => void
}

const NOTIFICATION_SETTINGS_KEY = 'termestra.notification.settings'

const DEFAULT_SETTINGS: NotificationSettings = {
  desktop: false,
  detail: 'brief',
  sound: 'soft',
}

const soundAssets: Record<Exclude<NotificationSound, 'off'>, string> = {
  beacon: '/sounds/termestra-beacon.ogg',
  cascade: '/sounds/termestra-cascade.ogg',
  chime: '/sounds/termestra-chime.ogg',
  ping: '/sounds/termestra-ping.ogg',
  resolve: '/sounds/termestra-resolve.ogg',
  soft: '/sounds/termestra-soft.ogg',
}

const isNotificationSound = (sound: unknown): sound is NotificationSound =>
  sound === 'off' ||
  sound === 'ping' ||
  sound === 'chime' ||
  sound === 'soft' ||
  sound === 'cascade' ||
  sound === 'beacon' ||
  sound === 'resolve'

const readSettings = (): NotificationSettings => {
  if (typeof window === 'undefined') return DEFAULT_SETTINGS
  try {
    const raw = window.localStorage.getItem(NOTIFICATION_SETTINGS_KEY)
    if (!raw) return DEFAULT_SETTINGS
    if (raw.length > 4_096) return DEFAULT_SETTINGS
    const parsed = JSON.parse(raw) as Partial<NotificationSettings>
    return {
      desktop: typeof parsed.desktop === 'boolean' ? parsed.desktop : DEFAULT_SETTINGS.desktop,
      detail: parsed.detail === 'detailed' ? 'detailed' : DEFAULT_SETTINGS.detail,
      sound: isNotificationSound(parsed.sound) ? parsed.sound : DEFAULT_SETTINGS.sound,
    }
  } catch {
    return DEFAULT_SETTINGS
  }
}

const writeSettings = (settings: NotificationSettings) => {
  try {
    window.localStorage.setItem(NOTIFICATION_SETTINGS_KEY, JSON.stringify(settings))
  } catch {
    // localStorage can be disabled; notification preferences are best-effort UI state.
  }
}

const playSound = (sound: NotificationSound) => {
  if (sound === 'off' || typeof window === 'undefined') return
  try {
    const audio = new window.Audio(soundAssets[sound])
    audio.preload = 'auto'
    audio.volume = 0.72
    void audio.play()?.catch(() => {
      // Browsers can block media playback until a user gesture; failed sound should not block work.
    })
  } catch {
    // Audio playback is best-effort UI feedback.
  }
}

const NotificationContext = createContext<NotificationApi | null>(null)

export const NotificationProvider = ({ children }: { children: ReactNode }) => {
  const toast = useToast()
  const { t } = useI18n()
  const [settings, setSettings] = useState<NotificationSettings>(() => readSettings())

  useEffect(() => {
    writeSettings(settings)
  }, [settings])

  const updateSettings = useCallback((patch: Partial<NotificationSettings>) => {
    setSettings((current) => ({ ...current, ...patch }))
  }, [])

  const requestDesktopNotifications = useCallback(async (): Promise<boolean> => {
    if (typeof window === 'undefined' || !('Notification' in window)) {
      updateSettings({ desktop: false })
      toast.show({ kind: 'warning', message: t('notifications.toast.unsupported') })
      return false
    }
    if (window.Notification.permission === 'granted') {
      updateSettings({ desktop: true })
      return true
    }
    if (window.Notification.permission === 'denied') {
      updateSettings({ desktop: false })
      toast.show({ kind: 'warning', message: t('notifications.toast.blocked') })
      return false
    }
    const permission = await window.Notification.requestPermission()
    const granted = permission === 'granted'
    updateSettings({ desktop: granted })
    if (!granted) toast.show({ kind: 'warning', message: t('notifications.toast.declined') })
    return granted
  }, [t, toast, updateSettings])

  const notify = useCallback(
    ({ brief, detail, kind, title }: NotifyOptions) => {
      const message = settings.detail === 'detailed' && detail ? detail : brief
      toast.show({ kind, message })
      playSound(settings.sound)
      if (
        settings.desktop &&
        typeof window !== 'undefined' &&
        'Notification' in window &&
        window.Notification.permission === 'granted'
      ) {
        try {
          new window.Notification(title, { body: message })
        } catch {
          // Desktop notifications can fail per-browser; toast remains the reliable channel.
        }
      }
    },
    [settings, toast]
  )

  const previewSound = useCallback((sound: NotificationSound) => {
    playSound(sound)
  }, [])

  const value = useMemo<NotificationApi>(
    () => ({ notify, previewSound, requestDesktopNotifications, settings, updateSettings }),
    [notify, previewSound, requestDesktopNotifications, settings, updateSettings]
  )

  return <NotificationContext.Provider value={value}>{children}</NotificationContext.Provider>
}

export const useNotifications = (): NotificationApi => {
  const context = useContext(NotificationContext)
  if (!context) throw new Error('useNotifications must be used within NotificationProvider')
  return context
}
