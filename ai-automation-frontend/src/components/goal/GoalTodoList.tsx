import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowDown, ArrowUp, CheckCircle2 } from 'lucide-react';
import { Card } from '../ui';
import { priorityColor } from '../todo/todoConstants';
import { ComplexityChip, EffortChip, RatedByMark } from './GoalBadges';
import { DueChip, WorkPackageChip } from './TodoPlanChips';
import { DecisionCard, DecisionMark } from './DecisionMark';
import { useDecisionScope } from './decisionContext';
import { useReorderGoal } from '../../hooks/useGoals';
import { todoDeepLink } from '../../utils/todoLinks';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import type { GoalTodoItem, GoalWorkPackage } from '../../types';

const isActive = (todo: GoalTodoItem) =>
  todo.status === 'OPEN' || todo.status === 'IN_PROGRESS' || todo.status === 'BACKLOG';

/**
 * A goal's TODOs as a list (TODO dc0566f8): what comes next (movable),
 * which questions need clarifying beforehand — with „Entscheiden“ on each (68a5e24e) and the collapsible
 * area „Entschieden“ —, what is already done. Due date and work package appear as chips on
 * each TODO (TODO e192df18).
 */
export function GoalTodoList({ goalId, todos, workPackages, today, onOpenTodo }: {
  goalId: string;
  todos: GoalTodoItem[];
  workPackages: GoalWorkPackage[];
  /** Day (`timelineLayout.dayOf`) against which „overdue“ is judged. */
  today: number;
  /** Opens the TODO dialog (c60bf914); without it the title leads to the TODO page. */
  onOpenTodo?: (todoId: string) => void;
}) {
  const t = useT();
  const scope = useDecisionScope();
  // The „Entscheiden“ card hangs on the list, not on the row: the row disappears immediately on
  // deciding; if that fails, the typed text should still be there (c60bf914)
  const [deciding, setDeciding] = useState<GoalTodoItem | null>(null);
  const decidingTrigger = useRef<HTMLElement | null>(null);
  const decide = (todo: GoalTodoItem, trigger: HTMLElement) => {
    decidingTrigger.current = trigger;
    setDeciding((current) => (current?.id === todo.id ? null : todo));
  };
  const reorder = useReorderGoal();
  const packageOf = (todo: GoalTodoItem) => workPackages.find((item) => item.id === todo.workPackageId);
  const next = todos.filter(isActive);
  const questions = next.filter((todo) => todo.openQuestion);
  const decided = todos.filter((todo) => todo.status !== 'ARCHIVED' && todo.decisionState === 'DECIDED');
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
                    <TodoTitle todo={todo} onOpenTodo={onOpenTodo} className="block truncate" title={todo.ratingNote ?? undefined} />
                    {todo.projectName && <span className="text-xs text-text-tertiary">{todo.projectName}</span>}
                  </div>
                  <DueChip todo={todo} today={today} />
                  <WorkPackageChip workPackage={packageOf(todo)} />
                  <ComplexityChip value={todo.complexity} />
                  <EffortChip value={todo.suggestedEffort} />
                  <RatedByMark ratedBy={todo.ratedBy} />
                  <DecisionMark todo={todo} />
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
                <QuestionRow key={todo.id} todo={todo} onOpenTodo={onOpenTodo} open={deciding?.id === todo.id} onDecide={decide} />
              ))}
            </ul>
          </Card>
        )}
        {decided.length > 0 && (
          <details className="mt-3" data-testid="goal-decided">
            <summary className="cursor-pointer text-sm font-medium text-text-secondary hover:text-text">
              {t.goals.decision.section.replace('{n}', String(decided.length))}
            </summary>
            <Card padding={false} className="mt-2">
              <ul className="divide-y divide-border">
                {decided.map((todo) => <DecidedRow key={todo.id} todo={todo} onOpenTodo={onOpenTodo} />)}
              </ul>
            </Card>
          </details>
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
                  <TodoTitle todo={todo} onOpenTodo={onOpenTodo} className="line-through" />
                </li>
              ))}
            </ul>
          </Card>
        </details>
      )}
      {deciding && scope && (
        <DecisionCard todo={deciding} goalId={scope.goalId} triggerRef={decidingTrigger} onClose={() => setDeciding(null)} />
      )}
    </>
  );
}

/**
 * Title of a TODO: opens the goal page's TODO dialog (c60bf914, Flo: „besser zu den TODOs
 * kommen … anklickbar“), by mouse and with Enter; without a dialog a link to the TODO page.
 */
function TodoTitle({ todo, onOpenTodo, className = '', title }: {
  todo: GoalTodoItem;
  onOpenTodo?: (todoId: string) => void;
  className?: string;
  title?: string;
}) {
  const t = useT();
  if (!onOpenTodo) {
    return <Link to={todoDeepLink(todo)} className={`text-sm text-text hover:text-accent ${className}`} title={title}>{todo.title}</Link>;
  }
  return (
    <button type="button" onClick={() => onOpenTodo(todo.id)} title={title ?? todo.title}
      aria-label={t.goals.outline.openTodo.replace('{title}', todo.title)}
      className={`max-w-full rounded text-left text-sm text-text hover:text-accent focus-ring ${className}`}>
      {todo.title}
    </button>
  );
}

/** An open question: TODO, wording, „Entscheiden“ opens the card (68a5e24e) — the card holds the list. */
function QuestionRow({ todo, onOpenTodo, open, onDecide }: {
  todo: GoalTodoItem;
  onOpenTodo?: (todoId: string) => void;
  open: boolean;
  onDecide: (todo: GoalTodoItem, trigger: HTMLElement) => void;
}) {
  const t = useT();
  const scope = useDecisionScope();
  return (
    <li className="flex items-start gap-3 px-4 py-2.5" data-testid="goal-question">
      <div className="min-w-0 flex-1">
        <TodoTitle todo={todo} onOpenTodo={onOpenTodo} />
        <p className="text-sm text-human mt-0.5">{todo.openQuestion}</p>
        {todo.decision && <p className="mt-0.5 text-xs text-text-tertiary">{t.goals.decision.earlier.replace('{text}', todo.decision)}</p>}
      </div>
      {scope?.enabled && (
        <button type="button" aria-haspopup="dialog" aria-expanded={open} onClick={(event) => onDecide(todo, event.currentTarget)}
          aria-label={t.goals.decision.decideFor.replace('{title}', todo.title)}
          className="shrink-0 rounded-md border border-human/40 bg-human-subtle px-2.5 py-1 text-xs font-medium text-human hover:border-human focus-ring">
          {t.goals.decision.decide}
        </button>
      )}
    </li>
  );
}

/** A decided TODO: wording of the decision, when and who; ✔ opens „Ändern/Entfernen“. */
function DecidedRow({ todo, onOpenTodo }: { todo: GoalTodoItem; onOpenTodo?: (todoId: string) => void }) {
  const t = useT();
  const td = t.goals.decision;
  const locale = useI18nStore((s) => s.locale);
  return (
    <li className="flex items-start gap-3 px-4 py-2.5" data-testid="goal-decided-item">
      <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <TodoTitle todo={todo} onOpenTodo={onOpenTodo} />
        <p className="mt-0.5 text-sm text-text-secondary">{todo.decision}</p>
        {todo.decidedAt && (
          <p className="mt-0.5 text-xs text-text-tertiary">
            {td.meta.replace('{date}', formatDate(todo.decidedAt, locale)).replace('{who}', todo.decidedBy ? td.by[todo.decidedBy] : '')
              .replace(/, $/, '')}
          </p>
        )}
      </div>
      <DecisionMark todo={todo} />
    </li>
  );
}
