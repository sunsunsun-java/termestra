import { useEffect, useState } from 'react'

import pkg from '../../package.json'
import { getVersionInfo, type VersionInfo } from './api.js'

export const APP_VERSION = pkg.version as string

let resolvedVersionInfo: VersionInfo | null = null
let versionInfoRequest: Promise<VersionInfo> | null = null

const loadVersionInfo = (): Promise<VersionInfo> => {
  if (resolvedVersionInfo) return Promise.resolve(resolvedVersionInfo)
  if (versionInfoRequest) return versionInfoRequest

  versionInfoRequest = getVersionInfo()
    .then((info) => {
      resolvedVersionInfo = info
      return info
    })
    .finally(() => {
      versionInfoRequest = null
    })
  return versionInfoRequest
}

export const useVersionInfo = (provided?: VersionInfo): VersionInfo | null => {
  const [loaded, setLoaded] = useState<VersionInfo | null>(() => resolvedVersionInfo)

  useEffect(() => {
    if (provided || loaded) return
    let subscribed = true
    loadVersionInfo().then(
      (info) => {
        if (subscribed) setLoaded(info)
      },
      () => {
        if (subscribed) setLoaded(null)
      }
    )
    return () => {
      subscribed = false
    }
  }, [provided, loaded])

  return provided ?? loaded
}
