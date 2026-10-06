import { CalendarOff, ChevronDown } from 'lucide-react';
import { useT } from '../../i18n';
import type { UndatedMode } from './timelineUndated';

/** Switch at the top of the timeline: show all undated TODOs or fold them. */
export function UndatedSwitch({ total, allOpen, onChange }: { total: number; allOpen: boolean; onChange: (mode: UndatedMode) => void }) {
  const tt = useT().goals.timeline;
  return (
    <div className="flex flex-wrap items-center justify-end gap-2 text-xs text-text-secondary" data-testid="timeline-undated-switch">
      <CalendarOff className="h-3.5 w-3.5 text-text-tertiary" aria-hidden="true" />
      <span>{tt.undatedSwitch.replace('{n}', String(total))}</span>
      <button type="button" onClick={() => onChange(allOpen ? 'none' : 'all')}
        aria-label={allOpen ? tt.undatedCollapseLabel : tt.undatedShowAllLabel}
        className="rounded-md border border-border-strong bg-surface px-2 py-0.5 font-medium text-text hover:border-accent hover:text-accent focus-ring">
        {allOpen ? tt.undatedCollapse : tt.undatedShowAll}
      </button>
    </div>
  );
}

/** Counter of the undated TODOs at a row or a package of the bar - a button that folds open and closed. */
export function UndatedToggle({ count, open, controls, onToggle }: {
  count: number;
  open: boolean;
  controls: string;
  onToggle: (open: boolean) => void;
}) {
  const tt = useT().goals.timeline;
  return (
    <button type="button" aria-expanded={open} aria-controls={controls} onClick={() => onToggle(!open)}
      data-testid="timeline-undated-toggle"
      className={`inline-flex max-w-full items-center gap-1 rounded-md border px-1.5 py-0.5 text-left text-[11px] font-medium leading-[14px] transition-colors focus-ring ${
        open ? 'border-accent/50 bg-accent-subtle text-accent' : 'border-border-strong bg-surface text-text-secondary hover:border-accent hover:text-accent'}`}>
      <CalendarOff className="h-3 w-3 shrink-0" aria-hidden="true" />
      <span className="min-w-0">{count === 1 ? tt.undatedTodo : tt.undatedTodos.replace('{n}', String(count))}</span>
      <ChevronDown className={`h-3 w-3 shrink-0 transition-transform motion-reduce:transition-none ${open ? 'rotate-180' : ''}`} aria-hidden="true" />
    </button>
  );
}
