import { describe, it, expect } from 'vitest';

// Sources of every dashboard widget and of the Insights charts they reuse (tests excluded).
const SOURCES = import.meta.glob<string>(
  ['../**/*.{ts,tsx}', '!../**/__tests__/**', '../../insights/**/*.{ts,tsx}', '!../../insights/**/__tests__/**'],
  { query: '?raw', import: 'default', eager: true },
);

/**
 * Dashboard content comes from runs, workflow names and (Phase 5) workflow output — possibly
 * injected text. It is only ever rendered as React text nodes: no HTML, no Markdown, no URLs or
 * images from data (spec E3). Whoever needs one of these here has to change this test on purpose.
 */
const FORBIDDEN: [string, RegExp][] = [
  ['dangerouslySetInnerHTML', /dangerouslySetInnerHTML/],
  ['innerHTML', /\.innerHTML\b/],
  ['Markdown renderer', /from ['"][^'"]*markdown/i],
  ['img element', /<img\b/],
  ['iframe element', /<iframe\b/],
  ['href attribute', /\bhref=/],
  ['src attribute', /\bsrc=/],
];

describe('dashboard markup guard', () => {
  it('reads the dashboard and Insights sources', () => {
    const files = Object.keys(SOURCES);
    expect(files.some((file) => file.endsWith('/WidgetContent.tsx'))).toBe(true);
    expect(files.some((file) => file.endsWith('/insights/GateStatusChart.tsx'))).toBe(true);
    expect(files.some((file) => file.includes('__tests__'))).toBe(false);
  });

  it.each(FORBIDDEN)('uses no %s', (_name, pattern) => {
    const offenders = Object.entries(SOURCES).filter(([, source]) => pattern.test(source)).map(([file]) => file);
    expect(offenders).toEqual([]);
  });
});
