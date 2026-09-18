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
  // A widget must never open anything from content either — the run route is built from ids that
  // come from the endpoint, and the widget contract holds no id and no URL at all.
  ['window.open', /\bwindow\.open\s*\(/],
];

describe('dashboard markup guard', () => {
  it('reads the dashboard and Insights sources', () => {
    const files = Object.keys(SOURCES);
    expect(files.some((file) => file.endsWith('/WidgetContent.tsx'))).toBe(true);
    expect(files.some((file) => file.endsWith('/insights/GateStatusChart.tsx'))).toBe(true);
    expect(files.some((file) => file.includes('__tests__'))).toBe(false);
  });

  // The files that put workflow output on screen (spec E3, phase 5). Named one by one so a rename or
  // a move can never quietly drop them out of the guard.
  it.each([
    '/WorkflowResultWidget.tsx',
    '/WorkflowStatusWidget.tsx',
    '/WorkflowWidgetStates.tsx',
    '/WorkflowWidgetProvider.tsx',
    '/workflowWidgetContract.ts',
    '/workflowWidgetContext.ts',
  ])('covers %s', (file) => {
    expect(Object.keys(SOURCES).some((path) => path.endsWith(file))).toBe(true);
  });

  it('is not vacuous: every source is non-empty and a known file has known content', () => {
    const entries = Object.entries(SOURCES);
    expect(entries.length).toBeGreaterThan(0);
    for (const [file, source] of entries) {
      expect(typeof source, file).toBe('string');
      expect(source.length, file).toBeGreaterThan(0);
    }
    const widgets = SOURCES[Object.keys(SOURCES).find((file) => file.endsWith('/InsightsWidgets.tsx'))!];
    expect(widgets).toContain('WidgetSkeleton');
  });

  it.each(FORBIDDEN)('uses no %s', (_name, pattern) => {
    const offenders = Object.entries(SOURCES).filter(([, source]) => pattern.test(source)).map(([file]) => file);
    expect(offenders).toEqual([]);
  });
});
