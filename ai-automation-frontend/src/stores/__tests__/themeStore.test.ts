import { describe, it, expect, beforeEach } from 'vitest';
import { useThemeStore, migrateThemeState } from '../themeStore';
import { getTheme } from '../../theme/themes';

const root = document.documentElement;
const cssVar = (name: string) => root.style.getPropertyValue(name);

describe('themeStore', () => {
  beforeEach(() => {
    useThemeStore.setState({
      themeId: 'suricate-dark',
      lastLightThemeId: 'suricate-light',
      lastDarkThemeId: 'suricate-dark',
      overrides: {},
    });
    root.classList.remove('dark');
    root.removeAttribute('style');
  });

  it('has MySuricates dark as default theme', () => {
    expect(useThemeStore.getState().themeId).toBe('suricate-dark');
  });

  it('setTheme applies the tokens of the chosen theme', () => {
    useThemeStore.getState().setTheme('nord');
    expect(useThemeStore.getState().themeId).toBe('nord');
    expect(cssVar('--color-surface')).toBe(getTheme('nord').tokens['--color-surface']);
  });

  it('setTheme sets the dark class according to the theme appearance', () => {
    useThemeStore.getState().setTheme('dracula');
    expect(root.classList.contains('dark')).toBe(true);
    useThemeStore.getState().setTheme('catppuccin-latte');
    expect(root.classList.contains('dark')).toBe(false);
  });

  it('setTheme ignores unknown theme ids', () => {
    useThemeStore.getState().setTheme('nope');
    expect(useThemeStore.getState().themeId).toBe('suricate-dark');
  });

  it('setTheme remembers the last theme per appearance', () => {
    useThemeStore.getState().setTheme('nord-light');
    useThemeStore.getState().setTheme('dracula');
    const { lastLightThemeId, lastDarkThemeId } = useThemeStore.getState();
    expect(lastLightThemeId).toBe('nord-light');
    expect(lastDarkThemeId).toBe('dracula');
  });

  it('toggleAppearance switches to the last used theme of the other appearance', () => {
    useThemeStore.getState().setTheme('gruvbox-light');
    useThemeStore.getState().setTheme('nord');
    useThemeStore.getState().toggleAppearance();
    expect(useThemeStore.getState().themeId).toBe('gruvbox-light');
    useThemeStore.getState().toggleAppearance();
    expect(useThemeStore.getState().themeId).toBe('nord');
  });

  it('toggleAppearance falls back to the default theme when the remembered one is unknown', () => {
    useThemeStore.setState({ lastLightThemeId: 'removed-theme' });
    useThemeStore.getState().toggleAppearance();
    expect(useThemeStore.getState().themeId).toBe('suricate-light');
  });

  it('setOverride only affects the active theme', () => {
    useThemeStore.getState().setTheme('nord');
    useThemeStore.getState().setOverride('--color-accent', '#123456');
    expect(useThemeStore.getState().overrides).toEqual({ nord: { '--color-accent': '#123456' } });
    expect(cssVar('--color-accent')).toBe('#123456');

    useThemeStore.getState().setTheme('dracula');
    expect(cssVar('--color-accent')).toBe(getTheme('dracula').tokens['--color-accent']);
  });

  it('resetOverrides clears only the overrides of the active theme', () => {
    useThemeStore.getState().setTheme('nord');
    useThemeStore.getState().setOverride('--color-accent', '#123456');
    useThemeStore.getState().setTheme('dracula');
    useThemeStore.getState().setOverride('--color-text', '#abcdef');
    useThemeStore.getState().resetOverrides();
    expect(useThemeStore.getState().overrides).toEqual({ nord: { '--color-accent': '#123456' } });
    expect(cssVar('--color-text')).toBe(getTheme('dracula').tokens['--color-text']);
  });
});

describe('migrateThemeState', () => {
  it('maps the old dark mode and moves its overrides to the MySuricates dark theme', () => {
    const migrated = migrateThemeState({ mode: 'dark', overrides: { '--color-accent': '#ff0000' } }, 0);
    expect(migrated.themeId).toBe('suricate-dark');
    expect(migrated.overrides).toEqual({ 'suricate-dark': { '--color-accent': '#ff0000' } });
  });

  it('maps the old light mode', () => {
    const migrated = migrateThemeState({ mode: 'light', overrides: {} }, 0);
    expect(migrated).toEqual({
      themeId: 'suricate-light',
      lastLightThemeId: 'suricate-light',
      lastDarkThemeId: 'suricate-dark',
      overrides: {},
    });
  });

  it('falls back to defaults for unreadable state', () => {
    expect(migrateThemeState(null, 0).themeId).toBe('suricate-dark');
  });
});
