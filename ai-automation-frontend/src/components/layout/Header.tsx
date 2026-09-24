import { useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Moon, Sun, Globe, Menu, Sparkles, Search } from 'lucide-react';
import { useThemeStore, useThemeAppearance } from '../../stores/themeStore';
import { useI18nStore, type Locale } from '../../i18n';
import { useT } from '../../i18n';
import { useSidebarStore } from '../../stores/sidebarStore';
import { useIsMobile } from '../../hooks/useIsMobile';
import { useAssistantStore } from '../../stores/assistantStore';
import { useAssistantSuggestions } from '../../hooks/useAssistant';
import { resolveContextId } from '../palette/resolveContext';
import { usePendingInputs } from '../../hooks/usePendingInputs';
import { PendingPulse } from './PendingInputNavItem';

function isMac(): boolean {
  if (typeof navigator === 'undefined') return false;
  return /Mac|iPhone|iPad|iPod/.test(navigator.platform || navigator.userAgent || '');
}

function PaletteTrigger({ compact, label }: { compact: boolean; label: string }) {
  const hint = isMac() ? '⌘K' : 'Ctrl+K';
  const onClick = () => window.dispatchEvent(new CustomEvent('mysuri:openPalette'));
  if (compact) {
    return (
      <button
        onClick={onClick}
        className="p-2 rounded-lg text-text-secondary hover:text-accent hover:bg-accent/10 transition-colors"
        title={`${label} (${hint})`}
        aria-label={label}
      >
        <Search className="w-4 h-4" />
      </button>
    );
  }
  return (
    <button
      onClick={onClick}
      className="flex items-center gap-2 h-8 px-3 rounded-lg border border-border bg-surface-raised/40
                 text-text-tertiary hover:text-text hover:border-accent/40 transition-colors text-sm min-w-[180px]"
      aria-label={label}
    >
      <Search className="w-3.5 h-3.5 shrink-0" />
      <span className="flex-1 text-left truncate">{label}</span>
      <span className="text-[10px] font-medium border border-border rounded-sm px-1 py-0.5 shrink-0">{hint}</span>
    </button>
  );
}

function SuggestionButton() {
  const t = useT();
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
        title={t.assistant.pageSuggestionsHint}
        aria-label={t.assistant.pageSuggestions}
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
  const appearance = useThemeAppearance();
  const toggleAppearance = useThemeStore((s) => s.toggleAppearance);
  const { locale, setLocale } = useI18nStore();
  const setMobileOpen = useSidebarStore((s) => s.setMobileOpen);
  const isMobile = useIsMobile();
  const { data: pendingInputs } = usePendingInputs();
  const pendingCount = pendingInputs?.length ?? 0;
  const location = useLocation();
  const contextId = resolveContextId(location.pathname);
  const triggerLabel = t.commandPalette.contexts[contextId].triggerLabel;

  return (
    <header className={`h-14 border-b border-border flex items-center px-6 gap-2 ${isMobile ? 'justify-between' : 'justify-end'}`}>
      {/* Hamburger menu (mobile only) */}
      {isMobile && (
        <button
          onClick={() => setMobileOpen(true)}
          className="relative p-2 rounded-lg text-text-secondary hover:text-text hover:bg-surface-raised transition-colors"
          aria-label={pendingCount > 0
            ? `${t.common.menu} — ${t.nav.approvals}: ${t.approvals.navWaiting.replace('{count}', String(pendingCount))}`
            : t.common.menu}
        >
          <Menu className="w-5 h-5" />
          {/* the sidebar is closed on mobile — without this, a waiting approval would stay invisible */}
          {pendingCount > 0 && <PendingPulse />}
        </button>
      )}

      <div className="flex items-center gap-2">
        {/* Command Palette trigger */}
        <PaletteTrigger compact={isMobile} label={triggerLabel} />

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
          onClick={toggleAppearance}
          className="p-2 rounded-lg text-text-secondary hover:text-text hover:bg-surface-raised transition-colors"
          title={appearance === 'dark' ? t.settings.switchToLight : t.settings.switchToDark}
          aria-label={appearance === 'dark' ? t.settings.switchToLight : t.settings.switchToDark}
        >
          {appearance === 'dark' ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
        </button>
      </div>
    </header>
  );
}
