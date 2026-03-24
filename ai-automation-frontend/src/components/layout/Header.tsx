import { useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Moon, Sun, Globe, Menu, Sparkles } from 'lucide-react';
import { useThemeStore } from '../../stores/themeStore';
import { useI18nStore, type Locale } from '../../i18n';
import { useT } from '../../i18n';
import { useSidebarStore } from '../../stores/sidebarStore';
import { useIsMobile } from '../../hooks/useIsMobile';
import { useAssistantStore } from '../../stores/assistantStore';
import { useAssistantSuggestions } from '../../hooks/useAssistant';

function SuggestionButton() {
  const location = useLocation();
  const { setOpen, setPendingInput } = useAssistantStore();
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);

  const page = location.pathname.replace(/^\//, '').split('/')[0] || 'dashboard';
  const { data: suggestions } = useAssistantSuggestions(page);

  if (!suggestions?.length) return null;

  const items = suggestions.slice(0, 4);

  const handleSelect = (text: string) => {
    setPendingInput(text);
    setOpen(true);
    setDropdownOpen(false);
  };

  return (
    <div className="relative">
      <button
        ref={buttonRef}
        onClick={() => setDropdownOpen((v) => !v)}
        className="p-2 rounded-lg text-text-secondary hover:text-accent hover:bg-accent/10 transition-colors"
        title="Vorschläge für diese Seite"
        aria-label="Vorschläge"
      >
        <Sparkles className="w-4 h-4" />
      </button>

      {dropdownOpen && (
        <>
          {/* Backdrop */}
          <div
            className="fixed inset-0 z-40"
            onClick={() => setDropdownOpen(false)}
          />
          {/* Dropdown */}
          <div className="absolute right-0 top-full mt-1 z-50 w-64 rounded-xl border border-border
                          bg-surface shadow-lg overflow-hidden">
            {items.map((s) => (
              <button
                key={s}
                onClick={() => handleSelect(s)}
                className="w-full text-left px-3 py-2.5 text-sm text-text-secondary
                           hover:bg-surface-raised hover:text-text transition-colors
                           border-b border-border last:border-0"
              >
                {s}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

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
        {/* Suggestions button */}
        <SuggestionButton />

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
