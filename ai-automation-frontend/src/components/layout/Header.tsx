import { Moon, Sun, Globe, Menu } from 'lucide-react';
import { useThemeStore } from '../../stores/themeStore';
import { useI18nStore, type Locale } from '../../i18n';
import { useT } from '../../i18n';
import { useSidebarStore } from '../../stores/sidebarStore';
import { useIsMobile } from '../../hooks/useIsMobile';

export function Header() {
  const t = useT();
  const { mode, toggleMode } = useThemeStore();
  const { locale, setLocale } = useI18nStore();
  const setMobileOpen = useSidebarStore((s) => s.setMobileOpen);
  const isMobile = useIsMobile();

  return (
    <header className={`h-14 border-b border-border flex items-center px-6 gap-2 ${isMobile ? 'justify-between' : 'justify-end'}`}>
      {/* Hamburger menu (mobile only) */}
      {isMobile && (
        <button
          onClick={() => setMobileOpen(true)}
          className="p-2 rounded-lg text-text-secondary hover:text-text hover:bg-surface-raised transition-colors"
          aria-label={t.common.menu}
        >
          <Menu className="w-5 h-5" />
        </button>
      )}

      <div className="flex items-center gap-2">
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
      </div>
    </header>
  );
}
