import { AppInner } from './AppInner.js'
import { AppProviders } from './AppProviders.js'

/** The complete Termestra UI tree, independent of the browser mount point. */
export function App() {
  return (
    <AppProviders>
      <AppInner />
    </AppProviders>
  )
}
