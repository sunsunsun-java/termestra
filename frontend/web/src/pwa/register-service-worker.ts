// Service worker registration with an update-available notification channel.
//
// Producers: registerServiceWorkerWithEnv (called once at boot).
// Consumers: subscribeServiceWorkerUpdate (the UpdateAvailableToast component).
// The split lets us inject a fake env in unit tests instead of leaning on
// jsdom's missing ServiceWorker implementation or import.meta.env stubbing.

import { silentReload } from '../useBeforeUnloadGuard.js'
import { registerServiceWorkerWithEnv } from './service-worker-registration.js'

export {
  __resetServiceWorkerUpdateStateForTests,
  __setServiceWorkerUpdateForTests,
  registerServiceWorkerWithEnv,
  subscribeServiceWorkerUpdate,
} from './service-worker-registration.js'
export type {
  ServiceWorkerEnv,
  ServiceWorkerUpdateApply,
} from './service-worker-registration.js'

export const registerServiceWorker = (): Promise<void> => {
  if (typeof navigator === 'undefined' || typeof window === 'undefined') return Promise.resolve()
  return registerServiceWorkerWithEnv({
    isProd: import.meta.env.PROD,
    serviceWorker: 'serviceWorker' in navigator ? navigator.serviceWorker : null,
    reload: silentReload,
  })
}
