import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Target } from 'lucide-react';
import { Button, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { Hint } from '../components/hints/Hint';
import { GoalCard } from '../components/goal/GoalCard';
import { GoalCreateForm } from '../components/goal/GoalCreateForm';
import { useGoals } from '../hooks/useGoals';
import { useT } from '../i18n';
import type { GoalStatus } from '../types';

const TABS: GoalStatus[] = ['ACTIVE', 'DONE', 'ARCHIVED'];

/**
 * All goals (TODO c6ea8f5c): active, reached and archived kept apart, plus creating. A goal
 * collects TODOs from several projects; the goal view shows order and relation map.
 */
export function Goals() {
  const t = useT();
  const navigate = useNavigate();
  const [tab, setTab] = useState<GoalStatus>('ACTIVE');
  const [creating, setCreating] = useState(false);
  const { data: goals = [], isLoading, error, refetch } = useGoals([tab]);

  const createButton = (
    <Button size="sm" onClick={() => setCreating(true)}>
      <Plus className="w-3.5 h-3.5 mr-1" />
      {t.goals.create}
    </Button>
  );

  return (
    <div className="space-y-6 animate-fade-in">
      <div className="flex flex-wrap items-center gap-3">
        <div className="p-2 rounded-xl bg-accent/10 text-accent">
          <Target className="w-5 h-5" />
        </div>
        <div className="flex-1 min-w-0">
          <h1 className="text-2xl font-bold text-text">{t.goals.title}</h1>
          <p className="text-sm text-text-secondary mt-0.5">{t.goals.subtitle}</p>
        </div>
        {!creating && createButton}
      </div>

      <Hint id="goals-overview" />

      {creating && (
        <GoalCreateForm onCancel={() => setCreating(false)} onCreated={(goal) => navigate(`/goals/${goal.id}`)} />
      )}

      <div role="tablist" aria-label={t.goals.title} className="inline-flex gap-1 p-1 rounded-lg bg-surface-secondary">
        {TABS.map((status) => (
          <button
            key={status}
            type="button"
            role="tab"
            aria-selected={tab === status}
            onClick={() => setTab(status)}
            className={`px-3 py-1.5 text-sm rounded-md transition-colors ${
              tab === status ? 'bg-surface text-text shadow-sm font-medium' : 'text-text-secondary hover:text-text'
            }`}
          >
            {t.goals.tabs[status]}
          </button>
        ))}
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center h-40">
          <Spinner text={t.common.loading} />
        </div>
      ) : error ? (
        <ErrorState message={error.message} onRetry={() => refetch()} />
      ) : goals.length === 0 ? (
        <EmptyState
          icon={<Target className="w-8 h-8 text-text-tertiary" />}
          title={t.goals.emptyTab[tab]}
          description={tab === 'ACTIVE' ? t.goals.empty : undefined}
          action={tab === 'ACTIVE' && !creating ? createButton : undefined}
        />
      ) : (
        <div role="tabpanel" className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {goals.map((goal) => (
            <GoalCard key={goal.id} goal={goal} onOpen={() => navigate(`/goals/${goal.id}`)} />
          ))}
        </div>
      )}
    </div>
  );
}
