import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { applyTheme, type ThemeTokens } from '../theme/tokens';
import {
  DEFAULT_DARK_THEME_ID,
  DEFAULT_LIGHT_THEME_ID,
  findTheme,
  getTheme,
  type ThemeAppearance,
} from '../theme/themes';

/** Eigene Farbanpassungen, je Theme-ID getrennt. */
export type ThemeOverrides = Record<string, Partial<ThemeTokens>>;

interface PersistedThemeState {
  themeId: string;
  /** Zuletzt genutztes helles/dunkles Theme — Ziel des Hell/Dunkel-Umschalters. */
  lastLightThemeId: string;
  lastDarkThemeId: string;
  overrides: ThemeOverrides;
}

interface ThemeState extends PersistedThemeState {
  setTheme: (themeId: string) => void;
  toggleAppearance: () => void;
  setOverride: (key: keyof ThemeTokens, value: string) => void;
  resetOverrides: () => void;
  apply: () => void;
}

const DEFAULT_STATE: PersistedThemeState = {
  themeId: DEFAULT_DARK_THEME_ID,
  lastLightThemeId: DEFAULT_LIGHT_THEME_ID,
  lastDarkThemeId: DEFAULT_DARK_THEME_ID,
  overrides: {},
};

/**
 * Persist-Version 0 kannte nur `mode: 'light' | 'dark'` und globale Overrides.
 * Der Modus wird auf das entsprechende MySuricates-Theme abgebildet, die Overrides
 * wandern zu diesem Theme.
 */
export function migrateThemeState(persisted: unknown, version: number): PersistedThemeState {
  if (version >= 1) return persisted as PersistedThemeState;
  const old = (persisted ?? {}) as { mode?: unknown; overrides?: unknown };
  const themeId = old.mode === 'light' ? DEFAULT_LIGHT_THEME_ID : DEFAULT_DARK_THEME_ID;
  const oldOverrides = old.overrides && typeof old.overrides === 'object' ? old.overrides : {};
  const overrides = Object.keys(oldOverrides).length > 0 ? { [themeId]: oldOverrides as Partial<ThemeTokens> } : {};
  return { ...DEFAULT_STATE, themeId, overrides };
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      ...DEFAULT_STATE,

      setTheme: (themeId) => {
        const theme = findTheme(themeId);
        if (!theme) return;
        set(theme.appearance === 'light'
          ? { themeId, lastLightThemeId: themeId }
          : { themeId, lastDarkThemeId: themeId });
        get().apply();
      },

      toggleAppearance: () => {
        const { themeId, lastLightThemeId, lastDarkThemeId } = get();
        const toLight = getTheme(themeId).appearance === 'dark';
        const target = findTheme(toLight ? lastLightThemeId : lastDarkThemeId);
        const targetAppearance: ThemeAppearance = toLight ? 'light' : 'dark';
        get().setTheme(target?.appearance === targetAppearance
          ? target.id
          : toLight ? DEFAULT_LIGHT_THEME_ID : DEFAULT_DARK_THEME_ID);
      },

      setOverride: (key, value) => {
        const id = getTheme(get().themeId).id;
        set((state) => ({
          overrides: { ...state.overrides, [id]: { ...state.overrides[id], [key]: value } },
        }));
        get().apply();
      },

      resetOverrides: () => {
        const id = getTheme(get().themeId).id;
        set((state) => {
          const { [id]: _removed, ...rest } = state.overrides;
          return { overrides: rest };
        });
        get().apply();
      },

      apply: () => {
        const { themeId, overrides } = get();
        const theme = getTheme(themeId);
        applyTheme({ ...theme.tokens, ...overrides[theme.id] });
        // Tailwind `dark:` variant, Monaco and xterm follow the theme's appearance
        document.documentElement.classList.toggle('dark', theme.appearance === 'dark');
      },
    }),
    {
      name: 'theme-settings',
      version: 1,
      migrate: migrateThemeState,
      partialize: ({ themeId, lastLightThemeId, lastDarkThemeId, overrides }) => ({
        themeId,
        lastLightThemeId,
        lastDarkThemeId,
        overrides,
      }),
    }
  )
);

/** Hell/Dunkel des aktiven Themes — für Komponenten mit eigenem Theme-System (Monaco, xterm). */
export function useThemeAppearance(): ThemeAppearance {
  return useThemeStore((state) => getTheme(state.themeId).appearance);
}
