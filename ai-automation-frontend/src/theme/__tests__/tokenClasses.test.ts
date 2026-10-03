import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// Read from disk: Vitest hands CSS imports (also `?raw`) over empty.
const indexCss = readFileSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../index.css'), 'utf-8');

// Gate against colour classes whose theme token does not exist (TODO a3fce6f5). Tailwind silently emits nothing for
// `text-text-primary`, `bg-surface-base` or `hover:bg-bg-secondary` — the element keeps the inherited colour or stays
// transparent, nobody notices. Every class (and every `var(--color-…)`) that names one of the theme's token families
// must name a token that index.css defines in its `@theme inline` block. See CLAUDE.md → Key Conventions → Styling.
//
// KNOWN_UNDEFINED lists the only undefined tokens still in use, each with its reason; an entry nobody uses any more
// must leave the list, so it only ever shrinks.

const SOURCES = import.meta.glob<string>(
  ['../../**/*.{ts,tsx}', '!../../**/__tests__/**', '!../../**/*.test.*', '!../../**/*.d.ts', '!../../test/**'],
  { query: '?raw', import: 'default', eager: true },
);

/** Undefined token name → why it is still in use. */
const KNOWN_UNDEFINED: Record<string, string> = {};

/** Colour names the theme defines: `--color-<name>` inside `@theme inline { … }`. */
function definedTokens(css: string): Set<string> {
  const start = css.indexOf('@theme inline');
  const block = css.slice(start, css.indexOf('@keyframes', start));
  return new Set([...block.matchAll(/^\s*--color-([a-z0-9-]+):/gm)].map((m) => m[1]!));
}

const DEFINED = definedTokens(indexCss);

/**
 * Names that look like a theme token: the first segment of a defined token (surface, text, accent …) plus the
 * family names of other design systems that keep slipping in (primary, background, bg-secondary …).
 */
const FAMILIES = [
  ...new Set([...DEFINED].map((name) => name.split('-')[0]!)),
  'primary', 'secondary', 'tertiary', 'background', 'foreground', 'bg', 'muted', 'elevated',
];

const UTILITIES = [
  'bg', 'text', 'border(?:-[trblxyse])?', 'ring(?:-offset)?', 'from', 'to', 'via', 'fill', 'stroke', 'divide',
  'outline', 'decoration', 'shadow', 'placeholder', 'accent', 'caret',
];

const NAME = `(?:${FAMILIES.join('|')})(?:-[a-z0-9]+)*`;
const TOKEN_CLASS = new RegExp(`(?<![\\w-])(?:${UTILITIES.join('|')})-(${NAME})(?:/\\d+)?(?![\\w$-])`, 'g');
const TOKEN_VAR = new RegExp(`--color-(${NAME})(?![\\w$-])`, 'g');

interface Finding {
  file: string;
  line: number;
  match: string;
  token: string;
}

function scan(file: string, source: string, defined: Set<string> = DEFINED): Finding[] {
  return source.split('\n').flatMap((text, i) =>
    [...text.matchAll(TOKEN_CLASS), ...text.matchAll(TOKEN_VAR)]
      .filter((m) => !defined.has(m[1]!))
      .map((m) => ({ file, line: i + 1, match: m[0], token: m[1]! })));
}

/**
 * Text on a solid fill needs a contrast-checked pairing (themes.test.ts): `text-text-inverse` on `bg-accent`,
 * `bg-danger-emphasis` or `bg-success-emphasis` (TODO e2abe6c1). Plain `bg-danger`/`bg-success`/`bg-warning` are the
 * palette tone for dots and bars; fixed white/black ignores the theme altogether.
 */
