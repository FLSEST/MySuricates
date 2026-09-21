import { useCallback, useEffect, useMemo, useState } from 'react';
import { X } from 'lucide-react';
import { Button } from '../ui/Button';
import { useT, useI18nStore, type Locale } from '../../i18n';
import { useWalkthroughText } from '../../i18n/walkthrough';
import { useModuleEnabled } from '../../hooks/useModules';
import { useSettingsStore } from '../../stores/settingsStore';
import { WALKTHROUGH_PANELS, type PanelModule } from '../../data/walkthroughPanels';
import walkthroughImages from '../../data/walkthroughImages.json';
import { PanelDiagram } from './PanelDiagram';

/**
 * The welcome tour shown once after the first start. Skippable, re-openable from the settings.
 *
 * It shows concepts, not places: no route is linked and no state is probed, so no module preset,
 * Simple Mode or per-module switch can break it. Only a panel that would otherwise claim something
 * untrue on this instance is hidden (see `module` in `walkthroughPanels.ts`).
 */
export function Walkthrough({ onClose }: { onClose: () => void }) {
  const t = useT();
  const text = useWalkthroughText();
  const locale = useI18nStore((s) => s.locale);
  const setLocale = useI18nStore((s) => s.setLocale);
  // One call per value of `PanelModule` — the record below is total over that union, so a new
  // gated panel does not compile until its module is looked up here.
  const audit = useModuleEnabled('audit');
  const factory = useModuleEnabled('factory');
  const integrations = useModuleEnabled('integrations');
  const [index, setIndex] = useState(0);

  const panels = useMemo(() => {
    const on: Record<PanelModule, boolean> = { audit, factory, integrations };
    return WALKTHROUGH_PANELS.filter((p) => !p.module || on[p.module]);
  }, [audit, factory, integrations]);

  // Module state is optimistic until the backend answers, so the list can shrink under a reader
  // who is already past its new end. Clamped on read rather than corrected in an effect: the
  // stored index is never wrong for long, and a render-time clamp cannot leave a blank frame.
  const position = Math.min(index, panels.length - 1);
  const last = position >= panels.length - 1;
  const panel = panels[position];

  const next = useCallback(() => {
    if (last) onClose();
    else setIndex(position + 1);
  }, [last, position, onClose]);
  const back = useCallback(() => setIndex(Math.max(0, position - 1)), [position]);

  useEffect(() => {
    document.body.style.overflow = 'hidden';
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      else if (e.key === 'ArrowRight') next();
      else if (e.key === 'ArrowLeft') back();
    };
    window.addEventListener('keydown', onKey);
    return () => {
      document.body.style.overflow = '';
      window.removeEventListener('keydown', onKey);
    };
  }, [next, back, onClose]);

  if (!panel) return null;
  const copy = text(panel.id);
  // Written by the image workflow after the set has been approved — until then the stage
  // shows the diagram alone and panels without one have no stage at all.
  const image = (walkthroughImages as Record<string, string>)[panel.id];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/70 backdrop-blur-xs animate-fade-in" />

      <div
        role="dialog"
        aria-modal="true"
        aria-label={copy.title}
        className="relative w-full max-w-2xl max-h-[90vh] flex flex-col bg-surface-overlay border border-border
                   rounded-2xl shadow-2xl overflow-hidden animate-slide-up"
      >
        {/* The tour is often the first thing a new instance shows — the language switch has to be
            reachable here, not three clicks away in the settings. */}
        <div className="absolute top-3 left-3 z-10 flex items-center gap-0.5 p-0.5 rounded-lg bg-surface-overlay/80">
          {(['en', 'de'] as Locale[]).map((l) => (
            <button
              key={l}
              onClick={() => setLocale(l)}
              aria-pressed={locale === l}
              className={`px-2 py-1 text-[0.7rem] font-medium uppercase tracking-wide rounded-md transition-colors ${
                locale === l ? 'bg-accent text-text-inverse' : 'text-text-tertiary hover:text-text'
              }`}
            >
              {l}
            </button>
          ))}
        </div>

        <button
          onClick={onClose}
          aria-label={t.walkthrough.skip}
          className="absolute top-3 right-3 z-10 p-2 rounded-lg text-text-tertiary hover:text-text
                     hover:bg-surface-raised transition-colors"
        >
          <X className="w-4 h-4" />
        </button>

        {/* Stage. Illustration always on the left, diagram always on the right — the same split on
            every panel is what makes them read as one set. The illustration sits on a plate
            of its own: a generated image carries fixed colours and would fight the themed surround. */}
        {(panel.diagram || image) && (
          <div className="shrink-0 h-36 sm:h-44 px-6 sm:px-10 py-4 bg-surface-raised border-b border-border flex items-center justify-center gap-5">
            {image && (
              <img
                src={image}
                alt=""
                aria-hidden="true"
                className="h-full w-auto max-w-[40%] object-contain rounded-xl bg-[#1b1714] p-2"
              />
            )}
            {panel.diagram && (
              <div className="flex-1 h-full min-w-0">
                <PanelDiagram id={panel.diagram} />
              </div>
            )}
          </div>
        )}

        <div className="overflow-y-auto px-6 sm:px-9 py-7">
          <h2 className="text-xl sm:text-2xl font-semibold text-text leading-snug pr-8">{copy.title}</h2>
          <ul className="mt-5 space-y-3">
            {copy.points.map((point) => (
              <li key={point} className="flex gap-3 text-sm sm:text-[0.95rem] text-text-secondary leading-relaxed">
                <span className="mt-2 w-1.5 h-1.5 rounded-full bg-accent shrink-0" />
                <span>{point}</span>
              </li>
            ))}
          </ul>
        </div>

        <div className="shrink-0 flex items-center justify-between gap-4 px-6 sm:px-9 py-4 border-t border-border">
          {/* Wraps rather than pushing the buttons off the edge: at thirteen panels the row is
              wider than a phone footer has room for. */}
          <div className="flex flex-wrap items-center gap-1.5 min-w-0" aria-hidden="true">
            {panels.map((p, i) => (
              <span
                key={p.id}
                className={`h-1.5 rounded-full transition-all ${
                  i === position ? 'w-5 bg-accent' : 'w-1.5 bg-border-strong'
                }`}
              />
            ))}
          </div>
          <div className="flex items-center gap-2">
            {!last && (
              <button onClick={onClose} className="px-3 py-2 text-xs text-text-tertiary hover:text-text transition-colors">
                {t.walkthrough.skip}
              </button>
            )}
            {position > 0 && (
              <Button size="sm" variant="secondary" onClick={back}>
                {t.walkthrough.back}
              </Button>
            )}
            <Button size="sm" onClick={next}>
              {last ? t.walkthrough.done : t.walkthrough.next}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Shows the tour until it has been seen; re-opening is `setWalkthroughSeen(false)` in the settings. */
export function WalkthroughGate() {
  const { walkthroughSeen, setWalkthroughSeen } = useSettingsStore();
  if (walkthroughSeen) return null;
  return <Walkthrough onClose={() => setWalkthroughSeen(true)} />;
}
