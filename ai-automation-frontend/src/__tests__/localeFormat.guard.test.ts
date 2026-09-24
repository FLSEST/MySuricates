import { describe, expect, it } from 'vitest';

// Wächter (e86f6544): Datums- und Zahlentexte folgen der App-Sprache, nicht der des Browsers.
// Ein toLocaleString() ohne Locale nimmt die Browser-Sprache — die Dashboard-Uhr stand so im
// englischen UI auf „Donnerstag, 24.09.2026“. Deshalb spricht nur utils/localeFormat.ts mit Intl
// und den toLocale…String-Methoden; alle anderen Dateien rufen dessen Helfer mit der i18n-Sprache.
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

describe('Datums- und Zahlenformat', () => {
  it('findet die Quelldateien und den Helfer', () => {
    expect(Object.keys(SOURCES).length).toBeGreaterThan(200);
    expect(SOURCES[GATEWAY]).toBeDefined();
    // Gegenprobe: die Muster erkennen, was der Helfer selbst tut
    expect(offenders(GATEWAY, SOURCES[GATEWAY]!).length).toBeGreaterThan(0);
  });

  it('läuft nur über utils/localeFormat mit der Sprache der App', () => {
    const found = Object.entries(SOURCES)
      .filter(([file]) => file !== GATEWAY)
      .flatMap(([file, source]) => offenders(file, source));

    expect(found).toEqual([]);
  });

  it('übersieht keinen Aufruf und keinen Kommentar-Trick', () => {
    expect(offenders('x.tsx', 'const a = d.toLocaleString();')).toHaveLength(1);
    expect(offenders('x.tsx', "const a = d.toLocaleDateString(undefined, { month: 'short' });")).toHaveLength(1);
    expect(offenders('x.tsx', 'const a = new Intl.NumberFormat().format(n);')).toHaveLength(1);
    expect(offenders('x.tsx', 'const a = Intl.DateTimeFormat().resolvedOptions();')).toHaveLength(1);
    expect(offenders('x.tsx', "const url = 'https://example.org'; const a = d.toLocaleTimeString();")).toHaveLength(1);
    expect(offenders('x.tsx', '// d.toLocaleString() ist verboten\nconst a = 1; /* Intl.NumberFormat() */')).toEqual([]);
    expect(offenders('x.tsx', 'const o: Intl.DateTimeFormatOptions = {};')).toEqual([]);
  });
});
