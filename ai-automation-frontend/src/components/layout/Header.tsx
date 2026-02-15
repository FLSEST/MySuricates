import { Moon, Sun, Globe } from 'lucide-react';
import { useThemeStore } from '../../stores/themeStore';
import { useI18nStore, type Locale } from '../../i18n';
import { useT } from '../../i18n';

export function Header() {
  const t = useT();
  const { mode, toggleMode } = useThemeStore();
  const { locale, setLocale } = useI18nStore();

  return (
    <header className="h-14 border-b border-border flex items-center justify-end px-6 gap-2">
      {/* Language toggle */}
      <button
        onClick={() => setLocale(locale === 'en' ? 'de' : ('en' as Locale))}
        className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium
                   text-text-secondary hover:text-text hover:bg-surface-raised transition-colors"
        aria-label={t.settings.language}
      >
        <Globe className="w-3.5 h-3.5" />
        {locale.toUpperCase()}
      </button>

      {/* Theme toggle */}
      <button
        onClick={toggleMode}
        className="p-2 rounded-lg text-text-secondary hover:text-text hover:bg-surface-raised transition-colors"
        title={mode === 'dark' ? t.settings.switchToLight : t.settings.switchToDark}
        aria-label={mode === 'dark' ? t.settings.switchToLight : t.settings.switchToDark}
      >
        {mode === 'dark' ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
      </button>
    </header>
  );
}
