import { Input, Select } from '../ui';
import { useT } from '../../i18n';
import { MAX_TITLE, rangeProblem, type WorkPackageDraft } from './workPackageForm';
import type { GoalProject } from '../../types';

/**
 * The fields of a work package (c60bf914) - in the dialog of the outline and in the card on the
 * timeline: title, project (locked once TODOs are attached to it), start and end. An
 * incomplete or reversed period appears as an error at the end field.
 */
export function WorkPackageFields({ draft, onChange, projects, locked, autoFocus = false }: {
  draft: WorkPackageDraft;
  onChange: (draft: WorkPackageDraft) => void;
  projects: GoalProject[];
  /** The project can no longer be changed. */
  locked: boolean;
  autoFocus?: boolean;
}) {
  const tw = useT().goals.workPackages;
  const options = projects.map((project) => ({ value: project.projectId, label: project.name }));
  // A package whose project no longer belongs to the goal keeps it as its selection
  if (draft.projectId && !options.some((option) => option.value === draft.projectId)) {
    options.push({ value: draft.projectId, label: draft.projectId });
  }
  const problem = rangeProblem(draft);
  const hasRange = draft.start !== '' || draft.end !== '';

  return (
    <div className="space-y-3">
      {/* The focus jumps in on purpose: dialog and card open on a click */}
      <Input label={tw.titleLabel} value={draft.title} maxLength={MAX_TITLE} autoFocus={autoFocus}
        onChange={(event) => onChange({ ...draft, title: event.target.value })} />
      {options.length === 0 ? (
        <p className="text-sm text-text-secondary">{tw.noProjects}</p>
      ) : (
        <div className="space-y-1">
          <Select label={tw.project} aria-label={tw.project} value={draft.projectId} options={options} disabled={locked}
            onChange={(event) => onChange({ ...draft, projectId: event.target.value })} />
          {locked && <p className="text-xs text-text-tertiary">{tw.projectLocked}</p>}
        </div>
      )}
      <div className="grid grid-cols-2 gap-2">
        <Input label={tw.start} type="date" value={draft.start}
          onChange={(event) => onChange({ ...draft, start: event.target.value })} />
        <Input label={tw.end} type="date" value={draft.end}
          error={problem === 'incomplete' ? tw.rangeIncomplete : problem === 'order' ? tw.rangeOrder : undefined}
          onChange={(event) => onChange({ ...draft, end: event.target.value })} />
      </div>
      {hasRange && (
        <button type="button" onClick={() => onChange({ ...draft, start: '', end: '' })}
          className="rounded text-xs text-text-secondary hover:text-danger focus-ring">
          {tw.clearRange}
        </button>
      )}
    </div>
  );
}
