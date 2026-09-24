import { Link } from 'react-router-dom';
import { ArrowDown, ArrowUp, HelpCircle } from 'lucide-react';
import { Card } from '../ui';
import { priorityColor } from '../todo/todoConstants';
import { ComplexityChip, EffortChip, RatedByMark } from './GoalBadges';
import { useReorderGoal } from '../../hooks/useGoals';
import { todoDeepLink } from '../../utils/todoLinks';
import { useT } from '../../i18n';
import type { GoalTodoItem } from '../../types';

const isActive = (todo: GoalTodoItem) =>
  todo.status === 'OPEN' || todo.status === 'IN_PROGRESS' || todo.status === 'BACKLOG';

/**
 * Die TODOs eines Ziels als Liste (TODO dc0566f8): was als Nächstes drankommt (verschiebbar),
 * welche Fragen vorher zu klären sind, was schon erledigt ist.
 */
export function GoalTodoList({ goalId, todos }: { goalId: string; todos: GoalTodoItem[] }) {
  const t = useT();
  const reorder = useReorderGoal();
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
    reorder.mutate({ id: goalId, todoIds: order });
  };

  return (
    <>
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
    </>
  );
}
