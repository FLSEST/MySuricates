import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowDown, ArrowLeft, ArrowUp, CalendarDays, HelpCircle, Target, Trash2 } from 'lucide-react';
import { Button, Card, Select, Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { TodoProgressBar } from '../components/todo/TodoProgressBar';
import { priorityColor } from '../components/todo/todoConstants';
import { ComplexityChip, EffortChip, RatedByMark } from '../components/goal/GoalBadges';
import { useDeleteGoal, useGoal, useReorderGoal, useUpdateGoal } from '../hooks/useGoals';
import { todoDeepLink } from '../utils/todoLinks';
import { useI18nStore, useT } from '../i18n';
import { formatDate } from '../utils/localeFormat';
import type { GoalStatus, GoalTodoItem } from '../types';

const GOAL_STATUSES: GoalStatus[] = ['ACTIVE', 'DONE', 'ARCHIVED'];

const isActive = (todo: GoalTodoItem) =>
  todo.status === 'OPEN' || todo.status === 'IN_PROGRESS' || todo.status === 'BACKLOG';

/**
 * Ein Ziel mit seinen TODOs (TODO dc0566f8): was als Nächstes drankommt (verschiebbar), welche
 * Fragen vorher zu klären sind, was schon erledigt ist, und wie weit jedes Projekt ist.
 */
export function GoalDetail() {
  const { goalId } = useParams<{ goalId: string }>();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const { data, isLoading, error, refetch } = useGoal(goalId);
  const reorder = useReorderGoal();
  const updateGoal = useUpdateGoal();
  const deleteGoal = useDeleteGoal();

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }
  if (error || !data) {
    return <ErrorState message={error?.message ?? t.goals.notFound} onRetry={() => refetch()} />;
  }

  const { goal, todos, byProject } = data;
  const next = todos.filter(isActive);
  const questions = next.filter((todo) => todo.openQuestion);
  const done = todos.filter((todo) => todo.status === 'DONE');

  const move = (index: number, delta: -1 | 1) => {
    const order = next.map((todo) => todo.id);
    const target = index + delta;
    const current = order[index];
    const neighbour = order[target];
    if (current === undefined || neighbour === undefined) return;
    order[index] = neighbour;
    order[target] = current;
    reorder.mutate({ id: goal.id, todoIds: order });
  };

  const remove = () => {
    if (!window.confirm(t.goals.removeConfirm)) return;
    deleteGoal.mutate(goal.id, { onSuccess: () => navigate('/cockpit') });
  };

  return (
    <div className="space-y-6 animate-fade-in">
      <Link to="/cockpit" className="inline-flex items-center gap-1 text-sm text-text-secondary hover:text-text">
        <ArrowLeft className="w-3.5 h-3.5" />
        {t.goals.back}
      </Link>

      <div className="flex flex-wrap items-start gap-3">
        <div className="p-2 rounded-xl bg-accent/10 text-accent">
          <Target className="w-5 h-5" />
        </div>
        <div className="flex-1 min-w-0">
          <h1 className="text-2xl font-bold text-text">{goal.title}</h1>
          {goal.targetDate && (
            <p className="flex items-center gap-1 text-sm text-text-secondary mt-0.5">
              <CalendarDays className="w-3.5 h-3.5" />
              {t.goals.targetDate.replace('{date}', formatDate(goal.targetDate, locale))}
            </p>
          )}
        </div>
        <div className="w-40">
          <Select
            aria-label={t.common.status}
            value={goal.status}
            onChange={(e) => updateGoal.mutate({ id: goal.id, data: { status: e.target.value as GoalStatus } })}
            options={GOAL_STATUSES.map((s) => ({ value: s, label: t.goals.statuses[s] }))}
          />
        </div>
        <Button variant="secondary" size="sm" onClick={remove} aria-label={t.goals.remove}>
          <Trash2 className="w-3.5 h-3.5" />
        </Button>
      </div>

      {goal.description && (
        <Card>
          <p className="text-sm text-text-secondary whitespace-pre-wrap">{goal.description}</p>
        </Card>
      )}

      <Card className="space-y-3">
        <TodoProgressBar open={goal.progress.open} inProgress={goal.progress.inProgress}
          done={goal.progress.done} backlog={goal.progress.backlog} />
        <p className="text-sm text-text-secondary" data-testid="goal-progress">
          {t.goals.progress.replace('{done}', String(goal.progress.done)).replace('{total}', String(goal.progress.total))}
        </p>
        {byProject.length > 1 && (
          <div className="text-xs text-text-secondary space-y-1">
            <p className="font-medium">{t.goals.byProject}</p>
            {byProject.map((p) => (
              <p key={p.projectId ?? 'none'}>
                {p.projectName ?? t.goals.noProject}: {p.done}/{p.total}
              </p>
            ))}
          </div>
        )}
      </Card>

      <section aria-labelledby="goal-next">
        <h2 id="goal-next" className="text-sm font-semibold text-text uppercase tracking-wide mb-3">{t.goals.next}</h2>
        {next.length === 0 ? (
          <Card><p className="text-sm text-text-secondary">{t.goals.noNext}</p></Card>
        ) : (
          <Card padding={false}>
            <ol className="divide-y divide-border">
              {next.map((todo, index) => (
                <li key={todo.id} className="flex items-center gap-3 px-4 py-2.5" data-testid="goal-next-item">
                  <span className="w-6 text-right text-xs tabular-nums text-text-tertiary"
                    title={todo.goalRank == null ? t.goals.unranked : undefined}>
                    {todo.goalRank ?? '·'}
                  </span>
                  <span className={`w-2 h-2 rounded-full shrink-0 ${priorityColor[todo.priority]}`} />
                  <div className="flex-1 min-w-0">
                    <Link to={todoDeepLink(todo)} className="text-sm text-text hover:text-accent truncate block"
                      title={todo.ratingNote ?? undefined}>
                      {todo.title}
                    </Link>
                    {todo.projectName && <span className="text-xs text-text-tertiary">{todo.projectName}</span>}
                  </div>
                  <ComplexityChip value={todo.complexity} />
                  <EffortChip value={todo.suggestedEffort} />
                  <RatedByMark ratedBy={todo.ratedBy} />
                  {todo.openQuestion && <HelpCircle className="w-3.5 h-3.5 text-human" aria-label={t.goals.openQuestion} role="img" />}
                  <div className="flex gap-1">
                    <button type="button" className="p-1 rounded hover:bg-surface disabled:opacity-30" disabled={index === 0 || reorder.isPending}
                      onClick={() => move(index, -1)} aria-label={`${t.goals.moveUp}: ${todo.title}`}>
                      <ArrowUp className="w-3.5 h-3.5" />
                    </button>
                    <button type="button" className="p-1 rounded hover:bg-surface disabled:opacity-30"
                      disabled={index === next.length - 1 || reorder.isPending}
                      onClick={() => move(index, 1)} aria-label={`${t.goals.moveDown}: ${todo.title}`}>
                      <ArrowDown className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </li>
              ))}
            </ol>
          </Card>
        )}
      </section>

      <section aria-labelledby="goal-questions">
        <h2 id="goal-questions" className="text-sm font-semibold text-text uppercase tracking-wide mb-3">{t.goals.questions}</h2>
        {questions.length === 0 ? (
          <Card><p className="text-sm text-text-secondary">{t.goals.noQuestions}</p></Card>
        ) : (
          <Card padding={false}>
            <ul className="divide-y divide-border">
              {questions.map((todo) => (
                <li key={todo.id} className="px-4 py-2.5" data-testid="goal-question">
                  <Link to={todoDeepLink(todo)} className="text-sm text-text hover:text-accent">{todo.title}</Link>
                  <p className="text-sm text-human mt-0.5">{todo.openQuestion}</p>
                </li>
              ))}
            </ul>
          </Card>
        )}
      </section>

      {done.length > 0 && (
        <details>
          <summary className="cursor-pointer text-sm font-semibold text-text uppercase tracking-wide">
            {t.goals.done} ({done.length})
          </summary>
          <Card padding={false} className="mt-3">
            <ul className="divide-y divide-border">
              {done.map((todo) => (
                <li key={todo.id} className="px-4 py-2 text-sm text-text-secondary line-through">
                  <Link to={todoDeepLink(todo)}>{todo.title}</Link>
                </li>
              ))}
            </ul>
          </Card>
        </details>
      )}
    </div>
  );
}
