/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_DEV_TENANT?: string;
  readonly VITE_DEV_USER_ID?: string;
  readonly VITE_DEV_PERMISSIONS?: string;
  readonly VITE_DEV_BRANCH_IDS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
