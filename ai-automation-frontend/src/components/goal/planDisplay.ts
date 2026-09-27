import type { Locale, TranslationKeys } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { isOverdue } from './timelineLayout';
import type { GoalTodoItem, GoalWorkPackage, TodoStatus } from '../../types';

/** Statuspunkt eines TODOs – am Zeitstrahl und in der Gliederung. */
export const STATUS_DOT: Record<TodoStatus, string> = {
  OPEN: 'bg-text-tertiary',
  BACKLOG: 'bg-border-strong',
  IN_PROGRESS: 'bg-info',
  DONE: 'bg-success',
  ARCHIVED: 'bg-border',
};

/** Kurzes Datum für Chips und Achse: „3. Okt.“ / „Oct 3“. */
export const SHORT_DATE = { month: 'short', day: 'numeric' } as const;

/** Tooltip einer Fälligkeit: „Fällig 3.10.2026“, überfällig „Überfällig seit …“. */
export function dueTitle(dueDate: string, overdue: boolean, locale: Locale, t: TranslationKeys): string {
  return (overdue ? t.goals.timeline.overdue : t.goals.timeline.due).replace('{date}', formatDate(dueDate, locale));
}

export interface PlanBadge {
  text: string;
  title: string;
  overdue: boolean;
}

/**
 * Fälligkeit und Arbeitspaket eines TODOs als ein Stück Text („3. Okt. · Beta“) – für die Knoten der
 * Relationskarte, die keine HTML-Chips tragen können. Null, wenn beides fehlt.
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
