/**
 * Design Tokens
 *
 * All colors are defined as CSS custom properties.
 * Users can override them in Settings > Theme.
 * The theme store persists overrides to localStorage.
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

export const lightTheme: ThemeTokens = {
  '--color-surface': '#fff8f3',
  '--color-surface-secondary': '#f7ede2',
  '--color-surface-raised': '#ffffff',
  '--color-surface-overlay': '#ffffff',
  '--color-surface-hover': '#f2e5d7',
  '--color-border': '#f0e0ce',
  '--color-border-strong': '#d4c0a8',
  '--color-text': '#3a2e22',
  '--color-text-secondary': '#6b5a48',
  '--color-text-tertiary': '#9a8a78',
  '--color-text-inverse': '#2c2218',
  '--color-accent': '#f7bd54',
  '--color-accent-hover': '#e5a63a',
  '--color-accent-subtle': '#fff4e0',
  '--color-success': '#4aaa4a',
  '--color-success-subtle': '#f0faf0',
  '--color-warning': '#f7bd54',
  '--color-warning-subtle': '#fff8ec',
  '--color-danger': '#d44848',
  '--color-danger-subtle': '#fef0f0',
  '--color-sidebar': '#f3e8dc',
  '--color-sidebar-border': '#e3d2be',
  '--color-sidebar-hover': '#ebdccb',
  '--color-sidebar-active': '#e2ceb8',
  '--color-sidebar-text': '#34291f',
  '--color-sidebar-text-muted': '#756553',
  '--color-assistant': '#fdf5ec',
  '--color-assistant-raised': '#ffffff',
  '--color-assistant-border': '#e8d5bc',
};

export const darkTheme: ThemeTokens = {
  '--color-surface': '#231e19',
  '--color-surface-secondary': '#2a241e',
  '--color-surface-raised': '#2e2720',
  '--color-surface-overlay': '#332c24',
  '--color-surface-hover': '#3a3128',
  '--color-border': '#4a3f33',
  '--color-border-strong': '#5e5143',
  '--color-text': '#f7f3ee',
  '--color-text-secondary': '#b8aa9a',
  '--color-text-tertiary': '#8c7e6e',
  '--color-text-inverse': '#231e19',
  '--color-accent': '#eba44a',
  '--color-accent-hover': '#f2ba62',
  '--color-accent-subtle': '#3d2e14',
  '--color-success': '#6cc66c',
  '--color-success-subtle': '#1e3320',
  '--color-warning': '#eba44a',
  '--color-warning-subtle': '#3d2e14',
  '--color-danger': '#e07272',
  '--color-danger-subtle': '#3d1f1f',
  '--color-sidebar': '#1a1614',
  '--color-sidebar-border': '#342b23',
  '--color-sidebar-hover': '#252019',
  '--color-sidebar-active': '#332b22',
  '--color-sidebar-text': '#f7f3ee',
  '--color-sidebar-text-muted': '#a6917f',
  '--color-assistant': '#1c1812',
  '--color-assistant-raised': '#252019',
  '--color-assistant-border': '#3d3020',
};

export function applyTheme(tokens: ThemeTokens): void {
  const root = document.documentElement;
  for (const [key, value] of Object.entries(tokens)) {
    root.style.setProperty(key, value);
  }
}
