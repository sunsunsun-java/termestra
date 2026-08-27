// Service worker registration with an update-available notification channel.
//
// The environment-aware implementation lives separately so tests can inject
// browser capabilities without relying on jsdom's ServiceWorker support.

import { silentReload } from '../useBeforeUnloadGuard.js'
import { registerServiceWorkerWithEnv } from './service-worker-registration.js'

export const registerServiceWorker = (): Promise<void> => {
  if (typeof navigator === 'undefined' || typeof window === 'undefined') return Promise.resolve()
  return registerServiceWorkerWithEnv({
    isProd: import.meta.env.PROD,
    serviceWorker: 'serviceWorker' in navigator ? navigator.serviceWorker : null,
    reload: silentReload,
  })
}
