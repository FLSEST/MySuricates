import { CalendarDays, HelpCircle } from 'lucide-react';
import { Card } from '../ui';
import { TodoProgressBar } from '../todo/TodoProgressBar';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import type { Goal } from '../../types';

/** Ein Ziel als Karte: Fortschritt, Zieldatum, offene Fragen; ein Klick öffnet die Zielansicht. */
export function GoalCard({ goal, onOpen }: { goal: Goal; onOpen: () => void }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { progress } = goal;
  return (
    <button type="button" onClick={onOpen} className="text-left" aria-label={goal.title}>
      <Card className="flex flex-col gap-2 hover:border-border-strong transition-colors h-full">
        <div className="flex items-start gap-2">
          <span className="font-semibold text-text flex-1">{goal.title}</span>
          {goal.status !== 'ACTIVE' && (
            <span className="text-[11px] px-1.5 py-0.5 rounded bg-surface text-text-tertiary">{t.goals.statuses[goal.status]}</span>
          )}
        </div>
        <TodoProgressBar open={progress.open} inProgress={progress.inProgress} done={progress.done} backlog={progress.backlog} />
        <div className="flex flex-wrap items-center gap-3 text-xs text-text-secondary">
          <span>{t.goals.progress.replace('{done}', String(progress.done)).replace('{total}', String(progress.total))}</span>
          {goal.targetDate && (
            <span className="flex items-center gap-1">
              <CalendarDays className="w-3 h-3" />
              {t.goals.targetDate.replace('{date}', formatDate(goal.targetDate, locale))}
            </span>
          )}
          {progress.openQuestions > 0 && (
            <span className="flex items-center gap-1 text-human">
              <HelpCircle className="w-3 h-3" />
              {t.goals.openQuestions.replace('{n}', String(progress.openQuestions))}
            </span>
          )}
        </div>
      </Card>
    </button>
  );
}
