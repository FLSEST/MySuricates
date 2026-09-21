import fs from 'fs';
import path from 'path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// Vendor-Pakete je eigenem Chunk. monaco-editor wird lazy aus components/shared/monacoSetup.ts
// geladen — der Name monaco-* laesst die PWA ihn beim Precaching auslassen.
const VENDOR_CHUNKS: Record<string, string[]> = {
  react: ['react', 'react-dom', 'react-router-dom', 'react-router', 'scheduler'],
  query: ['@tanstack/react-query', '@tanstack/query-core'],
  ws: ['@stomp/stompjs', 'sockjs-client'],
  markdown: ['react-markdown', 'remark-gfm'],
  dnd: ['@dnd-kit/core', '@dnd-kit/sortable', '@dnd-kit/utilities'],
  monaco: ['monaco-editor'],
};

function normalizeApiProxyTarget(url: string): string {
  return url.replace(/\/api\/v1\/?$/, '').replace(/\/$/, '');
}

function normalizeWsProxyTarget(url: string): string {
  return url.replace(/^ws(s?):\/\//, 'http$1://').replace(/\/$/, '');
}

export default defineConfig(() => {
  const e2eTarget = process.env.CYPRESS_TARGET === 'test' ? 'test' : 'dev';
  const defaultApiOrigin = e2eTarget === 'test' ? 'http://localhost:8090' : 'http://localhost:9090';
  const defaultWsOrigin = e2eTarget === 'test' ? 'ws://localhost:8090' : 'ws://localhost:9090';
  const apiProxyTarget = normalizeApiProxyTarget(
    process.env.CYPRESS_API_BASE_URL?.trim() || process.env.VITE_API_BASE_URL?.trim() || `${defaultApiOrigin}/api/v1`,
  );
  const wsProxyTarget = normalizeWsProxyTarget(
    process.env.CYPRESS_WS_BASE_URL?.trim() || process.env.VITE_WS_BASE_URL?.trim() || defaultWsOrigin,
  );

  return {
    define: {
      __BUILD_TIMESTAMP__: JSON.stringify(new Date().toISOString()),
      // Shim für sockjs-client, das bare `global` referenziert (siehe node_modules/sockjs-client/lib/*.js).
      // Ersetzt das bisherige Inline-<script>var global = globalThis;</script> in index.html (CSP-Verstoß).
      global: 'globalThis',
    },
    plugins: [
      react(),
      VitePWA({
        registerType: 'autoUpdate',
        includeAssets: ['icons/*.svg'],
        workbox: {
          navigateFallback: '/index.html',
          navigateFallbackDenylist: [/^\/api/, /^\/ws/],
          // Monaco (editor core > 2 MiB, language workers) loads on first use, not at PWA install
          // Rollup may name Monaco's transitive core chunk after its first export
          // instead of the manual chunk (currently `toggleHighContrast`). Keep both
          // names out of the precache; the editor loads them on demand.
          globIgnores: ['**/monaco-*.js', '**/toggleHighContrast-*.js', '**/*.worker-*.js'],
        },
        manifest: {
          name: 'MySuricates',
          short_name: 'MySuricates',
          description: 'Your meerkats automating workflows',
          theme_color: '#231e19',
          background_color: '#231e19',
          display: 'standalone',
          icons: [
            {
              src: '/icons/icon.svg',
              sizes: 'any',
              type: 'image/svg+xml',
              purpose: 'any maskable',
            },
          ],
        },
      }),
    ],
    server: {
      host: true,
      port: 3000,
      strictPort: true,
      ...(process.env.VITE_HTTPS !== 'false' && fs.existsSync(path.resolve(import.meta.dirname, '../certs/dev.crt'))
        ? {
            https: {
              cert: fs.readFileSync(path.resolve(import.meta.dirname, '../certs/dev.crt')),
              key: fs.readFileSync(path.resolve(import.meta.dirname, '../certs/dev.key')),
            },
          }
        : {}),
      proxy: {
        '/api': {
          target: apiProxyTarget,
          changeOrigin: true,
        },
        '/ws': {
          target: wsProxyTarget,
          changeOrigin: true,
          ws: true,
        },
      },
    },
    build: {
      chunkSizeWarningLimit: 600,
      rolldownOptions: {
        output: {
          // Rolldown (Vite 8) nimmt manualChunks nur noch als Funktion.
          manualChunks: (id: string) => {
            for (const [chunk, packages] of Object.entries(VENDOR_CHUNKS)) {
              if (packages.some((pkg) => id.includes(`/node_modules/${pkg}/`))) return chunk;
            }
            return undefined;
          },
        },
      },
    },
  };
});
