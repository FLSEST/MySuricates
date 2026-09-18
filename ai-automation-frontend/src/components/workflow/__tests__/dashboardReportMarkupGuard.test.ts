import { describe, it, expect } from 'vitest';

/**
 * The block editor now puts two kinds of externally-influenced text on screen: config values a
 * workflow wrote, and an AI proposal (task 9). Both are rendered as React text nodes only — the same
 * rule the dashboard widgets follow (spec E3), enforced there by dashboardMarkupGuard.test.ts. This
 * is that guard for the editor's report files, which live outside the dashboard folder.
 */
const SOURCES = import.meta.glob<string>(
  ['../dashboardReport.ts', '../DashboardReport*.tsx', '../../../hooks/useDashboardReportAssist.ts'],
  { query: '?raw', import: 'default', eager: true },
);

const FORBIDDEN: [string, RegExp][] = [
  ['dangerouslySetInnerHTML', /dangerouslySetInnerHTML/],
  ['innerHTML', /\.innerHTML\b/],
  ['outerHTML', /\.outerHTML\b/],
  ['insertAdjacentHTML', /\.insertAdjacentHTML\b/],
  ['Markdown renderer', /from ['"][^'"]*(markdown|marked|snarkdown)/i],
  ['img element', /<img\b/],
  ['iframe element', /<iframe\b/],
  ['href attribute', /\bhref\s*=/],
  ['src attribute', /\bsrc\s*=/],
  ['xlinkHref attribute', /\bxlink(Href|:href)\s*=/i],
  ['srcSet attribute', /\bsrc[Ss]et\s*=/],
  ['setAttribute for a URL', /\.setAttribute\s*\(\s*['"](href|src|srcset|xlink:href)/i],
  ['window.open', /\bwindow\.open\s*\(/],
];

describe('dashboard-report editor markup guard', () => {
  it('reads the report sources and is not vacuous', () => {
    const files = Object.keys(SOURCES);
    expect(files.some((file) => file.endsWith('/dashboardReport.ts'))).toBe(true);
    expect(files.some((file) => file.endsWith('/DashboardReportFields.tsx'))).toBe(true);
    for (const [file, source] of Object.entries(SOURCES)) {
      expect(typeof source, file).toBe('string');
      expect(source.length, file).toBeGreaterThan(0);
    }
  });

  it.each(FORBIDDEN)('uses no %s', (_name, pattern) => {
    const offenders = Object.entries(SOURCES).filter(([, source]) => pattern.test(source)).map(([file]) => file);
    expect(offenders).toEqual([]);
  });
});
