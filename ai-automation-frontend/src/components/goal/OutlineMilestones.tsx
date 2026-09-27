import { useId } from 'react';
import { Check, Pencil, Plus } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { MilestoneDiamond } from './MilestoneDiamond';
import type { GoalDetail, GoalMilestone } from '../../types';

/**
 * Die Meilensteine oben in der Gliederung (c60bf914): Termine fürs ganze Ziel, nach Datum – Raute,
 * Titel, Datum, Zustand und „2/3 Pakete“; ein Klick bearbeitet, „+ Meilenstein“ legt an. Bei einem
 * Backend vor c60bf914 steht hier nur, dass es sie nach dem Neustart gibt.
 */
export function OutlineMilestones({ detail, onAdd, onEdit }: {
  detail: GoalDetail;
  onAdd: () => void;
  onEdit: (milestone: GoalMilestone) => void;
}) {
  const t = useT();
  const to = t.goals.outline;
  const tm = t.goals.milestones;
  const headingId = useId();
  if (detail.legacyBackend) {
    return <p className="text-xs text-text-tertiary" data-testid="outline-milestones-legacy">{tm.legacy}</p>;
  }
  const { milestones } = detail;
  return (
    <section aria-labelledby={headingId} data-testid="outline-milestones"
      className="rounded-xl border border-card-border bg-surface-raised px-3 py-2.5 sm:px-4">
      <div className="flex items-center gap-2">
        <h3 id={headingId} className="text-[15px] font-semibold text-text">{to.milestones}</h3>
        <button type="button" onClick={onAdd} aria-label={tm.create}
          className="ml-auto inline-flex items-center gap-1 rounded-md border border-dashed border-border-strong px-2.5 py-1 text-xs font-medium text-text-secondary hover:border-accent hover:text-accent focus-ring">
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          {to.addMilestone}
        </button>
      </div>
      {milestones.length === 0 ? (
        <p className="mt-1.5 max-w-prose text-sm text-text-secondary">{to.milestonesEmpty}</p>
      ) : (
        <ol className="mt-1.5 divide-y divide-border">
          {milestones.map((milestone) => <MilestoneLine key={milestone.id} milestone={milestone} onEdit={() => onEdit(milestone)} />)}
        </ol>
      )}
    </section>
  );
}

function MilestoneLine({ milestone, onEdit }: { milestone: GoalMilestone; onEdit: () => void }) {
  const t = useT();
  const tm = t.goals.milestones;
  const locale = useI18nStore((s) => s.locale);
  const reached = milestone.state === 'REACHED';
  const label = t.goals.outline.editMilestone.replace('{title}', milestone.title);
  return (
    <li className="flex items-center gap-2.5 py-1.5" data-testid={`outline-milestone-${milestone.id}`}>
      <MilestoneDiamond state={milestone.state} />
      <button type="button" onClick={onEdit} aria-label={label} title={milestone.title}
        className="min-w-0 flex-1 rounded text-left focus-ring">
        <span className={`block truncate text-sm font-medium ${reached ? 'text-text-secondary' : 'text-text'}`}>{milestone.title}</span>
        <span className={`flex flex-wrap gap-x-2 text-[11px] ${milestone.state === 'OVERDUE' ? 'text-danger' : 'text-text-tertiary'}`}>
          <span className="tabular-nums">{formatDate(milestone.dueDate, locale)}</span>
          {milestone.state !== 'OPEN' && <span>{tm.states[milestone.state]}</span>}
          {milestone.reopened && <span>{tm.reopened}</span>}
        </span>
      </button>
      <span className="w-20 shrink-0 text-right text-xs tabular-nums text-text-secondary">
        {milestone.packagesTotal > 0 ? (
          <>
            <span aria-hidden="true">{tm.packagesProgress.replace('{done}', String(milestone.packagesDone))
              .replace('{total}', String(milestone.packagesTotal))}</span>
            <span className="sr-only">{tm.packagesProgressLong.replace('{done}', String(milestone.packagesDone))
              .replace('{total}', String(milestone.packagesTotal))}</span>
          </>
        ) : reached && <Check className="ml-auto h-3.5 w-3.5 text-success" role="img" aria-label={tm.states.REACHED} />}
      </span>
      {/* Nur für die Maus: der Titel davor ist schon der Knopf zum Bearbeiten */}
      <button type="button" onClick={onEdit} aria-hidden="true" title={tm.edit} tabIndex={-1}
        className="rounded p-1 text-text-tertiary hover:bg-surface-hover hover:text-text">
        <Pencil className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </li>
  );
}
