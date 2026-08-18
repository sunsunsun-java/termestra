import { useCallback, useEffect, useRef, useState } from 'react'

import {
  getWorkspaceTasks,
  saveWorkspaceTasks,
  type TasksSnapshot,
  TasksRevisionConflictError,
} from '../api.js'
import {
  appendChildTaskAtLine,
  deleteTaskLine,
  toggleTaskLine,
  updateTaskTextAtLine,
} from './task-markdown.js'
import {
  createObservedRejection,
  createTasksStream,
  createTasksWriteQueue,
  type TasksWriteQueue,
} from './tasks-sync.js'

const toTasksSocketUrl = (workspaceId: string) => {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws/tasks/${workspaceId}`
}

const shouldIgnoreRemoteUpdate = (
  nextContent: string,
  savedContent: string,
  currentContent: string,
  writeQueue: TasksWriteQueue | null
) =>
  nextContent === savedContent ||
  nextContent === currentContent ||
  writeQueue?.hasPendingContent(nextContent) === true

const NO_TASKS_WRITE = Promise.resolve<TasksSnapshot | undefined>(undefined)

export const useTasksFile = (workspaceId: string | null, demoContent?: string) => {
  const [content, setContent] = useState('')
  const [contentWorkspaceId, setContentWorkspaceId] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [hasConflict, setHasConflict] = useState(false)
  const [connectionStale, setConnectionStale] = useState(false)
  const [remoteContent, setRemoteContent] = useState<string | null>(null)
  const dirtyRef = useRef(false)
  const savedContentRef = useRef('')
  const contentRef = useRef('')
  const remoteSnapshotRef = useRef<TasksSnapshot | null>(null)
  const writeQueueRef = useRef<TasksWriteQueue | null>(null)

  const replaceContent = useCallback((snapshot: TasksSnapshot) => {
    savedContentRef.current = snapshot.content
    contentRef.current = snapshot.content
    dirtyRef.current = false
    setContent(snapshot.content)
    setHasConflict(false)
    setRemoteContent(null)
    remoteSnapshotRef.current = null
  }, [])

  const applyRemoteContent = useCallback((snapshot: TasksSnapshot) => {
    const nextContent = snapshot.content
    const writeQueue = writeQueueRef.current
    const hasLocalMutation = dirtyRef.current || writeQueue?.hasPendingWrites() === true
    if (!hasLocalMutation) {
      writeQueueRef.current?.setRevision(snapshot.revision)
      replaceContent(snapshot)
      return
    }
    if (
      shouldIgnoreRemoteUpdate(
        nextContent,
        savedContentRef.current,
        contentRef.current,
        writeQueue
      )
    ) {
      writeQueueRef.current?.setRevision(snapshot.revision)
      return
    }
    // A divergent remote document supersedes the local write generation.
    // An already-sent request may still finish, but its acknowledgement and
    // any same-generation pending write no longer own UI or persistence state.
    writeQueue?.supersede(snapshot.revision)
    dirtyRef.current = contentRef.current !== snapshot.content
    remoteSnapshotRef.current = snapshot
    setRemoteContent(nextContent)
    setHasConflict(true)
  }, [replaceContent])

  useEffect(() => {
    writeQueueRef.current?.invalidate()
    writeQueueRef.current = null
    setContent('')
    setContentWorkspaceId(workspaceId)
    setLoaded(false)
    setHasConflict(false)
    setConnectionStale(false)
    setRemoteContent(null)
    dirtyRef.current = false
    savedContentRef.current = ''
    contentRef.current = ''
    remoteSnapshotRef.current = null
    if (!workspaceId) return

    let current = true
    const writeQueue = createTasksWriteQueue({
      initialRevision: undefined,
      isBlockingFailure: (error) => error instanceof TasksRevisionConflictError,
      onAccepted: (snapshot) => {
        if (!current) return
        // The user may continue editing the raw document while this save is
        // in flight without enqueuing the newer value yet. The acknowledgement
        // advances savedContent in onCommitted, but must not replace that
        // newer local editor value or clear its dirty state.
        if (contentRef.current !== snapshot.content) {
          dirtyRef.current = contentRef.current !== savedContentRef.current
          return
        }
        replaceContent(snapshot)
      },
      onCommitted: (snapshot) => {
        if (!current) return
        savedContentRef.current = snapshot.content
      },
      onFailed: (error) => {
        console.error('[termestra] tasks.save failed', error)
        if (error instanceof TasksRevisionConflictError && error.snapshot) {
          writeQueue.setRevision(error.snapshot.revision)
        }
      },
      onRejected: (error) => {
        if (!current) return
        if (error instanceof TasksRevisionConflictError && error.snapshot) {
          remoteSnapshotRef.current = error.snapshot
          writeQueue.setRevision(error.snapshot.revision)
          dirtyRef.current = true
          setRemoteContent(error.snapshot.content)
          setHasConflict(true)
          return
        }
        // A transport/server failure does not mean the user's local edit was
        // invalid. Keep it dirty and visible so it can be retried instead of
        // silently replacing it with the last persisted document.
        dirtyRef.current = contentRef.current !== savedContentRef.current
      },
      save: (nextContent, revision) =>
        saveWorkspaceTasks(workspaceId, {
          content: nextContent,
          ...(revision !== undefined ? { revision } : {}),
        }),
    })
    writeQueueRef.current = writeQueue

    const stream = createTasksStream({
      workspaceId,
      loadSnapshot: (signal) => getWorkspaceTasks(workspaceId, signal),
      onLoadError: (error) => {
        if (!current) return
        setLoaded(true)
        console.error('[termestra] tasks recovery snapshot failed', error)
      },
      onSnapshot: (snapshot) => {
        if (!current) return
        applyRemoteContent(snapshot)
        setLoaded(true)
      },
      onStaleChange: (stale) => {
        if (current) setConnectionStale(stale)
      },
      openSocket: () => new WebSocket(toTasksSocketUrl(workspaceId)),
    })

    return () => {
      current = false
      stream.dispose()
      writeQueue.invalidate()
      if (writeQueueRef.current === writeQueue) writeQueueRef.current = null
    }
  }, [applyRemoteContent, replaceContent, workspaceId])

  const enqueueContent = (nextContent: string) => {
    const queue = writeQueueRef.current
    if (!workspaceId || !queue) return NO_TASKS_WRITE
    if (connectionStale) {
      return createObservedRejection(
        new Error('Tasks connection is stale; wait for reconnection')
      )
    }
    dirtyRef.current = false
    contentRef.current = nextContent
    setContent(nextContent)
    setHasConflict(false)
    setRemoteContent(null)
    remoteSnapshotRef.current = null
    return queue.enqueue(nextContent)
  }

  const persistTransform = (transform: (current: string) => string) => {
    const previous = contentRef.current
    const next = transform(previous)
    if (next === previous) return NO_TASKS_WRITE
    return enqueueContent(next)
  }

  // Demo short-circuit: all hooks have run above; workspaceId is null when a
  // static fixture is supplied, so the stream and write queue stay inactive.
  if (demoContent !== undefined) {
    return {
      content: demoContent,
      connectionStale: false,
      hasConflict: false,
      loaded: true,
      onChange: (_value: string) => {},
      onKeepLocal: () => {},
      onReload: () => {},
      onSave: () => NO_TASKS_WRITE,
      toggleTaskAtLine: (_lineIndex: number) => NO_TASKS_WRITE,
      appendTask: (_text: string) => NO_TASKS_WRITE,
      appendSubtask: (_parentLine: number, _text: string) => NO_TASKS_WRITE,
      updateTaskText: (_lineIndex: number, _nextText: string) => NO_TASKS_WRITE,
      deleteTask: (_lineIndex: number) => NO_TASKS_WRITE,
    }
  }

  const ownsVisibleContent = contentWorkspaceId === workspaceId

  return {
    content: ownsVisibleContent ? content : '',
    connectionStale: ownsVisibleContent ? connectionStale : false,
    hasConflict: ownsVisibleContent ? hasConflict : false,
    loaded: ownsVisibleContent ? loaded : false,
    onChange: (value: string) => {
      dirtyRef.current = value !== savedContentRef.current
      contentRef.current = value
      setContent(value)
    },
    onKeepLocal: () => {
      const remoteSnapshot = remoteSnapshotRef.current
      if (remoteSnapshot) writeQueueRef.current?.setRevision(remoteSnapshot.revision)
      dirtyRef.current = contentRef.current !== savedContentRef.current
      setHasConflict(false)
      setRemoteContent(null)
      remoteSnapshotRef.current = null
    },
    onReload: () => {
      const remoteSnapshot = remoteSnapshotRef.current
      const nextSnapshot: TasksSnapshot = remoteSnapshot ?? {
        content: remoteContent ?? savedContentRef.current,
      }
      // Reload is an explicit remote-wins choice. It must also discard any
      // local write queued after the conflict appeared but before this click.
      writeQueueRef.current?.supersede(nextSnapshot.revision)
      replaceContent(nextSnapshot)
    },
    onSave: () => {
      if (!workspaceId || contentRef.current === savedContentRef.current) return NO_TASKS_WRITE
      return enqueueContent(contentRef.current)
    },
    toggleTaskAtLine: (lineIndex: number) =>
      persistTransform((current) => toggleTaskLine(current, lineIndex)),
    appendTask: (text: string) => {
      const trimmed = text.trim()
      if (!workspaceId || !trimmed) return NO_TASKS_WRITE
      return persistTransform((previous) => {
        const needsLeadingNewline = previous.length > 0 && !previous.endsWith('\n')
        return `${previous}${needsLeadingNewline ? '\n' : ''}- [ ] ${trimmed}\n`
      })
    },
    appendSubtask: (parentLine: number, text: string) => {
      const trimmed = text.trim()
      if (!trimmed) return NO_TASKS_WRITE
      return persistTransform((current) => appendChildTaskAtLine(current, parentLine, trimmed))
    },
    updateTaskText: (lineIndex: number, nextText: string) => {
      const trimmed = nextText.trim()
      if (!trimmed) return NO_TASKS_WRITE
      return persistTransform((current) => updateTaskTextAtLine(current, lineIndex, trimmed))
    },
    deleteTask: (lineIndex: number) =>
      persistTransform((current) => deleteTaskLine(current, lineIndex)),
  }
}
