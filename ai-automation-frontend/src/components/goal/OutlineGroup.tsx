import { type ReactNode } from 'react';
import { CheckCircle2, ChevronDown, ChevronRight, HelpCircle } from 'lucide-react';
import { useT } from '../../i18n';
import { percentDone, type OutlineCounts } from './goalOutline';

/**
 * Bausteine der Gliederung (c60bf914) nach dem Muster von `workflow/ProjectGroup`: ein Knopf mit
 * `aria-expanded`/`aria-controls` klappt eine Gruppe auf und zu. Die Gliederung wandert per
 * Pfeiltasten von Knopf zu Knopf (`data-outline-toggle`, siehe `GoalOutline`).
 */

export function OutlineToggle({ nodeKey, expanded, controls, onToggle, className = '', children }: {
  nodeKey: string;
  expanded: boolean;
  /** ID des Bereichs, den der Knopf auf- und zuklappt. */
  controls: string;
  onToggle: () => void;
  className?: string;
  children: ReactNode;
}) {
  const Chevron = expanded ? ChevronDown : ChevronRight;
  return (
    <button type="button" data-outline-toggle={nodeKey} aria-expanded={expanded} aria-controls={controls} onClick={onToggle}
      className={`flex min-w-0 flex-1 items-center gap-1.5 rounded-md py-1 text-left focus-ring ${className}`}>
      <Chevron className="h-4 w-4 shrink-0 text-text-tertiary" aria-hidden="true" />
      {children}
    </button>
  );
}

/**
 * Zähler mit fester Breite, damit „erledigt/gesamt“, „❓ n“ (offen) und „✔ m“ (entschieden,
 * 68a5e24e) über alle Zeilen fluchten. Sichtbar steht die Kurzform, der Screenreader liest den ganzen
 * Satz. Eine leere Spalte hält trotzdem ihren Platz.
 */
export function OutlineCountColumns({ counts }: { counts: OutlineCounts }) {
  const t = useT();
  return (
    <>
      <span className="w-12 shrink-0 text-right text-xs tabular-nums text-text-secondary" data-testid="outline-done">
        <span aria-hidden="true">{counts.done}/{counts.total}</span>
        <span className="sr-only">
          {t.goals.progress.replace('{done}', String(counts.done)).replace('{total}', String(counts.total))}
        </span>
      </span>
      <span className="inline-flex w-10 shrink-0 items-center justify-end gap-0.5 text-xs tabular-nums text-human"
        data-testid="outline-questions">
        {counts.openQuestions > 0 && (
          <>
            <HelpCircle className="h-3.5 w-3.5" aria-hidden="true" />
            <span aria-hidden="true">{counts.openQuestions}</span>
            <span className="sr-only">{t.goals.openQuestions.replace('{n}', String(counts.openQuestions))}</span>
          </>
        )}
      </span>
      <span className="inline-flex w-10 shrink-0 items-center justify-end gap-0.5 text-xs tabular-nums text-success"
        data-testid="outline-decided">
        {counts.decided > 0 && (
          <>
            <CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" />
            <span aria-hidden="true">{counts.decided}</span>
            <span className="sr-only">{t.goals.decision.decidedCount.replace('{n}', String(counts.decided))}</span>
          </>
        )}
      </span>
    </>
  );
}

/** Fortschrittsbalken: erledigt in der Erfolgsfarbe auf neutralem Grund. Rein grafisch – die Zahl steht daneben. */
export function OutlineBar({ counts, className = '' }: { counts: OutlineCounts; className?: string }) {
  return (
    <span aria-hidden="true" className={`block overflow-hidden rounded-full bg-border ${className}`}>
      <span className="block h-full rounded-full bg-success transition-[width] duration-300 motion-reduce:transition-none"
        style={{ width: `${percentDone(counts)}%` }} />
    </span>
  );
}

/**
 * Eine Projektkarte der Gliederung: Kopf mit Name, Zählern und Aktionen, darunter der Balken über
 * die ganze Breite, aufgeklappt die Arbeitspakete.
 */
export function OutlineGroup({ nodeKey, bodyId, title, badge, counts, expanded, onToggle, actions, children, testId }: {
  nodeKey: string;
  bodyId: string;
  title: string;
  badge?: ReactNode;
  counts: OutlineCounts;
  expanded: boolean;
  onToggle: () => void;
  actions?: ReactNode;
  children: ReactNode;
  testId?: string;
}) {
  return (
    <section className="rounded-xl border border-card-border bg-surface-raised" aria-label={title} data-testid={testId}>
      <div className="flex items-center gap-2 px-3 pt-2.5 sm:px-4">
        <OutlineToggle nodeKey={nodeKey} expanded={expanded} controls={bodyId} onToggle={onToggle}>
          <span className="min-w-0 truncate text-[15px] font-semibold text-text" title={title}>{title}</span>
          {badge}
        </OutlineToggle>
        <OutlineCountColumns counts={counts} />
        {/* Feste Breite wie die drei Knöpfe der Paket-Zeilen – so fluchten die Zähler mit denen darunter */}
        <div className="flex w-[66px] shrink-0 items-center justify-end">{actions}</div>
      </div>
      <OutlineBar counts={counts} className="mx-3 mb-2.5 mt-1.5 h-1.5 sm:mx-4" />
      {/* Zugeklappt bleibt der Bereich im DOM (versteckt) – so zeigt `aria-controls` immer auf etwas. */}
      <div id={bodyId} hidden={!expanded} className="border-t border-border px-3 pb-3 pt-2 sm:px-4">
        {children}
      </div>
    </section>
  );
}
