import { useId, useRef, useState, type RefObject } from 'react';
import { CheckCircle2, HelpCircle } from 'lucide-react';
import { Button } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { useDecideTodo } from '../../hooks/useGoalDecisions';
import { TimelinePopover } from './TimelinePopover';
import { useDecisionScope } from './decisionContext';
import type { GoalTodoItem } from '../../types';

type Decidable = Pick<GoalTodoItem, 'id' | 'title' | 'status' | 'openQuestion' | 'decision' | 'decidedAt' | 'decidedBy' | 'decisionState'>;

/** Does the TODO show a mark? ❓ only while it is not done; ✔ always. */
function markOf(todo: Decidable): 'QUESTION' | 'DECIDED' | null {
  // Without a state (data without normalization) an open question counts as QUESTION
  const state = todo.decisionState ?? (todo.openQuestion ? 'QUESTION' : 'NONE');
  if (state === 'DECIDED') return 'DECIDED';
  if (state === 'QUESTION' && todo.openQuestion && todo.status !== 'DONE') return 'QUESTION';
  return null;
}

/** The wording for tooltip and screen reader: question or decision with date and who. */
function useMarkText(todo: Decidable) {
  const t = useT();
  const td = t.goals.decision;
  const locale = useI18nStore((s) => s.locale);
  const meta = todo.decidedAt
    ? td.meta.replace('{date}', formatDate(todo.decidedAt, locale)).replace('{who}', todo.decidedBy ? td.by[todo.decidedBy] : '')
      .replace(/, $/, '')
    : '';
  const decided = todo.decision ? `${td.decided.replace('{text}', todo.decision)}${meta ? ` (${meta})` : ''}` : '';
  return { meta, decided, question: td.question.replace('{text}', todo.openQuestion ?? '') };
}

const ICON = { sm: 'h-3 w-3', md: 'h-3.5 w-3.5' } as const;

/**
 * ❓ or ✔ on a TODO (68a5e24e): ❓ `text-human` = a person must clarify a question, ✔
 * `text-success` = decided. On the goal page (with `DecisionContext`) a click opens the card
 * „Entscheiden“ or „Entscheidung ändern/entfernen“; otherwise it is display only with a tooltip.
 */
export function DecisionMark({ todo, size = 'md', interactive = true }: { todo: Decidable; size?: 'sm' | 'md'; interactive?: boolean }) {
  const t = useT();
  const td = t.goals.decision;
  const scope = useDecisionScope();
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const text = useMarkText(todo);
  const mark = markOf(todo);
  if (!mark) return null;
  const Icon = mark === 'QUESTION' ? HelpCircle : CheckCircle2;
  const tone = mark === 'QUESTION' ? 'text-human' : 'text-success';
  const tooltip = mark === 'QUESTION'
    ? [text.question, todo.decision ? td.earlier.replace('{text}', todo.decision) : ''].filter(Boolean).join('\n')
    : text.decided;

  if (!interactive || !scope?.enabled) {
    return (
      <Icon className={`${ICON[size]} shrink-0 ${tone}`} role="img" aria-label={tooltip} data-testid="decision-mark"
        data-state={mark}>
        <title>{tooltip}</title>
      </Icon>
    );
  }
  const action = mark === 'QUESTION' ? td.decideFor : td.changeFor;
  return (
    <>
      <button ref={triggerRef} type="button" title={tooltip} aria-haspopup="dialog" aria-expanded={open}
        aria-label={`${tooltip} – ${action.replace('{title}', todo.title)}`} data-testid="decision-mark" data-state={mark}
        onClick={() => setOpen((was) => !was)}
        className={`inline-flex shrink-0 items-center rounded p-0.5 ${tone} hover:bg-surface-hover focus-ring`}>
        <Icon className={ICON[size]} aria-hidden="true" />
      </button>
      {open && <DecisionCard todo={todo} goalId={scope.goalId} triggerRef={triggerRef} onClose={() => setOpen(false)} />}
    </>
  );
}

/**
 * The card for deciding: for an open question the question and a field for the answer; for a
 * decision its wording with date and who, plus „Ändern“ and „Entfernen“.
 */
export function DecisionCard({ todo, goalId, triggerRef, onClose }: {
  todo: Decidable;
  goalId: string;
  triggerRef: RefObject<HTMLElement | null>;
  onClose: () => void;
}) {
  const t = useT();
  const td = t.goals.decision;
  const fieldId = useId();
  const hintId = useId();
  const decide = useDecideTodo(goalId);
  const text = useMarkText(todo);
  const asking = todo.decisionState !== 'DECIDED';
  const [editing, setEditing] = useState(asking);
  const [draft, setDraft] = useState(asking ? '' : todo.decision ?? '');
  const submit = (decision: string) => decide.mutate({ todoId: todo.id, decision: decision.trim() }, { onSuccess: onClose });

  return (
    <TimelinePopover label={td.card.replace('{title}', todo.title)} onClose={onClose} triggerRef={triggerRef} wide>
      <div className="space-y-2.5 text-sm" data-testid="decision-card">
        <p className="font-medium text-text">{todo.title}</p>
        {asking ? (
          <>
            <p className="flex gap-1.5 text-human"><HelpCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              <span>{todo.openQuestion}</span></p>
            {todo.decision && <p className="text-xs text-text-tertiary">{td.earlier.replace('{text}', todo.decision)}</p>}
          </>
        ) : !editing && (
          <div className="space-y-0.5">
            <p className="flex gap-1.5 text-text"><CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success" aria-hidden="true" />
              <span>{todo.decision}</span></p>
            {text.meta && <p className="pl-5 text-xs text-text-tertiary">{text.meta}</p>}
          </div>
        )}
        {editing && (
          <form className="space-y-1.5" onSubmit={(event) => {
            event.preventDefault();
            if (draft.trim() && !decide.isPending) submit(draft);
          }}>
            <label htmlFor={fieldId} className="block text-xs font-medium text-text-secondary">{td.label}</label>
            <textarea id={fieldId} rows={3} value={draft} placeholder={td.placeholder} aria-describedby={asking ? hintId : undefined}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
                  event.preventDefault();
                  event.currentTarget.form?.requestSubmit();
                }
              }}
              className="input-base w-full resize-y text-sm" />
            {asking && <p id={hintId} className="text-xs text-text-tertiary">{td.closesQuestion}</p>}
            <div className="flex justify-end gap-2 pt-1">
              <Button type="button" size="sm" variant="ghost" onClick={asking ? onClose : () => setEditing(false)}>{td.cancel}</Button>
              <Button type="submit" size="sm" loading={decide.isPending} disabled={!draft.trim() || draft.trim() === (todo.decision ?? '') && !asking}>
                {asking ? td.save : td.saveChange}
              </Button>
            </div>
          </form>
        )}
        {!asking && !editing && (
          <div className="flex justify-between gap-2 pt-1">
            <button type="button" disabled={decide.isPending} onClick={() => submit('')}
              className="rounded px-2 py-1 text-xs font-medium text-danger hover:bg-danger-subtle focus-ring disabled:opacity-50">
              {td.remove}
            </button>
            <Button size="sm" variant="secondary" onClick={() => setEditing(true)}>{td.change}</Button>
          </div>
        )}
      </div>
    </TimelinePopover>
  );
}
