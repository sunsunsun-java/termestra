import * as RadixTooltip from '@radix-ui/react-tooltip'
import type { ReactNode } from 'react'

import { I18nProvider } from './i18n.js'
import { NotificationProvider } from './notifications/NotificationProvider.js'
import { Toaster } from './ui/toast.js'
import { ToastProvider } from './ui/useToast.js'

type ProviderProps = { children: ReactNode }

const FeedbackProviders = ({ children }: ProviderProps) => (
  <ToastProvider>
    <NotificationProvider>
      {children}
      <Toaster />
    </NotificationProvider>
  </ToastProvider>
)

/** Shared browser services required by both the app and isolated UI surfaces. */
export const AppProviders = ({ children }: ProviderProps) => (
  <RadixTooltip.Provider delayDuration={250} skipDelayDuration={150}>
    <I18nProvider>
      <FeedbackProviders>{children}</FeedbackProviders>
    </I18nProvider>
  </RadixTooltip.Provider>
)
