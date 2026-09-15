/**
 * Theme-Registry
 *
 * MySuricates Dark/Light sind das eigene Design (feste Werte). Alle weiteren Themes
 * werden aus der offiziellen Palette und den Rollen der Theme-Autoren abgeleitet
 * (palettes.ts → derive.ts). Die Mindestkontraste und die Farbregeln sichert
 * src/theme/__tests__/themes.test.ts für jedes Theme ab.
 */
import { buildPaletteTheme, type HueFamily } from './derive';
import { PALETTE_THEMES } from './palettes';
import type { ThemeTokens } from './tokens';

export type ThemeAppearance = 'light' | 'dark';

export interface ThemeDefinition {
  id: string;
  name: string;
  appearance: ThemeAppearance;
  tokens: ThemeTokens;
  /** Palette hues; when set, Tailwind's palette classes (blue-500 …) are remapped onto them. */
  hues?: Record<HueFamily, string>;
}

export const DEFAULT_DARK_THEME_ID = 'suricate-dark';
export const DEFAULT_LIGHT_THEME_ID = 'suricate-light';

/** Tailwind's stock series colors — MySuricates keeps them, minus amber (that is its accent). */
const TAILWIND_CHART_DARK = ['#3b82f6', '#10b981', '#8b5cf6', '#ef4444', '#ec4899', '#14b8a6', '#6366f1', '#f97316'];
const TAILWIND_CHART_LIGHT = ['#3b82f6', '#0d9a6c', '#8b5cf6', '#ef4444', '#ec4698', '#119889', '#6366f1', '#e05f06'];

function chartTokens(colors: string[]): Pick<ThemeTokens, `--color-chart-${1 | 2 | 3 | 4 | 5 | 6 | 7 | 8}`> {
  return Object.fromEntries(colors.map((c, i) => [`--color-chart-${i + 1}`, c])) as Pick<
    ThemeTokens,
    `--color-chart-${1 | 2 | 3 | 4 | 5 | 6 | 7 | 8}`
  >;
}

const suricateDark: ThemeDefinition = {
  id: 'suricate-dark',
  name: 'MySuricates Dark',
  appearance: 'dark',
  tokens: {
    '--color-surface': '#231e19',
    '--color-surface-secondary': '#2a241e',
    '--color-surface-raised': '#2e2720',
    '--color-surface-overlay': '#332c24',
    '--color-surface-hover': '#3a3128',
    '--color-border': '#4a3f33',
    '--color-border-strong': '#5e5143',
    '--color-card-border': '#4a3f33',
    '--color-text': '#f7f3ee',
    '--color-text-secondary': '#b8aa9a',
    '--color-text-tertiary': '#8c7e6e',
    '--color-text-inverse': '#231e19',
    '--color-accent': '#eba44a',
    '--color-accent-text': '#eba44a',
    '--color-accent-hover': '#f2ba62',
    '--color-accent-emphasis': '#eba44a',
    '--color-accent-subtle': '#3d2e14',
    '--color-success': '#6cc66c',
    '--color-success-text': '#6cc66c',
    '--color-success-subtle': '#1e3320',
    '--color-warning': '#eba44a',
    '--color-warning-text': '#eba44a',
    '--color-warning-subtle': '#3d2e14',
    '--color-danger': '#e07272',
    '--color-danger-text': '#e07272',
    '--color-danger-subtle': '#3d1f1f',
    '--color-ai': '#b58ee8',
    '--color-ai-text': '#b58ee8',
    '--color-ai-subtle': '#362d34',
    '--color-human': '#56c2b0',
    '--color-human-text': '#56c2b0',
    '--color-human-subtle': '#2a332d',
    '--color-info': '#6aa6e0',
    '--color-info-text': '#6aa6e0',
    '--color-info-subtle': '#2c3033',
    ...chartTokens(TAILWIND_CHART_DARK),
    '--color-sidebar': '#1a1614',
    '--color-sidebar-border': '#342b23',
    '--color-sidebar-hover': '#252019',
    '--color-sidebar-active': '#332b22',
    '--color-sidebar-text': '#f7f3ee',
    '--color-sidebar-text-muted': '#a6917f',
    '--color-assistant': '#1c1812',
    '--color-assistant-raised': '#252019',
    '--color-assistant-border': '#3d3020',
  },
};

const suricateLight: ThemeDefinition = {
  id: 'suricate-light',
  name: 'MySuricates Light',
  appearance: 'light',
  tokens: {
    '--color-surface': '#fbf6f0',
    '--color-surface-secondary': '#f4ebe0',
    '--color-surface-raised': '#ffffff',
    '--color-surface-overlay': '#ffffff',
    '--color-surface-hover': '#efe3d5',
    '--color-border': '#e2d0bc',
    '--color-border-strong': '#c4ad94',
    '--color-card-border': '#e2d0bc',
    '--color-text': '#2f251b',
    '--color-text-secondary': '#5c4c3c',
    '--color-text-tertiary': '#857361',
    '--color-text-inverse': '#ffffff',
    // Flächen in lebendigem Amber (≥ 3:1), Text in der dunklen, lesbaren Variante
    '--color-accent': '#bb7816',
    '--color-accent-text': '#a54c08',
    '--color-accent-hover': '#833c06',
    '--color-accent-emphasis': '#a54c08',
    '--color-accent-subtle': '#fcebd0',
    '--color-success': '#3b9a49',
    '--color-success-text': '#2c742e',
    '--color-success-subtle': '#e2e6d9',
    '--color-warning': '#b57c16',
    '--color-warning-text': '#925906',
    '--color-warning-subtle': '#eee3d4',
    '--color-danger': '#d44848',
    '--color-danger-text': '#b83729',
    '--color-danger-subtle': '#f3dfd8',
    '--color-ai': '#9a6ad6',
    '--color-ai-text': '#7443b5',
    '--color-ai-subtle': '#e9dfe8',
    '--color-human': '#1e9888',
    '--color-human-text': '#157066',
    '--color-human-subtle': '#dde5de',
    '--color-info': '#3b82d6',
    '--color-info-text': '#2a64a8',
    '--color-info-subtle': '#e0e3e7',
    ...chartTokens(TAILWIND_CHART_LIGHT),
    '--color-sidebar': '#f1e6d8',
    '--color-sidebar-border': '#e0cdb8',
    '--color-sidebar-hover': '#e8d8c5',
    '--color-sidebar-active': '#dfc9b0',
    '--color-sidebar-text': '#2f251b',
    '--color-sidebar-text-muted': '#66553f',
    '--color-assistant': '#faf2e8',
    '--color-assistant-raised': '#ffffff',
    '--color-assistant-border': '#e2d0bc',
  },
};

export const THEMES: readonly ThemeDefinition[] = [suricateDark, suricateLight, ...PALETTE_THEMES.map(buildPaletteTheme)];

export function findTheme(id: string): ThemeDefinition | undefined {
  return THEMES.find((theme) => theme.id === id);
}

/** Liefert das Theme zur ID, bei unbekannter ID das Standard-Dunkel-Theme. */
export function getTheme(id: string): ThemeDefinition {
  return findTheme(id) ?? findTheme(DEFAULT_DARK_THEME_ID)!;
}
