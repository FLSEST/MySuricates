import { useId } from 'react';
import { Input } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { MAX_TITLE } from './workPackageForm';
import { togglePackage, type MilestoneDraft } from './milestoneForm';
import { rangeText } from './dragLabel';
import { packageRange } from './timelineLayout';
import { WorkPackageMark } from './WorkPackageMark';
import type { GoalProject, GoalWorkPackage } from '../../types';

/**
 * The fields of a milestone (c60bf914) — in the outline dialog and in the card on the
 * timeline: title, date and the linked work packages as a multi-select, grouped by project.
 * The milestone is reached as soon as all linked packages are completed.
 */
export function MilestoneFields({ draft, onChange, workPackages, projects, autoFocus = false }: {
  draft: MilestoneDraft;
  onChange: (draft: MilestoneDraft) => void;
  workPackages: GoalWorkPackage[];
  projects: GoalProject[];
  autoFocus?: boolean;
}) {
  const t = useT();
  const tm = t.goals.milestones;
  const tw = t.goals.workPackages;
  const noProject = t.goals.outline.noProject;
  const locale = useI18nStore((s) => s.locale);
  const legendId = useId();
  const groups = [
    ...projects.map((project) => ({ key: project.projectId, name: project.name })),
    ...(workPackages.some((item) => !item.projectId || !projects.some((p) => p.projectId === item.projectId))
      ? [{ key: '', name: noProject }] : []),
  ].map((group) => ({
    ...group,
    items: workPackages.filter((item) => (group.key
      ? item.projectId === group.key
      : !item.projectId || !projects.some((p) => p.projectId === item.projectId))),
  })).filter((group) => group.items.length > 0);

  return (
    <div className="space-y-3">
      {/* Focus deliberately jumps in: dialog and card open on a click */}
      <Input label={tm.titleLabel} value={draft.title} maxLength={MAX_TITLE} autoFocus={autoFocus}
        onChange={(event) => onChange({ ...draft, title: event.target.value })} />
      <Input label={tm.dateLabel} type="date" value={draft.date} required
        onChange={(event) => onChange({ ...draft, date: event.target.value })} />
      <fieldset aria-labelledby={legendId} className="space-y-1.5">
        <p id={legendId} className="text-sm font-medium text-text-secondary">{tm.packages}</p>
        <p className="text-xs text-text-tertiary">{tm.packagesHint}</p>
        {groups.length === 0 ? (
          <p className="text-xs text-text-tertiary">{tm.noPackages}</p>
        ) : (
          <div className="max-h-60 space-y-2 overflow-y-auto pr-1">
            {groups.map((group) => (
              <div key={group.key || 'none'} role="group" aria-label={group.name}>
                <p className="text-[11px] font-semibold text-text-tertiary">{group.name}</p>
                <ul className="mt-0.5 space-y-0.5">
                  {group.items.map((item) => {
                    const range = packageRange(item);
                    return (
                      <li key={item.id}>
                        <label className="flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-sm hover:bg-surface-hover">
                          <input type="checkbox" className="h-3.5 w-3.5 accent-[var(--color-accent)]"
                            checked={draft.workPackageIds.includes(item.id)}
                            onChange={() => onChange(togglePackage(draft, item.id))} />
                          <WorkPackageMark state={item.state} />
                          <span className="min-w-0 flex-1 truncate text-text" title={item.title}>{item.title}</span>
                          <span className="shrink-0 text-[11px] tabular-nums text-text-tertiary">
                            {range ? rangeText(range, locale) : tw.noRange}
                          </span>
                        </label>
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))}
          </div>
        )}
      </fieldset>
    </div>
  );
}
