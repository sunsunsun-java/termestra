export type ServiceWorkerUpdateApply = () => void
type ServiceWorkerUpdateListener = (apply: ServiceWorkerUpdateApply | null) => void

const listeners = new Set<ServiceWorkerUpdateListener>()
let currentApply: ServiceWorkerUpdateApply | null = null

const setUpdateApply = (apply: ServiceWorkerUpdateApply | null) => {
  currentApply = apply
  for (const listener of listeners) listener(apply)
}

export const subscribeServiceWorkerUpdate = (
  listener: ServiceWorkerUpdateListener
): (() => void) => {
  listeners.add(listener)
  listener(currentApply)
  return () => {
    listeners.delete(listener)
  }
}

export const __resetServiceWorkerUpdateStateForTests = (): void => {
  listeners.clear()
  currentApply = null
}

export const __setServiceWorkerUpdateForTests = (apply: ServiceWorkerUpdateApply | null): void => {
  setUpdateApply(apply)
}

export interface ServiceWorkerEnv {
  isProd: boolean
  serviceWorker: ServiceWorkerContainer | null
  reload: () => void
}

const CONTROLLERCHANGE_FALLBACK_MS = 2000

export const registerServiceWorkerWithEnv = async (env: ServiceWorkerEnv): Promise<void> => {
  if (!env.serviceWorker) return
  if (!env.isProd) {
    const registrations = await env.serviceWorker.getRegistrations()
    await Promise.all(registrations.map((registration) => registration.unregister()))
    return
  }

  let registration: ServiceWorkerRegistration
  try {
    registration = await env.serviceWorker.register('/sw.js')
  } catch (error) {
    console.error('[termestra] service worker registration failed', error)
    return
  }

  let fallbackReloadTimer: ReturnType<typeof setTimeout> | undefined
  const observedWorkers = new WeakSet<ServiceWorker>()
  const observe = (worker: ServiceWorker) => {
    if (observedWorkers.has(worker)) return
    observedWorkers.add(worker)
    const onStateChange = () => {
      if (worker.state === 'installed' && env.serviceWorker?.controller) {
        setUpdateApply(() => {
          worker.postMessage({ type: 'SKIP_WAITING' })
          if (fallbackReloadTimer === undefined) {
            fallbackReloadTimer = setTimeout(() => {
              fallbackReloadTimer = undefined
              env.reload()
            }, CONTROLLERCHANGE_FALLBACK_MS)
          }
        })
      }
    }
    worker.addEventListener('statechange', onStateChange)
    // waiting workers are already installed and will not emit another
    // statechange; inspect current state immediately.
    onStateChange()
  }

  if (registration.waiting && env.serviceWorker.controller) observe(registration.waiting)
  if (registration.installing) observe(registration.installing)
  registration.addEventListener('updatefound', () => {
    if (registration.installing) observe(registration.installing)
  })

  let refreshing = false
  env.serviceWorker.addEventListener('controllerchange', () => {
    if (refreshing) return
    refreshing = true
    if (fallbackReloadTimer !== undefined) {
      clearTimeout(fallbackReloadTimer)
      fallbackReloadTimer = undefined
    }
    setUpdateApply(null)
    env.reload()
  })
}
