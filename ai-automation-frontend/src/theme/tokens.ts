/**
 * Design Tokens
 *
 * All colors are defined as CSS custom properties.
 * The concrete values per theme live in themes.ts; users can pick a theme and
 * override single colors per theme in Settings > Appearance.
 * The theme store persists the choice and the overrides to localStorage.
 *
 * Accent, status and role colors come in two variants: `--color-<name>` is the
 * fill (bars, dots, charts, tints; the palette's own tone) and
 * `--color-<name>-text` is the readable text variant. index.css maps the text
 * variant onto the `text-<name>` utilities, so components keep writing
 * `text-success` / `bg-success`. `--color-accent-emphasis` backs `bg-accent`
 * (solid buttons carrying `text-text-inverse`). Danger and success fills that carry
 * text or an icon use `bg-danger-emphasis` / `bg-success-emphasis` — the plain
 * `bg-danger` / `bg-success` stay the palette tone for dots, bars and charts.
 */

export interface ThemeTokens {
  '--color-surface': string;
  '--color-surface-secondary': string;
  '--color-surface-raised': string;
  '--color-surface-overlay': string;
  '--color-surface-hover': string;
  '--color-border': string;
  '--color-border-strong': string;
  '--color-text': string;
  '--color-text-secondary': string;
  '--color-text-tertiary': string;
  '--color-text-inverse': string;
  '--color-accent': string;
  '--color-accent-text': string;
  '--color-accent-hover': string;
  '--color-accent-emphasis': string;
  '--color-accent-subtle': string;
  '--color-success': string;
  '--color-success-text': string;
  '--color-success-subtle': string;
  '--color-success-emphasis': string;
  '--color-warning': string;
  '--color-warning-text': string;
  '--color-warning-subtle': string;
  '--color-danger': string;
  '--color-danger-text': string;
  '--color-danger-subtle': string;
  '--color-danger-emphasis': string;
  '--color-ai': string;
  '--color-ai-text': string;
  '--color-ai-subtle': string;
  '--color-human': string;
  '--color-human-text': string;
  '--color-human-subtle': string;
  '--color-info': string;
  '--color-info-text': string;
  '--color-info-subtle': string;
  '--color-chart-1': string;
  '--color-chart-2': string;
  '--color-chart-3': string;
  '--color-chart-4': string;
  '--color-chart-5': string;
  '--color-chart-6': string;
  '--color-chart-7': string;
  '--color-chart-8': string;
  '--color-card-border': string;
  '--color-sidebar': string;
  '--color-sidebar-border': string;
  '--color-sidebar-hover': string;
  '--color-sidebar-active': string;
  '--color-sidebar-text': string;
  '--color-sidebar-text-muted': string;
  '--color-assistant': string;
  '--color-assistant-raised': string;
  '--color-assistant-border': string;
}

export function applyTheme(tokens: ThemeTokens): void {
  setCssVars(tokens as unknown as Record<string, string>);
}

/** Set arbitrary custom properties on the root element (e.g. remapped Tailwind palette colors). */
export function setCssVars(vars: Readonly<Record<string, string>>): void {
  const root = document.documentElement;
  for (const [key, value] of Object.entries(vars)) {
    root.style.setProperty(key, value);
  }
}

export function removeCssVars(names: readonly string[]): void {
  const root = document.documentElement;
  for (const name of names) root.style.removeProperty(name);
}
