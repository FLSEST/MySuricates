import { CalendarClock } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { isOverdue } from './timelineLayout';
import { SHORT_DATE, dueTitle } from './planDisplay';
import { MilestoneDiamond } from './MilestoneDiamond';
import type { GoalMilestone, GoalTodoItem } from '../../types';

/** Fälligkeit eines TODOs; überfällig in der Fehlerfarbe. */
export function DueChip({ todo, today }: { todo: Pick<GoalTodoItem, 'dueDate' | 'status'>; today: number }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  if (!todo.dueDate) return null;
  const overdue = isOverdue(todo, today);
  return (
    <span title={dueTitle(todo.dueDate, overdue, locale, t)}
      className={`inline-flex shrink-0 items-center gap-1 rounded px-1.5 py-0.5 text-[11px] font-medium ${
        overdue ? 'bg-danger-subtle text-danger' : 'bg-surface text-text-secondary'}`}>
      <CalendarClock className="h-3 w-3" aria-hidden="true" />
      {formatDate(todo.dueDate, locale, SHORT_DATE)}
    </span>
  );
}

/** Meilenstein eines TODOs, mit der Raute in seiner Zustandsfarbe. */
export function MilestoneChip({ milestone }: { milestone: GoalMilestone | undefined }) {
  const t = useT();
  if (!milestone) return null;
  return (
    <span title={t.goals.milestones.chip.replace('{title}', milestone.title)}
      className="inline-flex max-w-[10rem] shrink-0 items-center gap-1.5 rounded border border-border px-1.5 py-0.5 text-[11px] text-text-secondary">
      <MilestoneDiamond state={milestone.state} size="sm" />
      <span className="truncate">{milestone.title}</span>
    </span>
  );
}
