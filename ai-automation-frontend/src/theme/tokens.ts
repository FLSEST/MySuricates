/**
 * Design Tokens
 *
 * All colors are defined as CSS custom properties.
 * The concrete values per theme live in themes.ts; users can pick a theme and
 * override single colors per theme in Settings > Appearance.
 * The theme store persists the choice and the overrides to localStorage.
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
  '--color-accent-hover': string;
  '--color-accent-subtle': string;
  '--color-success': string;
  '--color-success-subtle': string;
  '--color-warning': string;
  '--color-warning-subtle': string;
  '--color-danger': string;
  '--color-danger-subtle': string;
  '--color-ai': string;
  '--color-ai-subtle': string;
  '--color-human': string;
  '--color-human-subtle': string;
  '--color-info': string;
  '--color-info-subtle': string;
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
  const root = document.documentElement;
  for (const [key, value] of Object.entries(tokens)) {
    root.style.setProperty(key, value);
  }
}
