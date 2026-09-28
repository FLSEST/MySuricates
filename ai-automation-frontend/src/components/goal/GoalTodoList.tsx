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
 * Die TODOs eines Ziels als Liste (TODO dc0566f8): was als Nächstes drankommt (verschiebbar),
 * welche Fragen vorher zu klären sind – mit „Entscheiden“ an jeder (68a5e24e) und dem aufklappbaren
 * Bereich „Entschieden“ –, was schon erledigt ist. Fälligkeit und Arbeitspaket stehen als Chips an
 * jedem TODO (TODO e192df18).
 */
export function GoalTodoList({ goalId, todos, workPackages, today }: {
  goalId: string;
  todos: GoalTodoItem[];
  workPackages: GoalWorkPackage[];
  /** Tag (`timelineLayout.dayOf`), gegen den „überfällig“ gilt. */
  today: number;
}) {
  const t = useT();
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
                    <Link to={todoDeepLink(todo)} className="text-sm text-text hover:text-accent truncate block"
                      title={todo.ratingNote ?? undefined}>
                      {todo.title}
                    </Link>
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
              {questions.map((todo) => <QuestionRow key={todo.id} todo={todo} />)}
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
                {decided.map((todo) => <DecidedRow key={todo.id} todo={todo} />)}
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

/** Eine offene Frage: TODO, Wortlaut, „Entscheiden“ öffnet die Karte (68a5e24e). */
function QuestionRow({ todo }: { todo: GoalTodoItem }) {
  const t = useT();
  const scope = useDecisionScope();
  const [open, setOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  return (
    <li className="flex items-start gap-3 px-4 py-2.5" data-testid="goal-question">
      <div className="min-w-0 flex-1">
        <Link to={todoDeepLink(todo)} className="text-sm text-text hover:text-accent">{todo.title}</Link>
        <p className="text-sm text-human mt-0.5">{todo.openQuestion}</p>
        {todo.decision && <p className="mt-0.5 text-xs text-text-tertiary">{t.goals.decision.earlier.replace('{text}', todo.decision)}</p>}
      </div>
      {scope?.enabled && (
        <button ref={buttonRef} type="button" aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen((was) => !was)}
          aria-label={t.goals.decision.decideFor.replace('{title}', todo.title)}
          className="shrink-0 rounded-md border border-human/40 bg-human-subtle px-2.5 py-1 text-xs font-medium text-human hover:border-human focus-ring">
          {t.goals.decision.decide}
        </button>
      )}
      {open && scope && <DecisionCard todo={todo} goalId={scope.goalId} triggerRef={buttonRef} onClose={() => setOpen(false)} />}
    </li>
  );
}

/** Ein entschiedenes TODO: Wortlaut der Entscheidung, wann und wer; ✔ öffnet „Ändern/Entfernen“. */
function DecidedRow({ todo }: { todo: GoalTodoItem }) {
  const t = useT();
  const td = t.goals.decision;
  const locale = useI18nStore((s) => s.locale);
  return (
    <li className="flex items-start gap-3 px-4 py-2.5" data-testid="goal-decided-item">
      <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <Link to={todoDeepLink(todo)} className="text-sm text-text hover:text-accent">{todo.title}</Link>
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
