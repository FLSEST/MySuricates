/// <reference types="vite/client" />

declare const __BUILD_TIMESTAMP__: string;
declare const __APP_VERSION__: string;
/** Address the Vite dev server listens on (MYSURICATES_DEV_BIND); empty in a production build. */
declare const __DEV_BIND__: string;

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_WS_BASE_URL: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
