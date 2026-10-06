import { type ReactNode } from 'react';
import { CheckCircle2, ChevronDown, ChevronRight, HelpCircle } from 'lucide-react';
import { useT } from '../../i18n';
import { percentDone, type OutlineCounts } from './goalOutline';

/**
 * Building blocks of the outline (c60bf914) following the pattern of `workflow/ProjectGroup`: a button with
 * `aria-expanded`/`aria-controls` expands and collapses a group. The outline moves via
 * arrow keys from button to button (`data-outline-toggle`, see `GoalOutline`).
 */

export function OutlineToggle({ nodeKey, expanded, controls, onToggle, className = 'flex-1', label, children }: {
  nodeKey: string;
  expanded: boolean;
  /** ID of the area that the button expands and collapses. */
  controls: string;
  onToggle: () => void;
  className?: string;
  /** Name when the button shows only the arrow (package row: the title next to it opens the side panel). */
  label?: string;
  children?: ReactNode;
}) {
  const Chevron = expanded ? ChevronDown : ChevronRight;
  return (
    <button type="button" data-outline-toggle={nodeKey} aria-expanded={expanded} aria-controls={controls} onClick={onToggle}
      aria-label={label} title={label}
      className={`flex min-w-0 items-center gap-1.5 rounded-md py-1 text-left focus-ring ${className}`}>
      <Chevron className="h-4 w-4 shrink-0 text-text-tertiary" aria-hidden="true" />
      {children}
    </button>
  );
}

/**
 * Counter with fixed width so that „done/total“, „❓ n“ (open) and „✔ m“ (decided,
 * 68a5e24e) line up across all rows. The short form is visible, the screen reader reads the whole
 * sentence. An empty column still keeps its place.
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

/** Progress bar: done in the success colour on a neutral ground. Purely graphical — the number is next to it. */
export function OutlineBar({ counts, className = '' }: { counts: OutlineCounts; className?: string }) {
  return (
    <span aria-hidden="true" className={`block overflow-hidden rounded-full bg-border ${className}`}>
      <span className="block h-full rounded-full bg-success transition-[width] duration-300 motion-reduce:transition-none"
        style={{ width: `${percentDone(counts)}%` }} />
    </span>
  );
}

/**
 * A project card of the outline: header with name, counters and actions, below it the bar across
 * the whole width, expanded the work packages.
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
        {/* Fixed width like the three buttons of the package rows — so the counters line up with those below */}
        <div className="flex w-[66px] shrink-0 items-center justify-end">{actions}</div>
      </div>
      <OutlineBar counts={counts} className="mx-3 mb-2.5 mt-1.5 h-1.5 sm:mx-4" />
      {/* When collapsed the area stays in the DOM (hidden) — so `aria-controls` always points at something. */}
      <div id={bodyId} hidden={!expanded} className="border-t border-border px-3 pb-3 pt-2 sm:px-4">
        {children}
      </div>
    </section>
  );
}
