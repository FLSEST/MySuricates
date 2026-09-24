import { Sparkles, Zap } from 'lucide-react';
import { useSettingsStore } from '../../stores/settingsStore';
import { useT } from '../../i18n';

/**
 * Einfach|Pro switch under the logo. Expanded it is a pill with both modes; collapsed it is one
 * icon button that switches to the other mode. Both count as a mode choice (`chooseMode`).
 */
export function SidebarModeToggle({ collapsed }: { collapsed: boolean }) {
  const { simpleMode, chooseMode } = useSettingsStore();
  const t = useT();

  if (collapsed) {
    // Collapsed: the Einfach|Pro pill has no room — one icon button switches the mode
    return (
      <button
        onClick={() => chooseMode(!simpleMode)}
        aria-label={simpleMode ? t.simple.handoff.switchToPro : t.simple.handoff.switchToSimple}
        title={simpleMode ? t.simple.handoff.switchToPro : t.simple.handoff.switchToSimple}
        className="mt-2 flex h-7 w-7 items-center justify-center rounded-full text-accent transition-colors hover:bg-[var(--color-sidebar-hover)]"
        style={{ backgroundColor: 'color-mix(in srgb, var(--color-sidebar-text) 10%, transparent)' }}
      >
        {simpleMode ? <Zap className="w-3.5 h-3.5" /> : <Sparkles className="w-3.5 h-3.5" />}
      </button>
    );
  }

  return (
    <div
      className="relative mt-2 flex rounded-full p-0.5 text-xs"
      style={{ backgroundColor: 'color-mix(in srgb, var(--color-sidebar-text) 10%, transparent)' }}
    >
      <div
        className={`absolute inset-y-0.5 w-[calc(50%-2px)] rounded-full bg-accent/25 transition-transform duration-200 ease-in-out ${
          simpleMode ? 'translate-x-0.5' : 'translate-x-[calc(100%+2px)]'
        }`}
      />
      <button
        onClick={() => chooseMode(true)}
        className={`relative z-10 flex items-center gap-1 px-3 py-1 rounded-full font-medium transition-colors ${
          simpleMode
            ? 'text-accent'
            : 'text-[var(--color-sidebar-text-muted)] hover:text-[var(--color-sidebar-text)]'
        }`}
      >
        <Zap className="w-3 h-3" />
        {t.settings.simpleModeShort}
      </button>
      <button
        onClick={() => chooseMode(false)}
        className={`relative z-10 flex items-center gap-1 px-3 py-1 rounded-full font-medium transition-colors ${
          !simpleMode
            ? 'text-accent'
            : 'text-[var(--color-sidebar-text-muted)] hover:text-[var(--color-sidebar-text)]'
        }`}
      >
        <Sparkles className="w-3 h-3" />
        {t.settings.fullModeShort}
      </button>
    </div>
  );
}
