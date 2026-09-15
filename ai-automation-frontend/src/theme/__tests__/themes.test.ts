import { describe, it, expect } from 'vitest';
import {
  THEMES,
  DEFAULT_DARK_THEME_ID,
  DEFAULT_LIGHT_THEME_ID,
  findTheme,
  getTheme,
} from '../themes';
import { contrast, oklabDistance, toOklch } from '../color';
import { TAILWIND_PALETTE_VARS, tailwindPaletteVars } from '../derive';
import type { ThemeTokens } from '../tokens';

type Key = keyof ThemeTokens;

const SURFACES: Key[] = ['--color-surface', '--color-surface-secondary', '--color-surface-raised'];
const STATUS = ['accent', 'success', 'warning', 'danger', 'ai', 'human', 'info'] as const;
const CHARTS = [1, 2, 3, 4, 5, 6, 7, 8].map((i) => `--color-chart-${i}` as Key);

describe('theme registry', () => {
  it('has unique ids and names', () => {
    expect(new Set(THEMES.map((t) => t.id)).size).toBe(THEMES.length);
    expect(new Set(THEMES.map((t) => t.name)).size).toBe(THEMES.length);
  });

  it('offers both light and dark themes', () => {
    expect(THEMES.some((t) => t.appearance === 'light')).toBe(true);
    expect(THEMES.some((t) => t.appearance === 'dark')).toBe(true);
  });

  it('defaults point to themes with the matching appearance', () => {
    expect(findTheme(DEFAULT_DARK_THEME_ID)?.appearance).toBe('dark');
    expect(findTheme(DEFAULT_LIGHT_THEME_ID)?.appearance).toBe('light');
  });

  it('getTheme falls back to the default dark theme for unknown ids', () => {
    expect(findTheme('does-not-exist')).toBeUndefined();
    expect(getTheme('does-not-exist').id).toBe(DEFAULT_DARK_THEME_ID);
  });

  it('keeps the stock Tailwind palette for the MySuricates themes only', () => {
    expect(getTheme('suricate-dark').hues).toBeUndefined();
    expect(getTheme('suricate-light').hues).toBeUndefined();
    expect(THEMES.filter((t) => !t.id.startsWith('suricate-')).every((t) => t.hues)).toBe(true);
  });
});

describe.each(THEMES)('theme $id', (theme) => {
  const tk = theme.tokens;
  const expectContrast = (fg: Key, bg: Key, min: number) =>
    expect(contrast(tk[fg], tk[bg]), `${fg} on ${bg}`).toBeGreaterThanOrEqual(min);

  it('defines every token as a 6-digit hex color (required by <input type="color">)', () => {
    const keys = Object.keys(getTheme(DEFAULT_DARK_THEME_ID).tokens);
    expect(Object.keys(tk).sort()).toEqual([...keys].sort());
    for (const value of Object.values(tk)) expect(value).toMatch(/^#[0-9a-f]{6}$/i);
  });

  it('keeps body text readable on every surface', () => {
    for (const bg of [...SURFACES, '--color-surface-overlay'] as Key[]) {
      expectContrast('--color-text', bg, 4.5);
      expectContrast('--color-text-secondary', bg, 4.5);
    }
    expectContrast('--color-text', '--color-surface-hover', 4.5);
    for (const bg of SURFACES) expectContrast('--color-text-tertiary', bg, 3);
  });

  it('keeps the text variants of accent, status and role colors readable', () => {
    for (const name of STATUS) {
      for (const bg of SURFACES) expectContrast(`--color-${name}-text`, bg, 4.5);
      expectContrast(`--color-${name}-text`, `--color-${name}-subtle`, 4.5);
    }
    expectContrast('--color-accent-hover', '--color-surface', 4.5);
  });

  it('draws accent, status and role fills visibly (graphics ≥ 3:1)', () => {
    for (const name of STATUS) {
      for (const bg of SURFACES) expectContrast(`--color-${name}`, bg, 3);
    }
  });

  it('keeps inverse text readable on solid accent buttons', () => {
    expectContrast('--color-text-inverse', '--color-accent-emphasis', 4.5);
    expectContrast('--color-text-inverse', '--color-accent-hover', 4.5);
  });

  it('draws visible, distinguishable chart series', () => {
    for (const chart of CHARTS) for (const bg of SURFACES) expectContrast(chart, bg, 3);
    const firstFive = CHARTS.slice(0, 5).map((k) => tk[k]);
    for (const [i, a] of firstFive.entries()) {
      expect(oklabDistance(a, tk['--color-accent']), `chart-${i + 1} vs accent`).toBeGreaterThan(5);
      for (const [j, b] of firstFive.entries()) {
        if (j > i) expect(oklabDistance(a, b), `chart-${i + 1} vs chart-${j + 1}`).toBeGreaterThan(5);
      }
    }
  });

  it('keeps sidebar and assistant text readable', () => {
    for (const bg of ['--color-sidebar', '--color-sidebar-hover', '--color-sidebar-active'] as Key[]) {
      expectContrast('--color-sidebar-text', bg, 4.5);
    }
    expectContrast('--color-sidebar-text-muted', '--color-sidebar', 4.5);
    expectContrast('--color-sidebar-text-muted', '--color-sidebar-hover', 4.5);
    for (const bg of ['--color-assistant', '--color-assistant-raised'] as Key[]) {
      expectContrast('--color-text', bg, 4.5);
      expectContrast('--color-text-secondary', bg, 4.5);
    }
  });

  it('draws visible borders; card borders are either the border or invisible', () => {
    for (const bg of ['--color-surface', '--color-surface-raised'] as Key[]) {
      expectContrast('--color-border', bg, 1.3);
      expectContrast('--color-border-strong', bg, 1.6);
    }
    expect([tk['--color-border'], tk['--color-surface-raised']]).toContain(tk['--color-card-border']);
  });
});

describe.each(THEMES.filter((t) => t.hues))('palette theme $id', (theme) => {
  const tk = theme.tokens;
  const dark = theme.appearance === 'dark';
  const cards = [tk['--color-surface'], tk['--color-surface-raised']];

  it('uses recognizable hues for success, warning and danger', () => {
    const hue = (k: Key) => toOklch(tk[k]).h;
    expect(hue('--color-success')).toBeGreaterThanOrEqual(120);
    expect(hue('--color-success')).toBeLessThanOrEqual(163);
    expect(hue('--color-warning')).toBeGreaterThanOrEqual(40);
    expect(hue('--color-warning')).toBeLessThanOrEqual(105);
    const danger = hue('--color-danger');
    expect(danger >= 340 || danger <= 45, `danger hue ${danger}`).toBe(true);
  });

  it('remaps every Tailwind palette class onto readable palette colors', () => {
    const vars = tailwindPaletteVars(theme);
    expect(Object.keys(vars).sort()).toEqual([...TAILWIND_PALETTE_VARS].sort());
    const textShades = dark ? [100, 200, 300, 400] : [600, 700, 800, 900];
    for (const family of ['red', 'amber', 'green', 'emerald', 'teal', 'blue', 'violet', 'purple', 'pink']) {
      for (const shade of textShades) {
        for (const bg of cards) {
          expect(contrast(vars[`--color-${family}-${shade}`]!, bg), `${family}-${shade}`).toBeGreaterThanOrEqual(4.5);
        }
      }
      for (const bg of cards) {
        expect(contrast(vars[`--color-${family}-500`]!, bg), `${family}-500`).toBeGreaterThanOrEqual(3);
      }
    }
  });
});
