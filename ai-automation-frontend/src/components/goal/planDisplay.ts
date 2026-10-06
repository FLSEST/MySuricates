import type { Locale, TranslationKeys } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { isOverdue } from './timelineLayout';
import type { GoalTodoItem, GoalWorkPackage, TodoStatus } from '../../types';

/** Status dot of a TODO - on the timeline and in the outline. */
export const STATUS_DOT: Record<TodoStatus, string> = {
  OPEN: 'bg-text-tertiary',
  BACKLOG: 'bg-border-strong',
  IN_PROGRESS: 'bg-info',
  DONE: 'bg-success',
  ARCHIVED: 'bg-border',
};

/** Short date for chips and axis: „3. Okt.“ / „Oct 3“. */
export const SHORT_DATE = { month: 'short', day: 'numeric' } as const;

/** Tooltip of a due date: „Fällig 3.10.2026“, overdue „Überfällig seit …“. */
export function dueTitle(dueDate: string, overdue: boolean, locale: Locale, t: TranslationKeys): string {
  return (overdue ? t.goals.timeline.overdue : t.goals.timeline.due).replace('{date}', formatDate(dueDate, locale));
}

export interface PlanBadge {
  text: string;
  title: string;
  overdue: boolean;
}

/**
 * Due date and work package of a TODO as one piece of text („3. Okt. · Beta“) - for the nodes of the
 * relation card, which cannot carry HTML chips. Null if both are missing.
 */
export function planBadge(
  todo: Pick<GoalTodoItem, 'dueDate' | 'status'>, workPackage: GoalWorkPackage | undefined, today: number,
  locale: Locale, t: TranslationKeys,
): PlanBadge | null {
  if (!todo.dueDate && !workPackage) return null;
  const overdue = isOverdue(todo, today);
  const text: string[] = [];
  const title: string[] = [];
  if (todo.dueDate) {
    text.push(formatDate(todo.dueDate, locale, SHORT_DATE));
    title.push(dueTitle(todo.dueDate, overdue, locale, t));
  }
  if (workPackage) {
    text.push(workPackage.title);
    title.push(t.goals.workPackages.chip.replace('{title}', workPackage.title));
  }
  return { text: text.join(' · '), title: title.join(' · '), overdue };
}
