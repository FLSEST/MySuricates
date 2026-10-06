import { CalendarClock } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { isOverdue } from './timelineLayout';
import { SHORT_DATE, dueTitle } from './planDisplay';
import { WorkPackageMark } from './WorkPackageMark';
import type { GoalTodoItem, GoalWorkPackage } from '../../types';

/** Due date of a TODO; overdue in the error colour. */
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

/** Arbeitspaket eines TODOs, mit dem Balken-Zeichen in seiner Zustandsfarbe. */
export function WorkPackageChip({ workPackage }: { workPackage: GoalWorkPackage | undefined }) {
  const t = useT();
  if (!workPackage) return null;
  return (
    <span title={t.goals.workPackages.chip.replace('{title}', workPackage.title)}
      className="inline-flex max-w-[10rem] shrink-0 items-center gap-1.5 rounded border border-border px-1.5 py-0.5 text-[11px] text-text-secondary">
      <WorkPackageMark state={workPackage.state} />
      <span className="truncate">{workPackage.title}</span>
    </span>
  );
}
