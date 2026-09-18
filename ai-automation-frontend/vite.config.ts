import fs from 'fs';
import path from 'path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

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
          globIgnores: ['**/monaco-*.js', '**/*.worker-*.js'],
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
      ...(process.env.VITE_HTTPS !== 'false' && fs.existsSync(path.resolve(__dirname, '../certs/dev.crt'))
        ? {
            https: {
              cert: fs.readFileSync(path.resolve(__dirname, '../certs/dev.crt')),
              key: fs.readFileSync(path.resolve(__dirname, '../certs/dev.key')),
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
      rollupOptions: {
        output: {
          manualChunks: {
            react: ['react', 'react-dom', 'react-router-dom'],
            query: ['@tanstack/react-query'],
            ws: ['@stomp/stompjs', 'sockjs-client'],
            markdown: ['react-markdown', 'remark-gfm'],
            dnd: ['@dnd-kit/core', '@dnd-kit/sortable', '@dnd-kit/utilities'],
            // lazily imported by components/shared/monacoSetup.ts — named so the PWA can skip precaching it
            monaco: ['monaco-editor/esm/vs/editor/edcore.main.js'],
          },
        },
      },
    },
  };
});
