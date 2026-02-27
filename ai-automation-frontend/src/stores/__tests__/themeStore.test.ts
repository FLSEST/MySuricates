import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useThemeStore } from '../themeStore';

// Mock the theme tokens module
vi.mock('../../theme/tokens', () => ({
  lightTheme: { bg: '#fff', text: '#000' },
  darkTheme: { bg: '#000', text: '#fff' },
  applyTheme: vi.fn(),
}));

describe('themeStore', () => {
  beforeEach(() => {
    useThemeStore.setState({ mode: 'dark', overrides: {} });
    document.documentElement.classList.remove('dark');
  });

  it('has dark as default mode', () => {
    expect(useThemeStore.getState().mode).toBe('dark');
  });

  it('setMode changes to light', () => {
    useThemeStore.getState().setMode('light');
    expect(useThemeStore.getState().mode).toBe('light');
  });

  it('setMode changes to dark', () => {
    useThemeStore.getState().setMode('light');
    useThemeStore.getState().setMode('dark');
    expect(useThemeStore.getState().mode).toBe('dark');
  });

  it('toggleMode switches from dark to light', () => {
    useThemeStore.getState().toggleMode();
    expect(useThemeStore.getState().mode).toBe('light');
  });

  it('toggleMode switches from light to dark', () => {
    useThemeStore.setState({ mode: 'light' });
    useThemeStore.getState().toggleMode();
    expect(useThemeStore.getState().mode).toBe('dark');
  });

  it('setOverride stores a theme override', () => {
    useThemeStore.getState().setOverride('bg' as any, '#123');
    expect(useThemeStore.getState().overrides).toEqual({ bg: '#123' });
  });

  it('setOverride accumulates multiple overrides', () => {
    useThemeStore.getState().setOverride('bg' as any, '#123');
    useThemeStore.getState().setOverride('text' as any, '#456');
    expect(useThemeStore.getState().overrides).toEqual({ bg: '#123', text: '#456' });
  });

  it('resetOverrides clears all overrides', () => {
    useThemeStore.getState().setOverride('bg' as any, '#123');
    useThemeStore.getState().resetOverrides();
    expect(useThemeStore.getState().overrides).toEqual({});
  });

  it('apply adds dark class in dark mode', () => {
    useThemeStore.getState().setMode('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });

  it('apply removes dark class in light mode', () => {
    document.documentElement.classList.add('dark');
    useThemeStore.getState().setMode('light');
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });
});
