import { describe, expect, it } from 'vitest';

// Wächter (23635ef6): Rückfragen laufen über ui/ConfirmModal – einheitliche Optik, Texte aus de.ts/en.ts,
// Escape wie bei Modal. window.confirm zeigt einen Browser-Dialog in der Sprache des Browsers und
// blockiert die Seite; vierzehn Stellen mussten dafür umgebaut werden.
const SOURCES = import.meta.glob<string>(
  ['../**/*.{ts,tsx}', '!../**/__tests__/**', '!../**/*.test.{ts,tsx}', '!../test/**'],
  { query: '?raw', import: 'default', eager: true },
);

/** `confirm(…)` or `window.confirm(…)`, not a method or function that merely ends in "confirm". */
const BROWSER_CONFIRM = /(^|[^.\w$])(?:window\.)?confirm\s*\(/;

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

describe('Rückfragen', () => {
  it('findet die Quelldateien', () => {
    expect(Object.keys(SOURCES).length).toBeGreaterThan(200);
  });

  it('fragen nie über den Browser-Dialog window.confirm', () => {
    const found = Object.entries(SOURCES).flatMap(([file, source]) => offenders(file, source));
    expect(found).toEqual([]);
  });

  it('erkennt die Aufrufe, aber keine Namen, die nur auf confirm enden', () => {
    expect(offenders('x.tsx', 'if (window.confirm(text)) remove();')).toHaveLength(1);
    expect(offenders('x.tsx', 'if (!confirm(text)) return;')).toHaveLength(1);
    expect(offenders('x.tsx', 'onClick={() => { if (confirm(t.x)) go(); }}')).toHaveLength(1);
    expect(offenders('x.tsx', 'await api.confirm(id); onConfirm(); handleConfirm(); confirmReplace(name);')).toEqual([]);
    expect(offenders('x.tsx', '// früher: window.confirm(text)\n/* confirm(x) */')).toEqual([]);
  });
});
