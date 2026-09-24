import { afterEach, describe, expect, it } from 'vitest';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
// @ts-expect-error — Node-Skript ohne Typdeklaration, laeuft am Ende von `npm run build`
import { checkLazyChunks } from '../../scripts/check-lazy-chunks.mjs';

// Die Build-Pruefung selbst gegen kleine Schein-Builds: Sie muss Monaco am Einstieg finden —
// per modulepreload und ueber statische Importe auch mehrere Chunks tief — und darf
// dynamische import()-Aufrufe nicht melden (TODO 2a552d8a, Monaco auf jeder Seite).
describe('check-lazy-chunks build guard', () => {
  const scriptsDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../scripts');
  const dirs: string[] = [];

  const build = (preloads: string[], files: Record<string, string>) => {
    const dist = mkdtempSync(path.join(tmpdir(), 'lazy-chunks-'));
    dirs.push(dist);
    mkdirSync(path.join(dist, 'assets'));
    const links = preloads.map((href) => `<link rel="modulepreload" crossorigin href="${href}">`).join('\n');
    writeFileSync(
      path.join(dist, 'index.html'),
      `<head><script type="module" crossorigin src="/assets/index-AAAAAAAA.js"></script>\n${links}</head>`,
    );
    for (const [name, code] of Object.entries(files)) writeFileSync(path.join(dist, 'assets', name), code);
    return dist;
  };

  afterEach(() => {
    for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
  });

  it('laesst einen Build durch, der Monaco nur per import() nachlaedt', () => {
    const dir = build(['/assets/react-BBBBBBBB.js'], {
      'index-AAAAAAAA.js': 'import{t as e}from"./react-BBBBBBBB.js";const m=()=>import("./monaco-CCCCCCCC.js");',
      'react-BBBBBBBB.js': 'export{};',
      'monaco-CCCCCCCC.js': 'export const o=1;',
    });
    expect(checkLazyChunks(dir, ['monaco']).violations).toEqual([]);
  });

  it('meldet einen modulepreload-Link auf Monaco', () => {
    const dir = build(['/assets/monaco-CCCCCCCC.js'], {
      'index-AAAAAAAA.js': 'console.log(1);',
      'monaco-CCCCCCCC.js': 'export const o=1;',
    });
    const { violations } = checkLazyChunks(dir, ['monaco']);
    expect(violations).toHaveLength(1);
    expect(violations[0]).toContain('modulepreload');
  });

  it('meldet einen statischen Import auch ueber Zwischen-Chunks, mit Pfad', () => {
    const dir = build([], {
      'index-AAAAAAAA.js': 'import"./react-BBBBBBBB.js";',
      'react-BBBBBBBB.js': 'import{o as i}from"./monaco-CCCCCCCC.js";export{i as t};',
      'monaco-CCCCCCCC.js': 'export const o=1;',
    });
    const { violations } = checkLazyChunks(dir, ['monaco']);
    expect(violations).toEqual([
      'Lazy-Chunk "monaco" wird statisch vom Einstieg importiert: index-AAAAAAAA.js → react-BBBBBBBB.js → monaco-CCCCCCCC.js.',
    ]);
  });

  // So schreibt der echte Build: Hash mit Bindestrich, dynamischer Import in Backticks
  // hinter Vites Preload-Helper, statischer Import in doppelten Anfuehrungszeichen.
  it('erkennt Chunks mit Bindestrich im Hash und meldet nur den statischen, nicht den Backtick-import()', () => {
    const worker = { 'editor.worker-O-CH8JUO.js': 'self.onmessage=()=>{};' };
    const dynamic = build([], {
      'index-AAAAAAAA.js': 'const w=()=>i(()=>import(`./editor.worker-O-CH8JUO.js`),[]);',
      ...worker,
    });
    expect(checkLazyChunks(dynamic, ['editor.worker']).violations).toEqual([]);

    const staticImport = build([], {
      'index-AAAAAAAA.js': 'import{t as e}from"./editor.worker-O-CH8JUO.js";',
      ...worker,
    });
    expect(checkLazyChunks(staticImport, ['editor.worker']).violations).toEqual([
      'Lazy-Chunk "editor.worker" wird statisch vom Einstieg importiert: index-AAAAAAAA.js → editor.worker-O-CH8JUO.js.',
    ]);
  });

  it('scheitert, wenn ein gelisteter Lazy-Chunk im Build fehlt', () => {
    const dir = build([], { 'index-AAAAAAAA.js': 'console.log(1);' });
    const { violations } = checkLazyChunks(dir, ['monaco']);
    expect(violations).toHaveLength(1);
    expect(violations[0]).toContain('fehlt im Build');
  });

  // Aufruf als Skript ueber einen Symlink-Pfad: process.argv[1] ist dann nicht der echte Pfad.
  // Frueher hielt sich das Skript deshalb nicht fuer direkt aufgerufen und endete still mit 0.
  it('scheitert auch beim Aufruf ueber einen Symlink-Pfad mit Exit 1', () => {
    const dist = build(['/assets/monaco-CCCCCCCC.js'], {
      'index-AAAAAAAA.js': 'console.log(1);',
      'monaco-CCCCCCCC.js': 'export const o=1;',
    });
    const linkParent = mkdtempSync(path.join(tmpdir(), 'lazy-chunks-link-'));
    dirs.push(linkParent);
    const link = path.join(linkParent, 'scripts');
    // 'junction' braucht unter Windows keine Admin-Rechte; Linux und macOS ignorieren den Typ
    symlinkSync(scriptsDir, link, 'junction');
    try {
      const result = spawnSync(process.execPath, [path.join(link, 'check-lazy-chunks.mjs'), dist], {
        encoding: 'utf-8',
      });
      expect(result.status).toBe(1);
      expect(result.stderr).toContain('modulepreload');
    } finally {
      // Nur den Link entfernen, nie ueber ihn hinweg rekursiv loeschen
      unlinkSync(link);
    }
  });
});
