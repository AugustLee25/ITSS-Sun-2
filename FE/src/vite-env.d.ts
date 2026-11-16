/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Mặc định `/api/v1` (dev proxy `/api` → http://localhost:8080). */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
