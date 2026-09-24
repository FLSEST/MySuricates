#!/usr/bin/env node
// Build-Pruefung: Lazy gedachte Chunks duerfen nicht am Einstieg haengen.
//
// Monaco (3,7 MB) wurde auf jeder Seite geladen, weil Rolldown Vites Preload-Helper in den
// monaco-Chunk gelegt hatte: index.html trug einen modulepreload-Link darauf und index-*.js
// importierte ihn statisch (TODO 2a552d8a). Das Skript liest dist/index.html, folgt von den
// Einstiegs-Skripten allen statischen Importen und scheitert, wenn ein Chunk aus LAZY_CHUNKS
// per modulepreload oder statischem Import erreicht wird. Dynamische import()-Aufrufe zaehlen
// nicht — ueber die sollen diese Chunks kommen.
//
// Aufruf: node scripts/check-lazy-chunks.mjs [distDir]   (laeuft am Ende von `npm run build`)
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Chunk-Namen (Teil vor dem Hash), die nur ueber import() nachgeladen werden duerfen.
// Fehlt einer im Build, scheitert die Pruefung ebenfalls: sonst bliebe sie nach einer
// Umbenennung stumm gruen.
export const LAZY_CHUNKS = [
  'monaco', // monaco-editor, Vendor-Chunk aus vite.config.ts
  'editor.worker', // Monaco-Worker
  'json.worker',
  'TerminalPanel', // @xterm/xterm, lazy in RunContainerPanel
  'BpmnDiagramView', // lazy in WorkflowDetailPanel
];

// Statische Importe/Re-Exporte in minifiziertem ESM: import"x", import{a}from"x",
// import a,{b}from"x", import*as a from"x", export{a}from"x", export*from"x".
// import("x") passt nicht, weil nach `import` direkt die Klammer folgt.
const STATIC_IMPORT =
  /(?:^|[^\w$.])(?:import|export)\s*(?:[\w$]+\s*,?\s*)?(?:\{[^}]*\}|\*\s*(?:as\s+[\w$]+)?)?\s*(?:from\s*)?["']([^"']+)["']/g;

function attr(tag, name) {
  const match = tag.match(new RegExp(`\\b${name}\\s*=\\s*["']([^"']+)["']`));
  return match ? match[1] : null;
}

function chunkName(file) {
  // monaco-BDW3yWG2.js → monaco, editor.worker-O-CH8JUO.js → editor.worker
  const match = path.basename(file).match(/^(.+?)-[\w-]{8}\.js$/);
  return match ? match[1] : path.basename(file, '.js');
}

function staticImports(file) {
  const code = readFileSync(file, 'utf-8');
  const imports = [];
  for (const match of code.matchAll(STATIC_IMPORT)) {
    const spec = match[1];
    if (spec.startsWith('./') || spec.startsWith('../')) {
      imports.push(path.resolve(path.dirname(file), spec));
    }
  }
  return imports;
}

/**
 * Prueft einen Build-Ordner. Liefert die Liste der Verstoesse als lesbare Saetze (leer = ok).
 * @param {string} distDir
 * @param {string[]} [lazyChunks]
 * @returns {{ violations: string[], entries: string[], preloads: string[] }}
 */
export function checkLazyChunks(distDir, lazyChunks = LAZY_CHUNKS) {
  const violations = [];
  const html = readFileSync(path.join(distDir, 'index.html'), 'utf-8');
  const toFile = (href) => path.join(distDir, href.replace(/^\//, ''));

  const preloads = [];
  const entries = [];
  for (const tag of html.match(/<(?:link|script)\b[^>]*>/g) ?? []) {
    if (tag.startsWith('<link') && attr(tag, 'rel') === 'modulepreload' && attr(tag, 'href')) {
      preloads.push(attr(tag, 'href'));
    }
    if (tag.startsWith('<script') && attr(tag, 'type') === 'module' && attr(tag, 'src')) {
      entries.push(attr(tag, 'src'));
    }
  }

  const assets = existsSync(path.join(distDir, 'assets')) ? readdirSync(path.join(distDir, 'assets')) : [];
  for (const lazy of lazyChunks) {
    if (!assets.some((file) => file.endsWith('.js') && chunkName(file) === lazy)) {
      violations.push(
        `Lazy-Chunk "${lazy}" fehlt im Build (umbenannt oder entfernt?) — LAZY_CHUNKS in scripts/check-lazy-chunks.mjs pflegen.`,
      );
    }
  }

  for (const href of preloads) {
    if (lazyChunks.includes(chunkName(href))) {
      violations.push(`index.html laedt den Lazy-Chunk "${chunkName(href)}" per <link rel="modulepreload" href="${href}">.`);
    }
  }

  // Breitensuche ueber statische Importe ab den Einstiegs-Skripten, mit Rueckweg fuer die Meldung.
  const parent = new Map();
  const queue = [];
  for (const src of entries) {
    const file = toFile(src);
    if (!parent.has(file)) {
      parent.set(file, null);
      queue.push(file);
    }
  }
  while (queue.length > 0) {
    const file = queue.shift();
    if (!existsSync(file)) continue;
    if (lazyChunks.includes(chunkName(file))) {
      const chain = [];
      for (let step = file; step; step = parent.get(step)) chain.unshift(path.basename(step));
      violations.push(`Lazy-Chunk "${chunkName(file)}" wird statisch vom Einstieg importiert: ${chain.join(' → ')}.`);
      continue;
    }
    for (const dep of staticImports(file)) {
      if (!parent.has(dep)) {
        parent.set(dep, file);
        queue.push(dep);
      }
    }
  }

  return { violations, entries, preloads };
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const distDir = path.resolve(process.argv[2] ?? path.join(path.dirname(fileURLToPath(import.meta.url)), '../dist'));
  const { violations, entries, preloads } = checkLazyChunks(distDir);
  if (violations.length > 0) {
    console.error('check-lazy-chunks: Lazy gedachte Chunks werden beim Seitenaufruf mitgeladen:');
    for (const violation of violations) console.error(`  - ${violation}`);
    console.error(
      'Meist hat Rolldown ein geteiltes Modul (z. B. Vites Preload-Helper) in den Chunk gelegt — '
        + 'codeSplitting.groups in vite.config.ts pruefen.',
    );
    process.exit(1);
  }
  console.log(
    `check-lazy-chunks: ok — ${entries.length} Einstieg(e), ${preloads.length} modulepreload-Links, `
      + `keiner der Lazy-Chunks (${LAZY_CHUNKS.join(', ')}) am Einstieg.`,
  );
}
