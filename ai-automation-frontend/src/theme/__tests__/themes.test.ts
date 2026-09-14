import { describe, it, expect } from 'vitest';
import {
  THEMES,
  DEFAULT_DARK_THEME_ID,
  DEFAULT_LIGHT_THEME_ID,
  findTheme,
  getTheme,
} from '../themes';
import type { ThemeTokens } from '../tokens';

type Key = keyof ThemeTokens;

// WCAG 2.x relative luminance / contrast ratio
function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const v = parseInt(hex.slice(i, i + 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

const SURFACES: Key[] = ['--color-surface', '--color-surface-secondary', '--color-surface-raised'];
const STATUS = ['accent', 'success', 'warning', 'danger'] as const;

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

  it('keeps accent and status colors readable as text', () => {
    for (const name of STATUS) {
      for (const bg of SURFACES) expectContrast(`--color-${name}`, bg, 4.5);
      expectContrast(`--color-${name}`, `--color-${name}-subtle`, 4.5);
    }
    expectContrast('--color-accent-hover', '--color-surface', 4.5);
  });

  it('keeps inverse text readable on accent buttons', () => {
    expectContrast('--color-text-inverse', '--color-accent', 4.5);
    expectContrast('--color-text-inverse', '--color-accent-hover', 4.5);
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

  it('draws visible borders', () => {
    for (const bg of ['--color-surface', '--color-surface-raised'] as Key[]) {
      expectContrast('--color-border', bg, 1.3);
      expectContrast('--color-border-strong', bg, 1.6);
    }
  });
});
