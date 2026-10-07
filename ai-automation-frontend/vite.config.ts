import fs from 'fs';
import path from 'path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// Vendor packages each in their own chunk. monaco-editor is loaded lazily from components/shared/monacoSetup.ts
// — the name monaco-* makes the PWA skip it when precaching.
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

// Address the dev server listens on: loopback unless MYSURICATES_DEV_BIND opens it (e.g. 0.0.0.0 or one
// Tailscale IP) — the same switch scripts/start-mysuricates-dev.sh passes to the backend as
// --server.address (cd7c53f9). Same check as there: only characters of an IP address.
function devBindAddress(): string {
  const bind = process.env.MYSURICATES_DEV_BIND?.trim() || '127.0.0.1';
  if (/[^0-9a-fA-F.:]/.test(bind)) {
    throw new Error(`Ungueltiges MYSURICATES_DEV_BIND '${bind}' (eine IP-Adresse, z. B. 127.0.0.1 oder 0.0.0.0).`);
  }
  return bind;
}

// Host under which this machine reaches a server bound to `bind`: localhost for 127.0.0.1 and all
// interfaces, otherwise that address — a dev backend bound to one Tailscale IP does not answer on
// localhost. Same rule as netaddr_local_url in scripts/lib/netaddr.sh.
function localHostFor(bind: string): string {
  if (['127.0.0.1', '0.0.0.0', '::'].includes(bind)) return 'localhost';
  return bind.includes(':') ? `[${bind}]` : bind;
}

export default defineConfig(() => {
  const devBind = devBindAddress();
  const devBackendHost = localHostFor(devBind);
  const e2eTarget = process.env.CYPRESS_TARGET === 'test' ? 'test' : 'dev';
  const defaultApiOrigin = e2eTarget === 'test' ? 'http://localhost:8090' : `http://${devBackendHost}:9090`;
  const defaultWsOrigin = e2eTarget === 'test' ? 'ws://localhost:8090' : `ws://${devBackendHost}:9090`;
  const apiProxyTarget = normalizeApiProxyTarget(
    process.env.CYPRESS_API_BASE_URL?.trim() || process.env.VITE_API_BASE_URL?.trim() || `${defaultApiOrigin}/api/v1`,
  );
  const wsProxyTarget = normalizeWsProxyTarget(
    process.env.CYPRESS_WS_BASE_URL?.trim() || process.env.VITE_WS_BASE_URL?.trim() || defaultWsOrigin,
  );

  return {
    // Vite's dependency optimizer writes to node_modules/.vite by default. A worktree's node_modules
    // is a symlink to the main checkout, so a second Vite (E2E side instance on :5173) would rewrite
    // the cache of the Vite already serving :3000 — VITE_CACHE_DIR gives it a cache of its own.
    ...(process.env.VITE_CACHE_DIR?.trim() ? { cacheDir: path.resolve(process.env.VITE_CACHE_DIR.trim()) } : {}),
    define: {
      __BUILD_TIMESTAMP__: JSON.stringify(new Date().toISOString()),
      // The frontend version is otherwise nowhere in the bundle. The BuildInfoStat tile puts it
      // next to the backend version from /system/info: if the two differ, a new frontend runs
      // against an old backend JAR — the case that produces 404s and hanging
      // pages in the test container (CLAUDE.md, Staging).
      __APP_VERSION__: JSON.stringify(
        JSON.parse(fs.readFileSync(path.resolve(import.meta.dirname, 'package.json'), 'utf-8')).version,
      ),
      // Shim for sockjs-client, which references bare `global` (see node_modules/sockjs-client/lib/*.js).
      // Replaces the former inline <script>var global = globalThis;</script> in index.html (CSP violation).
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
      host: devBind,
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
          // One group per vendor chunk instead of one manualChunks function: Rolldown pulls the
          // dependencies of captured modules into the group (includeDependenciesRecursively).
          // With a single naming function, a shared module got the group that captured it first —
          // Vite's preload helper (\0vite/preload-helper.js, needed by every import()) ended up
          // in monaco, React itself in dnd; index thus loaded the 3.7 MB of Monaco statically.
          // The priority decides who gets a shared module: preload helper first, then
          // the vendor chunks in VENDOR_CHUNKS order. scripts/check-lazy-chunks.mjs checks
          // after every build that no lazy chunk is attached to the entry again.
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
