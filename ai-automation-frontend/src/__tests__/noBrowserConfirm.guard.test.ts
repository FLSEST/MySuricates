import { describe, expect, it } from 'vitest';

// Guard (23635ef6): confirmations go through ui/ConfirmModal – uniform look, texts from de.ts/en.ts,
// Escape like Modal. window.confirm shows a browser dialog in the browser's language and
// blocks the page; fourteen places had to be rebuilt for this.
const SOURCES = import.meta.glob<string>(
  ['../**/*.{ts,tsx}', '!../**/__tests__/**', '!../**/*.test.{ts,tsx}', '!../test/**'],
  { query: '?raw', import: 'default', eager: true },
);

/**
 * `confirm(…)`, `window.confirm(…)`, `window?.confirm(…)` or `globalThis.confirm(…)`, not a method or
 * function that merely ends in "confirm".
 */
const BROWSER_CONFIRM = /(^|[^.\w$])(?:(?:window|globalThis)\??\.)?confirm\s*\(/;

/** Comments may name it; only code counts. Keeps `//` inside URLs (`https://…`). */
function withoutComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/.*$/gm, '$1');
}

function offenders(file: string, source: string): string[] {
  return withoutComments(source).split('\n').flatMap((line, index) =>
    BROWSER_CONFIRM.test(line) ? [`${file.replace(/^\.\.\//, 'src/')}:${index + 1}: ${line.trim()}`] : []);
}

describe('Confirmations', () => {
  it('findet die Quelldateien', () => {
    expect(Object.keys(SOURCES).length).toBeGreaterThan(200);
  });

  it('never confirm via the browser dialog window.confirm', () => {
    const found = Object.entries(SOURCES).flatMap(([file, source]) => offenders(file, source));
    expect(found).toEqual([]);
  });

  it('recognizes the calls, but not names that merely end in confirm', () => {
    expect(offenders('x.tsx', 'if (window.confirm(text)) remove();')).toHaveLength(1);
    expect(offenders('x.tsx', 'if (!confirm(text)) return;')).toHaveLength(1);
    expect(offenders('x.tsx', 'onClick={() => { if (confirm(t.x)) go(); }}')).toHaveLength(1);
    expect(offenders('x.tsx', 'if (window?.confirm(text)) remove();')).toHaveLength(1);
    expect(offenders('x.tsx', 'if (globalThis.confirm(text)) remove();')).toHaveLength(1);
    expect(offenders('x.tsx', 'if (globalThis?.confirm(text)) remove();')).toHaveLength(1);
    expect(offenders('x.tsx', 'await api.confirm(id); onConfirm(); handleConfirm(); confirmReplace(name);')).toEqual([]);
    expect(offenders('x.tsx', '// früher: window.confirm(text)\n/* confirm(x) */')).toEqual([]);
  });
});
