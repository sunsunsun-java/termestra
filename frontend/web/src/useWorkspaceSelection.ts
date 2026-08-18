import { useCallback, useRef, useState } from 'react'

import { saveActiveWorkspaceId } from './api.js'
import { createLatestWriteQueue } from './lib/latest-write-queue.js'
import { logSwallowed } from './lib/log-swallowed.js'

export const useWorkspaceSelection = () => {
  const [activeWorkspaceId, setActiveWorkspaceId] = useState<string | null>(null)
  const activeWorkspaceSaveQueue = useRef(
    createLatestWriteQueue(saveActiveWorkspaceId, logSwallowed('selectWorkspace.save'))
  )

  const selectWorkspace = useCallback((workspaceId: string | null) => {
    setActiveWorkspaceId(workspaceId)
    activeWorkspaceSaveQueue.current.enqueue(workspaceId)
  }, [])

  return { activeWorkspaceId, selectWorkspace, setActiveWorkspaceId }
}
