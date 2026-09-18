import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Wächter: Der API-Key darf nie wieder in einer URL landen (Browser-History, Proxy-Logs).
// WebSocket-Handshakes authentifizieren sich seit 05b759f6 über ein kurzlebiges Einmal-Ticket
// (POST /api/v1/ws-ticket, Key im Header) — siehe WebSocketAuthInterceptor im Backend.
describe('kein apiKey im Query-String', () => {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const srcDir = path.resolve(currentDir, '..');

  function sourceFiles(dir: string): string[] {
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) return entry.name === 'node_modules' ? [] : sourceFiles(full);
      if (!/\.tsx?$/.test(entry.name)) return [];
      // Testdateien dürfen das Verbot benennen, um es zu prüfen
      if (/\.test\.tsx?$/.test(entry.name) || full.includes(`${path.sep}__tests__${path.sep}`)) return [];
      return [full];
    });
  }

  it('findet Quelldateien zum Prüfen', () => {
    expect(sourceFiles(srcDir).length).toBeGreaterThan(100);
  });

  it('kein Produktivcode hängt apiKey= an eine URL', () => {
    const offenders = sourceFiles(srcDir).filter((file) => readFileSync(file, 'utf-8').includes('apiKey='));

    expect(offenders.map((f) => path.relative(srcDir, f))).toEqual([]);
  });
});
