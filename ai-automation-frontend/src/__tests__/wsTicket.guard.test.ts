import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Guard: the API key must never end up in a URL again (browser history, proxy logs).
// WebSocket handshakes have authenticated via a short-lived one-time ticket since 05b759f6
// (POST /api/v1/ws-ticket, key in the header) — see WebSocketAuthInterceptor in the backend.
describe('no apiKey in the query string', () => {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const srcDir = path.resolve(currentDir, '..');

  function sourceFiles(dir: string): string[] {
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) return entry.name === 'node_modules' ? [] : sourceFiles(full);
      if (!/\.tsx?$/.test(entry.name)) return [];
      // Test files may name the prohibition in order to check it
      if (/\.test\.tsx?$/.test(entry.name) || full.includes(`${path.sep}__tests__${path.sep}`)) return [];
      return [full];
    });
  }

  it('finds source files to check', () => {
    expect(sourceFiles(srcDir).length).toBeGreaterThan(100);
  });

  it('no production code appends apiKey= to a URL', () => {
    const offenders = sourceFiles(srcDir).filter((file) => readFileSync(file, 'utf-8').includes('apiKey='));

    expect(offenders.map((f) => path.relative(srcDir, f))).toEqual([]);
  });
});
