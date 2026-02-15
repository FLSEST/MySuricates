/**
 * Design Tokens
 *
 * All colors are defined as CSS custom properties.
 * Users can override them in Settings > Theme.
 * The theme store persists overrides to localStorage.
 */

export interface ThemeTokens {
  '--color-surface': string;
  '--color-surface-raised': string;
  '--color-surface-overlay': string;
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
  '--color-sidebar': string;
  '--color-sidebar-hover': string;
  '--color-sidebar-active': string;
}

export const lightTheme: ThemeTokens = {
  '--color-surface': '#ffffff',
  '--color-surface-raised': '#f8fafc',
  '--color-surface-overlay': '#ffffff',
  '--color-border': '#e2e8f0',
  '--color-border-strong': '#cbd5e1',
  '--color-text': '#0f172a',
  '--color-text-secondary': '#475569',
  '--color-text-tertiary': '#94a3b8',
  '--color-text-inverse': '#ffffff',
  '--color-accent': '#0891b2',
  '--color-accent-hover': '#0e7490',
  '--color-accent-subtle': '#ecfeff',
  '--color-success': '#059669',
  '--color-success-subtle': '#ecfdf5',
  '--color-warning': '#d97706',
  '--color-warning-subtle': '#fffbeb',
  '--color-danger': '#dc2626',
  '--color-danger-subtle': '#fef2f2',
  '--color-sidebar': '#0f172a',
  '--color-sidebar-hover': '#1e293b',
  '--color-sidebar-active': '#334155',
};

export const darkTheme: ThemeTokens = {
  '--color-surface': '#0f172a',
  '--color-surface-raised': '#1e293b',
  '--color-surface-overlay': '#1e293b',
  '--color-border': '#334155',
  '--color-border-strong': '#475569',
  '--color-text': '#f1f5f9',
  '--color-text-secondary': '#94a3b8',
  '--color-text-tertiary': '#64748b',
  '--color-text-inverse': '#0f172a',
  '--color-accent': '#06b6d4',
  '--color-accent-hover': '#22d3ee',
  '--color-accent-subtle': '#083344',
  '--color-success': '#34d399',
  '--color-success-subtle': '#064e3b',
  '--color-warning': '#fbbf24',
  '--color-warning-subtle': '#451a03',
  '--color-danger': '#f87171',
  '--color-danger-subtle': '#450a0a',
  '--color-sidebar': '#020617',
  '--color-sidebar-hover': '#0f172a',
  '--color-sidebar-active': '#1e293b',
};

export function applyTheme(tokens: ThemeTokens): void {
  const root = document.documentElement;
  for (const [key, value] of Object.entries(tokens)) {
    root.style.setProperty(key, value);
  }
}