const SOLID_FILL_TEXT: { pattern: RegExp; why: string }[] = [
  {
    pattern: /(?<![\w-])text-(?:white|black)(?![\w-])/,
    why: 'fixed white/black ignores the theme — text on bg-accent / bg-*-emphasis is text-text-inverse',
  },
  {
    pattern: /(?<![\w-])bg-(?:danger|success|warning)(?![\w/-])[^'"`]*text-text-inverse|text-text-inverse[^'"`]*(?<![\w-])bg-(?:danger|success|warning)(?![\w/-])/,
    why: 'text on a solid status fill — use bg-danger-emphasis / bg-success-emphasis (warning has none: add it first)',
  },
];

function solidFillProblems(file: string, source: string): string[] {
  return source.split('\n').flatMap((text, i) =>
    SOLID_FILL_TEXT.filter((rule) => rule.pattern.test(text)).map((rule) => `  ${file}:${i + 1} ${rule.why}`));
}

function relative(globPath: string): string {
  return new URL(globPath, 'file:///src/theme/__tests__/').pathname.replace(/^\/src\//, '');
}

function findings(): Finding[] {
  return Object.entries(SOURCES).flatMap(([path, source]) => {
    return scan(relative(path), source);
  });
}

describe('theme token classes', () => {
  it('reads the defined tokens from index.css', () => {
    for (const name of ['surface', 'surface-raised', 'text', 'text-secondary', 'border-strong', 'accent-emphasis',
      'danger-subtle', 'chart-8', 'card-border', 'sidebar-text-muted']) {
      expect(DEFINED.has(name), name).toBe(true);
    }
    expect(DEFINED.has('text-primary')).toBe(false);
  });

  it('name only tokens that index.css defines (or KNOWN_UNDEFINED)', () => {
    const all = findings();
    const problems = all
      .filter((f) => !KNOWN_UNDEFINED[f.token])
      .map((f) => `  ${f.file}:${f.line} ${f.match} — token "${f.token}" is not defined in index.css`);
    for (const token of Object.keys(KNOWN_UNDEFINED)) {
      if (!all.some((f) => f.token === token)) problems.push(`  "${token}" is no longer used — remove it from KNOWN_UNDEFINED`);
    }
    expect(problems.join('\n')).toBe('');
  });

  it('put text on solid fills only via the contrast-checked pairings', () => {
    const problems = Object.entries(SOURCES).flatMap(([path, source]) => solidFillProblems(relative(path), source));
    expect(problems.join('\n')).toBe('');
  });

  it('flags white text and text on plain status fills, but not the emphasis pairings', () => {
    const source = [
      "const a = 'bg-accent text-white';",
      "const b = 'border-success bg-success text-text-inverse';",
      "const c = 'text-text-inverse bg-danger';",
      "const d = 'bg-danger-emphasis text-text-inverse bg-accent text-text-inverse';",
      "const e = 'bg-danger/10 text-danger'; const f = 'h-2 rounded-full bg-success';",
    ].join('\n');
    expect(solidFillProblems('Example.tsx', source).map((p) => p.trim().split(' ')[0])).toEqual([
      'Example.tsx:1',
      'Example.tsx:2',
      'Example.tsx:3',
    ]);
  });

  it('finds undefined tokens with variants and opacity, and leaves defined tokens and other utilities alone', () => {
    const defined = new Set(['text', 'text-secondary', 'surface', 'surface-raised', 'accent', 'chart-1']);
    const source = [
      "const a = 'text-text-primary hover:bg-bg-secondary/50 bg-surface-base';",
      "const b = 'text-text bg-surface-raised/80 hover:text-text-secondary bg-accent text-sm text-base text-balance';",
      "const c = 'border-t-surface-elevated ring-offset-background bg-red-500 bg-[#211a14] shadow-lg';",
      'const d = `var(--color-text-primary) var(--color-chart-1) --color-chart-${i} --color-red-500`;',
    ].join('\n');

    expect(scan('Example.tsx', source, defined).map((f) => `${f.line}:${f.match}`)).toEqual([
      '1:text-text-primary',
      '1:bg-bg-secondary/50',
      '1:bg-surface-base',
      '3:border-t-surface-elevated',
      '3:ring-offset-background',
      '4:--color-text-primary',
    ]);
  });
});
