import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { lightTheme, darkTheme, applyTheme, type ThemeTokens } from '../theme/tokens';

export type ThemeMode = 'light' | 'dark';

interface ThemeState {
  mode: ThemeMode;
  overrides: Partial<ThemeTokens>;
  setMode: (mode: ThemeMode) => void;
  toggleMode: () => void;
  setOverride: (key: keyof ThemeTokens, value: string) => void;
  resetOverrides: () => void;
  apply: () => void;
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      mode: 'dark',
      overrides: {},

      setMode: (mode) => {
        set({ mode });
        get().apply();
      },

      toggleMode: () => {
        const next = get().mode === 'light' ? 'dark' : 'light';
        set({ mode: next });
        get().apply();
      },

      setOverride: (key, value) => {
        set((state) => ({
          overrides: { ...state.overrides, [key]: value },
        }));
        get().apply();
      },

      resetOverrides: () => {
        set({ overrides: {} });
        get().apply();
      },

      apply: () => {
        const { mode, overrides } = get();
        const base = mode === 'light' ? lightTheme : darkTheme;
        const merged = { ...base, ...overrides };
        applyTheme(merged);

        // Toggle Tailwind dark class
        if (mode === 'dark') {
          document.documentElement.classList.add('dark');
        } else {
          document.documentElement.classList.remove('dark');
        }
      },
    }),
    { name: 'theme-settings' }
  )
);
