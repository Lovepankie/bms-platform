/// <reference types="vite/client" />

interface ImportMetaEnv {
  // Local development only: the tenant slug sent as X-Tenant (chapter 7 section 7.2). Sign-in
  // is real in every environment; the X-Dev-* header stub is for curl and tests, not the PWA.
  readonly VITE_DEV_TENANT?: string;
  // Set to "1" to run the retail screens on fabricated data without a backend (retail-ui-notes.md).
  readonly VITE_RETAIL_MOCK?: string;
  // With the mock on: "sales" or "admin" (default) decides which permissions the fake session holds.
  readonly VITE_RETAIL_MOCK_ROLE?: string;
  // With the mock on: action types that wait for an administrator, comma separated (issue #343), e.g. "retail_sale_void".
  readonly VITE_RETAIL_MOCK_APPROVALS?: string;
  // With the mock on: the switched-on retail capabilities, comma separated, so a wholesaler's menu can be seen (issue #424).
  readonly VITE_RETAIL_MOCK_CAPS?: string;
  // Set by the web image's build (#576): the build's sha tag, as the API's /version says, and its commit time (UTC YYYYMMDDHHMMSS).
  readonly VITE_BMS_BUILD?: string;
  readonly VITE_BMS_BUILT?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

// The help corpus (issue #598, ADR-095), made by the bms-help-corpus plugin of vite.config.ts from src/help/articles.
declare module 'virtual:help-corpus' {
  /** Each article's Markdown by its id, the file name without `.md`. */
  export const sources: Record<string, string>;
  /** The first 16 hex digits of the SHA-256 of the articles, in name order. */
  export const revision: string;
}
