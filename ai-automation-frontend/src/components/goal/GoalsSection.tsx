import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CalendarDays, HelpCircle, Plus, Target } from 'lucide-react';
import { Button, Card, Input } from '../ui';
import { TodoProgressBar } from '../todo/TodoProgressBar';
import { useCreateGoal, useGoals } from '../../hooks/useGoals';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import type { Goal } from '../../types';

/**
 * Ziele im Cockpit (TODO dc0566f8): je Ziel Fortschritt, Zieldatum und offene Fragen; ein Klick
 * öffnet die Zielansicht. Ein neues Ziel entsteht hier mit Titel, Datum und „fertig heißt …“.
 */
export function GoalsSection() {
  const t = useT();
  const navigate = useNavigate();
  const { data: goals = [], isLoading } = useGoals();
  const createGoal = useCreateGoal();
  const [creating, setCreating] = useState(false);
  const [title, setTitle] = useState('');
  const [targetDate, setTargetDate] = useState('');
  const [description, setDescription] = useState('');

  const submit = () => {
    if (!title.trim()) return;
    createGoal.mutate(
      { title: title.trim(), targetDate: targetDate || undefined, description: description.trim() || undefined },
      {
        onSuccess: (goal) => {
          setCreating(false);
          setTitle('');
          setTargetDate('');
          setDescription('');
          navigate(`/goals/${goal.id}`);
        },
      },
    );
  };

  return (
    <section aria-labelledby="goals-heading">
      <div className="flex items-center gap-2 mb-3">
        <Target className="w-4 h-4 text-accent" />
        <h2 id="goals-heading" className="text-sm font-semibold text-text uppercase tracking-wide">{t.goals.title}</h2>
        <span className="text-xs text-text-tertiary hidden sm:inline">{t.goals.subtitle}</span>
        <div className="ml-auto">
          {!creating && (
            <Button size="sm" variant="secondary" onClick={() => setCreating(true)}>
              <Plus className="w-3.5 h-3.5 mr-1" />
              {t.goals.create}
            </Button>
          )}
        </div>
      </div>

      {creating && (
        <Card className="mb-3 space-y-3">
          <Input label={t.goals.titleLabel} value={title} onChange={(e) => setTitle(e.target.value)} autoFocus />
          <Input label={t.goals.targetDateLabel} type="date" value={targetDate} onChange={(e) => setTargetDate(e.target.value)} />
          <div className="space-y-1.5">
            <label htmlFor="goal-description" className="block text-sm font-medium text-text-secondary">
              {t.goals.descriptionLabel}
            </label>
            <textarea
              id="goal-description"
              className="input-base w-full min-h-[64px] resize-y"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setCreating(false)}>{t.common.cancel}</Button>
            <Button onClick={submit} disabled={!title.trim() || createGoal.isPending}>{t.goals.save}</Button>
          </div>
        </Card>
      )}

      {!isLoading && goals.length === 0 && !creating && (
        <Card>
          <p className="text-sm text-text-secondary">{t.goals.empty}</p>
        </Card>
      )}

      {goals.length > 0 && (
        <div className="grid gap-3 md:grid-cols-2">
          {goals.map((goal) => (
            <GoalCard key={goal.id} goal={goal} onOpen={() => navigate(`/goals/${goal.id}`)} />
          ))}
        </div>
      )}
    </section>
  );
}

function GoalCard({ goal, onOpen }: { goal: Goal; onOpen: () => void }) {
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
