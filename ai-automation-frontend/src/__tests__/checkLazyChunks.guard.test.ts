import { afterEach, describe, expect, it } from 'vitest';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
// @ts-expect-error — Node-Skript ohne Typdeklaration, laeuft am Ende von `npm run build`
import { checkLazyChunks } from '../../scripts/check-lazy-chunks.mjs';

// Die Build-Pruefung selbst gegen kleine Schein-Builds: Sie muss Monaco am Einstieg finden —
// per modulepreload und ueber statische Importe auch mehrere Chunks tief — und darf
// dynamische import()-Aufrufe nicht melden (TODO 2a552d8a, Monaco auf jeder Seite).
describe('check-lazy-chunks build guard', () => {
  let dist: string;

  const build = (preloads: string[], files: Record<string, string>) => {
    dist = mkdtempSync(path.join(tmpdir(), 'lazy-chunks-'));
    mkdirSync(path.join(dist, 'assets'));
    const links = preloads.map((href) => `<link rel="modulepreload" crossorigin href="${href}">`).join('\n');
    writeFileSync(
      path.join(dist, 'index.html'),
      `<head><script type="module" crossorigin src="/assets/index-AAAAAAAA.js"></script>\n${links}</head>`,
    );
    for (const [name, code] of Object.entries(files)) writeFileSync(path.join(dist, 'assets', name), code);
    return dist;
  };

  afterEach(() => rmSync(dist, { recursive: true, force: true }));

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

  it('scheitert, wenn ein gelisteter Lazy-Chunk im Build fehlt', () => {
    const dir = build([], { 'index-AAAAAAAA.js': 'console.log(1);' });
    const { violations } = checkLazyChunks(dir, ['monaco']);
    expect(violations).toHaveLength(1);
    expect(violations[0]).toContain('fehlt im Build');
  });
});
