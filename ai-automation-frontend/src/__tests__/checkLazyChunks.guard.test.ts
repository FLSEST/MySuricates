import { afterEach, describe, expect, it } from 'vitest';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
// @ts-expect-error — Node script without type declaration, runs at the end of `npm run build`
import { checkLazyChunks } from '../../scripts/check-lazy-chunks.mjs';

// The build check itself against small fake builds: it must find Monaco at the entry —
// via modulepreload and via static imports even several chunks deep — and must not
// report dynamic import() calls (TODO 2a552d8a, Monaco on every page).
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

  it('lets a build through that loads Monaco only via import()', () => {
    const dir = build(['/assets/react-BBBBBBBB.js'], {
      'index-AAAAAAAA.js': 'import{t as e}from"./react-BBBBBBBB.js";const m=()=>import("./monaco-CCCCCCCC.js");',
      'react-BBBBBBBB.js': 'export{};',
      'monaco-CCCCCCCC.js': 'export const o=1;',
    });
    expect(checkLazyChunks(dir, ['monaco']).violations).toEqual([]);
  });

  it('reports a modulepreload link to Monaco', () => {
    const dir = build(['/assets/monaco-CCCCCCCC.js'], {
      'index-AAAAAAAA.js': 'console.log(1);',
      'monaco-CCCCCCCC.js': 'export const o=1;',
    });
    const { violations } = checkLazyChunks(dir, ['monaco']);
    expect(violations).toHaveLength(1);
    expect(violations[0]).toContain('modulepreload');
  });

  it('reports a static import even via intermediate chunks, with path', () => {
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

  // How the real build writes it: hash with hyphen, dynamic import in backticks
  // behind Vite's preload helper, static import in double quotes.
  it('recognizes chunks with a hyphen in the hash and reports only the static one, not the backtick import()', () => {
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

  it('fails if a listed lazy chunk is missing from the build', () => {
    const dir = build([], { 'index-AAAAAAAA.js': 'console.log(1);' });
    const { violations } = checkLazyChunks(dir, ['monaco']);
    expect(violations).toHaveLength(1);
    expect(violations[0]).toContain('fehlt im Build');
  });

  // Call as a script via a symlink path: process.argv[1] is then not the real path.
  // Previously the script therefore did not consider itself called directly and ended silently with 0.
  it('also fails with exit 1 when called via a symlink path', () => {
    const dist = build(['/assets/monaco-CCCCCCCC.js'], {
      'index-AAAAAAAA.js': 'console.log(1);',
      'monaco-CCCCCCCC.js': 'export const o=1;',
    });
    const linkParent = mkdtempSync(path.join(tmpdir(), 'lazy-chunks-link-'));
    dirs.push(linkParent);
    const link = path.join(linkParent, 'scripts');
    // 'junction' needs no admin rights on Windows; Linux and macOS ignore the type
    symlinkSync(scriptsDir, link, 'junction');
    try {
      const result = spawnSync(process.execPath, [path.join(link, 'check-lazy-chunks.mjs'), dist], {
        encoding: 'utf-8',
      });
      expect(result.status).toBe(1);
      expect(result.stderr).toContain('modulepreload');
    } finally {
      // Remove only the link, never delete recursively across it
      unlinkSync(link);
    }
  });
});
