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

const PRELOAD_HELPER_ID = 'vite/preload-helper';

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
      // Die Frontend-Version steht sonst nirgends im Bundle. Die Kachel BuildInfoStat stellt sie
      // neben die Backend-Version aus /system/info: weichen beide ab, laeuft ein neues Frontend
      // gegen ein altes Backend-JAR — der Fall, der beim Test-Container 404er und haengende
      // Seiten erzeugt (CLAUDE.md, Staging).
      __APP_VERSION__: JSON.stringify(
        JSON.parse(fs.readFileSync(path.resolve(__dirname, 'package.json'), 'utf-8')).version,
      ),
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
          // Monaco (editor core > 2 MiB, language workers) loads on first use, not at PWA install.
          // The codeSplitting group below keeps all of monaco-editor in the `monaco` chunk.
          // Its stylesheet (~146 KB) goes with it: offline the CSS is useless without the JS,
          // it only adds to the install size. The lazy MonacoEditor chunk loads it on demand.
          globIgnores: ['**/monaco-*.js', '**/monaco-*.css', '**/*.worker-*.js'],
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
          // Eine Gruppe je Vendor-Chunk statt einer manualChunks-Funktion: Rolldown zieht die
          // Abhaengigkeiten erfasster Module mit in die Gruppe (includeDependenciesRecursively).
          // Bei einer einzigen Namensfunktion bekam ein geteiltes Modul die zuerst erfassende Gruppe —
          // Vites Preload-Helper (\0vite/preload-helper.js, von jedem import() gebraucht) landete
          // in monaco, React selbst in dnd; index lud so die 3,7 MB Monaco statisch mit.
          // Die Prioritaet legt fest, wer ein geteiltes Modul bekommt: Preload-Helper vorn, dann
          // die Vendor-Chunks in VENDOR_CHUNKS-Reihenfolge. scripts/check-lazy-chunks.mjs prueft
          // nach jedem Build, dass kein Lazy-Chunk wieder am Einstieg haengt.
          codeSplitting: {
            groups: [
              { name: 'preload-helper', test: (id: string) => id.includes(PRELOAD_HELPER_ID), priority: 100 },
              ...Object.entries(VENDOR_CHUNKS).map(([chunk, packages], index) => ({
                name: chunk,
                test: (id: string) => packages.some((pkg) => id.includes(`/node_modules/${pkg}/`)),
                priority: 50 - index,
              })),
            ],
          },
        },
      },
    },
  };
});
