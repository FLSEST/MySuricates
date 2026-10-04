import { useId } from 'react';
import { Lightbulb } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Button } from '../ui';
import { useT } from '../../i18n';
import { useHintText } from '../../i18n/hints';
import { HINTS, hintBlueprint, type HintId } from '../../data/hints';
import { useSettingsStore } from '../../stores/settingsStore';
import { useToast } from '../../hooks/useToast';

/**
 * A hint in place (TODO e284eb3d): rendered by the component it explains, see `data/hints.ts`.
 *
 * Shown until dismissed for its current version; afterwards a small button in the same spot brings
 * it back. Renders nothing while hints are switched off (settings, or "hide all" on any hint).
 * A card in the page flow, not a floating tooltip: it moves with the layout instead of pointing at
 * coordinates. The copy is rendered as React text only. A hint with a `blueprint` links to it: the
 * create dialog with that blueprint chosen (`/workflows?new=1&template=…`, TODO 093d2f11).
 */
export function Hint({ id, className = '' }: { id: HintId; className?: string }) {
  const t = useT();
  const text = useHintText(id);
  const titleId = useId();
  const toast = useToast();
  const enabled = useSettingsStore((s) => s.hintsEnabled);
  const dismissedVersion = useSettingsStore((s) => s.dismissedHints[id]);
  const dismissHint = useSettingsStore((s) => s.dismissHint);
  const restoreHint = useSettingsStore((s) => s.restoreHint);
  const setHintsEnabled = useSettingsStore((s) => s.setHintsEnabled);

  if (!enabled) return null;
  const { version } = HINTS[id];
  const blueprint = hintBlueprint(id);

  if (dismissedVersion === version) {
    return (
      <div className={`flex justify-end ${className}`}>
        <button
          type="button"
          onClick={() => restoreHint(id)}
          aria-label={`${t.hints.show}: ${text.title}`}
          title={`${t.hints.show}: ${text.title}`}
          data-hint-restore={id}
          className="inline-flex items-center rounded-md p-1 text-text-tertiary hover:text-info hover:bg-info-subtle transition-colors"
        >
          <Lightbulb className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </div>
    );
  }

  return (
    <aside
      role="note"
      aria-labelledby={titleId}
      data-hint={id}
      className={`flex gap-3 rounded-xl border border-info/30 bg-info-subtle p-4 text-left ${className}`}
    >
      <Lightbulb className="mt-0.5 h-5 w-5 shrink-0 text-info" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <p id={titleId} className="text-sm font-semibold text-text">{text.title}</p>
        <ul className="mt-1.5 list-disc space-y-1 pl-4 text-sm text-text-secondary">
          {text.points.map((point) => <li key={point}>{point}</li>)}
        </ul>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button size="sm" variant="secondary" onClick={() => dismissHint(id, version)}>
            {t.hints.understood}
          </Button>
          {blueprint && (
            <Link
              to={`/workflows?new=1&template=${encodeURIComponent(blueprint)}`}
              data-hint-blueprint={blueprint}
              className="inline-flex items-center rounded-md px-2.5 py-1 text-xs font-medium text-info hover:bg-info/10 transition-colors"
            >
              {t.hints.tryBlueprint}
            </Link>
          )}
          <Button
            size="sm"
            variant="ghost"
            onClick={() => {
              setHintsEnabled(false);
              toast.info(t.hints.hiddenToast);
            }}
          >
            {t.hints.hideAll}
          </Button>
        </div>
      </div>
    </aside>
  );
}
