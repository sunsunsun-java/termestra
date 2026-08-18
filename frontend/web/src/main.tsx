import { StrictMode } from 'react'
import { createRoot, type Root } from 'react-dom/client'

import { App } from './app.js'
import { registerServiceWorker } from './pwa/register-service-worker.js'
import './styles/globals.css'

const requireMountPoint = (): HTMLElement => {
  const mountPoint = document.getElementById('root')
  if (mountPoint === null) throw new Error('Root element not found')
  return mountPoint
}

const mountApplication = (mountPoint: HTMLElement): Root => {
  const root = createRoot(mountPoint)
  root.render(
    <StrictMode>
      <App />
    </StrictMode>
  )
  return root
}

const startBrowserApplication = (): void => {
  mountApplication(requireMountPoint())
  void registerServiceWorker()
}

startBrowserApplication()
