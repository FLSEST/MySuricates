import { useUpdateMilestone } from '../../hooks/useGoalMilestones';
import { useT } from '../../i18n';
import { reachedByHand } from './milestoneForm';
import type { GoalMilestone } from '../../types';

/**
 * „Erreicht“ von Hand (c60bf914) – nur für Meilensteine ohne verknüpfte Arbeitspakete; mit
 * Verknüpfung entscheiden die Pakete, dann steht hier der Stand „2/3 Pakete“. Schaltet sofort.
 */
export function ReachedSwitch({ goalId, milestone, className = '' }: { goalId: string; milestone: GoalMilestone; className?: string }) {
  const tm = useT().goals.milestones;
  const update = useUpdateMilestone(goalId);
  if (!reachedByHand(milestone)) {
    return (
      <p className={`text-xs text-text-secondary ${className}`} data-testid="milestone-packages-progress">
        {tm.packagesProgressLong.replace('{done}', String(milestone.packagesDone)).replace('{total}', String(milestone.packagesTotal))}
      </p>
    );
  }
  const reached = milestone.state === 'REACHED';
  return (
    <div className={`flex items-center gap-3 ${className}`}>
      <button type="button" role="switch" aria-checked={reached} disabled={update.isPending}
        onClick={() => update.mutate({ id: milestone.id, data: { reached: !reached } })}
        className={`relative inline-flex h-5 w-9 shrink-0 rounded-full border-2 border-transparent transition-colors focus-ring ${
          reached ? 'bg-success' : 'bg-border-strong'} disabled:opacity-50`}>
        <span className={`pointer-events-none inline-block h-4 w-4 rounded-full bg-surface-raised shadow transition-transform ${
          reached ? 'translate-x-4' : 'translate-x-0'}`} />
        <span className="sr-only">{tm.reached}</span>
      </button>
      <span className="text-sm text-text">{tm.reached}</span>
      <span className="text-xs text-text-tertiary">{tm.reachedHint}</span>
    </div>
  );
}
