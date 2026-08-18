import { useCallback, useEffect, useRef, useState } from 'react'

import { browseFs, type FsBrowseResponse, type FsProbeResponse, probeFs } from '../api.js'

const EMPTY_BROWSE: FsBrowseResponse = {
  current_path: '',
  entries: [],
  error: null,
  ok: false,
  parent_path: null,
  root_path: '',
  truncated: false,
}

export const useFsBrowser = (enabled: boolean) => {
  const [browse, setBrowse] = useState<FsBrowseResponse>(EMPTY_BROWSE)
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [probe, setProbe] = useState<FsProbeResponse | null>(null)
  const browseTokenRef = useRef(0)
  const probeTokenRef = useRef(0)
  const browseControllerRef = useRef<AbortController | null>(null)
  const probeControllerRef = useRef<AbortController | null>(null)

  const navigate = useCallback(async (path: string) => {
    const token = ++browseTokenRef.current
    browseControllerRef.current?.abort()
    probeTokenRef.current++
    probeControllerRef.current?.abort()
    probeControllerRef.current = null
    const controller = new AbortController()
    browseControllerRef.current = controller
    setProbe(null)
    setSelected(null)
    setLoading(true)
    try {
      const result = await browseFs(path, controller.signal)
      if (browseTokenRef.current !== token) return
      setBrowse(result)
      if (result.ok) {
        setSelected(result.current_path)
      }
    } catch (error) {
      // network/abort while the dialog is closing — swallow; the stale-token
      // guard above keeps stale responses from mutating state anyway.
      if (!controller.signal.aborted) {
        console.error('[termestra] fsBrowser.browse failed', error)
        if (browseTokenRef.current === token) {
          setBrowse({
            ...EMPTY_BROWSE,
            error: error instanceof Error ? error.message : String(error),
          })
        }
      }
    } finally {
      if (browseControllerRef.current === controller) browseControllerRef.current = null
      if (browseTokenRef.current === token) setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (!enabled) {
      browseTokenRef.current++
      probeTokenRef.current++
      browseControllerRef.current?.abort()
      browseControllerRef.current = null
      probeControllerRef.current?.abort()
      probeControllerRef.current = null
      setBrowse(EMPTY_BROWSE)
      setSelected(null)
      setProbe(null)
      return
    }
    void navigate('')
  }, [enabled, navigate])

  useEffect(() => {
    if (!selected) {
      probeControllerRef.current?.abort()
      probeControllerRef.current = null
      setProbe(null)
      return
    }
    const token = ++probeTokenRef.current
    probeControllerRef.current?.abort()
    const controller = new AbortController()
    probeControllerRef.current = controller
    probeFs(selected, controller.signal)
      .then((result) => {
        if (probeTokenRef.current === token) setProbe(result)
      })
      .catch((error: unknown) => {
        // Probe is racy by design — newer selections cancel older ones via the
        // probeTokenRef gate, so a stale probe rejecting is expected, not a bug.
        // Log to dev console anyway so genuine network failures are visible.
        if (!controller.signal.aborted) {
          console.error('[termestra] fsBrowser.probe failed', error)
        }
      })
      .finally(() => {
        if (probeControllerRef.current === controller) probeControllerRef.current = null
      })
    return () => controller.abort()
  }, [selected])

  useEffect(
    () => () => {
      browseControllerRef.current?.abort()
      probeControllerRef.current?.abort()
    },
    []
  )

  const selectEntry = useCallback((path: string) => {
    probeTokenRef.current++
    probeControllerRef.current?.abort()
    probeControllerRef.current = null
    setProbe(null)
    setSelected(path)
  }, [])

  return { browse, loading, navigate, probe, selectEntry, selected }
}
