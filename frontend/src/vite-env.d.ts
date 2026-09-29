/// <reference types="vite/client" />

interface ImportMetaEnv {
  // Local development only: the tenant slug sent as X-Tenant (chapter 7 section 7.2). Sign-in
  // is real in every environment; the X-Dev-* header stub is for curl and tests, not the PWA.
  readonly VITE_DEV_TENANT?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
