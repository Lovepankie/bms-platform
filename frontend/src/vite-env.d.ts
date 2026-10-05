/// <reference types="vite/client" />

interface ImportMetaEnv {
  // Local development only: the tenant slug sent as X-Tenant (chapter 7 section 7.2). Sign-in
  // is real in every environment; the X-Dev-* header stub is for curl and tests, not the PWA.
  readonly VITE_DEV_TENANT?: string;
  // Set to "1" to run the retail screens on fabricated data without a backend (retail-ui-notes.md).
  readonly VITE_RETAIL_MOCK?: string;
  // With the mock on: "sales" or "admin" (default) decides which permissions the fake session holds.
  readonly VITE_RETAIL_MOCK_ROLE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
