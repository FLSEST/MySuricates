#!/usr/bin/env node
// Build check: chunks meant to be lazy must not hang off the entry point.
//
// Monaco (3.7 MB) was loaded on every page because Rolldown had put Vite's preload helper into the
// monaco chunk: index.html carried a modulepreload link to it and index-*.js
// imported it statically (TODO 2a552d8a). The script reads dist/index.html, follows all static imports
// from the entry scripts and fails if a chunk from LAZY_CHUNKS
// is reached via modulepreload or static import. Dynamic import() calls do not count
// — these chunks are supposed to arrive through them.
//
// Usage: node scripts/check-lazy-chunks.mjs [distDir]   (runs at the end of `npm run build`)
import { existsSync, readFileSync, readdirSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Chunk names (part before the hash) that may only be loaded later via import().
// If one is missing from the build, the check fails as well: otherwise it would stay
// silently green after a rename.
export const LAZY_CHUNKS = [
  'monaco', // monaco-editor, vendor chunk from vite.config.ts
  'editor.worker', // Monaco worker
  'json.worker',
  'TerminalPanel', // @xterm/xterm, lazy in RunContainerPanel
  'BpmnDiagramView', // lazy in WorkflowBlockList (panel of the workflow list)
];

// Static imports/re-exports in minified ESM: import"x", import{a}from"x",
// import a,{b}from"x", import*as a from"x", export{a}from"x", export*from"x".
// import("x") does not match because the parenthesis follows `import` directly.
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
 * Checks a build folder. Returns the list of violations as readable sentences (empty = ok).
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

  // Breadth-first search over static imports from the entry scripts, with the path back for the message.
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

// Called directly? Resolve both sides for real: Node sets import.meta.url to the real path,
// process.argv[1] keeps a symlink path. Without realpath a call via a
// symlink ended silently with exit 0 — even with a broken build.
function realPath(file) {
  try {
    return realpathSync(file);
  } catch {
    return path.resolve(file);
  }
}
const isMain = Boolean(process.argv[1]) && realPath(process.argv[1]) === realPath(fileURLToPath(import.meta.url));
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
