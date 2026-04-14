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
    },
    plugins: [
      react(),
      VitePWA({
        registerType: 'autoUpdate',
        includeAssets: ['icons/*.svg'],
        workbox: {
          navigateFallback: '/index.html',
          navigateFallbackDenylist: [/^\/api/, /^\/ws/],
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
  };
});
