/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** "1" builds the static demo: in-browser fixtures instead of the /api backend. */
  readonly VITE_DEMO?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
