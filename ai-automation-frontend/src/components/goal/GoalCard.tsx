import { CalendarDays, FolderOpen } from 'lucide-react';
import { Card } from '../ui';
import { TodoProgressBar } from '../todo/TodoProgressBar';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { DecisionCounts } from './DecisionCounts';
import { MilestoneDiamond } from './MilestoneDiamond';
import type { Goal } from '../../types';

/** A card carries this many project chips, the rest appears as „+n“. */
const PROJECT_CHIPS = 3;

/**
 * A goal as a card: projects, the next milestone (c60bf914), progress, target date, open
 * questions; a click opens the goal view.
 */
export function GoalCard({ goal, onOpen }: { goal: Goal; onOpen: () => void }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { progress } = goal;
  const projects = goal.projects ?? [];
  const hidden = projects.slice(PROJECT_CHIPS);
  return (
    <button type="button" onClick={onOpen} className="text-left" aria-label={goal.title}>
      <Card className="flex flex-col gap-2 hover:border-border-strong transition-colors h-full">
        <div className="flex items-start gap-2">
          <span className="font-semibold text-text flex-1">{goal.title}</span>
          {goal.status !== 'ACTIVE' && (
            <span className="text-[11px] px-1.5 py-0.5 rounded bg-surface text-text-tertiary">{t.goals.statuses[goal.status]}</span>
          )}
        </div>
        {projects.length > 0 && (
          <div className="flex flex-wrap items-center gap-1" data-testid="goal-card-projects">
            {projects.slice(0, PROJECT_CHIPS).map((project) => (
              <span key={project.id} title={project.name}
                className="inline-flex max-w-[10rem] items-center gap-1 rounded-full bg-surface px-2 py-0.5 text-[11px] text-text-secondary">
                <FolderOpen className="h-3 w-3 shrink-0" aria-hidden="true" />
                <span className="truncate">{project.name}</span>
              </span>
            ))}
            {hidden.length > 0 && (
              <span className="text-[11px] text-text-tertiary" title={hidden.map((project) => project.name).join(', ')}>
                +{hidden.length}
              </span>
            )}
          </div>
        )}
        {goal.nextMilestone && (
          <span className="flex min-w-0 items-center gap-1.5 text-xs text-text-secondary" data-testid="goal-card-milestone"
            title={t.goals.nextMilestone.replace('{title}', goal.nextMilestone.title)
              .replace('{date}', formatDate(goal.nextMilestone.dueDate, locale))}>
            <MilestoneDiamond state={goal.nextMilestone.state} size="sm" />
            <span className="min-w-0 truncate">{goal.nextMilestone.title}</span>
            <span className="shrink-0 tabular-nums text-text-tertiary">
              {formatDate(goal.nextMilestone.dueDate, locale, { day: 'numeric', month: 'numeric' })}
            </span>
          </span>
        )}
        <TodoProgressBar open={progress.open} inProgress={progress.inProgress} done={progress.done} backlog={progress.backlog} />
        <div className="flex flex-wrap items-center gap-3 text-xs text-text-secondary">
          <span>{t.goals.progress.replace('{done}', String(progress.done)).replace('{total}', String(progress.total))}</span>
          {goal.targetDate && (
            <span className="flex items-center gap-1">
              <CalendarDays className="w-3 h-3" />
              {t.goals.targetDate.replace('{date}', formatDate(goal.targetDate, locale))}
            </span>
          )}
          <DecisionCounts openQuestions={progress.openQuestions} decided={progress.decided ?? 0} variant="words" />
        </div>
      </Card>
    </button>
  );
}
