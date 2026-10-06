import { describe, expect, it } from 'vitest';

// Guard (e86f6544): date and number texts follow the app language, not the browser's.
// A toLocaleString() without locale takes the browser language — the dashboard clock thus showed
// "Donnerstag, 24.09.2026" in the English UI. Therefore only utils/localeFormat.ts talks to Intl
// and the toLocale…String methods; all other files call its helpers with the i18n language.
const SOURCES = import.meta.glob<string>(
  ['../**/*.{ts,tsx}', '!../**/__tests__/**', '!../**/*.test.{ts,tsx}', '!../test/**'],
  { query: '?raw', import: 'default', eager: true },
);

const GATEWAY = '../utils/localeFormat.ts';

const FORBIDDEN: Array<{ name: string; pattern: RegExp }> = [
  { name: 'toLocale…String()', pattern: /\.toLocale(?:Date|Time)?String\s*\(/ },
  { name: 'new Intl.…', pattern: /\bnew\s+Intl\.\w+/ },
  { name: 'Intl.…()', pattern: /\bIntl\.\w+\s*\(/ },
];

/** Comments may name the methods; only code counts. Keeps `//` inside URLs (`https://…`). */
function withoutComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/.*$/gm, '$1');
}

/** One entry per offending line: `src/…:line pattern: code`. */
function offenders(file: string, source: string): string[] {
  return withoutComments(source).split('\n').flatMap((line, index) => {
    const hit = FORBIDDEN.find(({ pattern }) => pattern.test(line));
    return hit ? [`${file.replace(/^\.\.\//, 'src/')}:${index + 1} ${hit.name}: ${line.trim()}`] : [];
  });
}

describe('Date and number format', () => {
  it('finds the source files and the helper', () => {
    expect(Object.keys(SOURCES).length).toBeGreaterThan(200);
    expect(SOURCES[GATEWAY]).toBeDefined();
    // Counter-check: the patterns recognize what the helper itself does
    expect(offenders(GATEWAY, SOURCES[GATEWAY]!).length).toBeGreaterThan(0);
  });

  it('goes only through utils/localeFormat with the app language', () => {
    const found = Object.entries(SOURCES)
      .filter(([file]) => file !== GATEWAY)
      .flatMap(([file, source]) => offenders(file, source));

    expect(found).toEqual([]);
  });

  it('misses no call and no comment trick', () => {
    expect(offenders('x.tsx', 'const a = d.toLocaleString();')).toHaveLength(1);
    expect(offenders('x.tsx', "const a = d.toLocaleDateString(undefined, { month: 'short' });")).toHaveLength(1);
    expect(offenders('x.tsx', 'const a = new Intl.NumberFormat().format(n);')).toHaveLength(1);
    expect(offenders('x.tsx', 'const a = Intl.DateTimeFormat().resolvedOptions();')).toHaveLength(1);
    expect(offenders('x.tsx', "const url = 'https://example.org'; const a = d.toLocaleTimeString();")).toHaveLength(1);
    expect(offenders('x.tsx', '// d.toLocaleString() ist verboten\nconst a = 1; /* Intl.NumberFormat() */')).toEqual([]);
    expect(offenders('x.tsx', 'const o: Intl.DateTimeFormatOptions = {};')).toEqual([]);
  });
});
