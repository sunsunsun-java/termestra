/// <reference types="vite/client" />

/** Environment values consumed by the Termestra browser entrypoint. */
interface ImportMetaEnv {
  readonly PROD: boolean
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
