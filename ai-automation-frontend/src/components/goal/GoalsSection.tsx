import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowRight, Plus, Target } from 'lucide-react';
import { Button, Card } from '../ui';
import { useGoals } from '../../hooks/useGoals';
import { useT } from '../../i18n';
import { GoalCard } from './GoalCard';
import { GoalCreateForm } from './GoalCreateForm';

/** So viele aktive Ziele zeigt das Cockpit; alle stehen unter „Ziele“. */
const COCKPIT_LIMIT = 4;

/**
 * Ziele im Cockpit (TODO dc0566f8), kompakt: die aktiven Ziele mit Fortschritt, Zieldatum und
 * offenen Fragen, dazu Anlegen und der Weg zur Übersicht aller Ziele (TODO c6ea8f5c).
 */
export function GoalsSection() {
  const t = useT();
  const navigate = useNavigate();
  const { data: goals = [], isLoading } = useGoals();
  const [creating, setCreating] = useState(false);
  const active = goals.filter((goal) => goal.status === 'ACTIVE');
  const shown = active.slice(0, COCKPIT_LIMIT);

  return (
    <section aria-labelledby="goals-heading">
      <div className="flex items-center gap-2 mb-3">
        <Target className="w-4 h-4 text-accent" />
        <h2 id="goals-heading" className="text-sm font-semibold text-text uppercase tracking-wide">{t.goals.title}</h2>
        <span className="text-xs text-text-tertiary hidden sm:inline">{t.goals.subtitle}</span>
        <div className="ml-auto flex items-center gap-3">
          <Link to="/goals" className="inline-flex items-center gap-1 text-xs text-text-secondary hover:text-accent">
            {t.goals.allGoals}
            <ArrowRight className="w-3 h-3" />
          </Link>
          {!creating && (
            <Button size="sm" variant="secondary" onClick={() => setCreating(true)}>
              <Plus className="w-3.5 h-3.5 mr-1" />
              {t.goals.create}
            </Button>
          )}
        </div>
      </div>

      {creating && (
        <GoalCreateForm onCancel={() => setCreating(false)} onCreated={(goal) => navigate(`/goals/${goal.id}`)} />
      )}

      {!isLoading && shown.length === 0 && !creating && (
        <Card>
          <p className="text-sm text-text-secondary">{goals.length === 0 ? t.goals.empty : t.goals.noActive}</p>
        </Card>
      )}

      {shown.length > 0 && (
        <div className="grid gap-3 md:grid-cols-2">
          {shown.map((goal) => (
            <GoalCard key={goal.id} goal={goal} onOpen={() => navigate(`/goals/${goal.id}`)} />
          ))}
        </div>
      )}
    </section>
  );
}
