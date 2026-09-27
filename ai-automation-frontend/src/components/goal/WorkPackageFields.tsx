import { Input, Select } from '../ui';
import { useT } from '../../i18n';
import { MAX_TITLE, rangeProblem, type WorkPackageDraft } from './workPackageForm';
import type { GoalProject } from '../../types';

/**
 * Die Felder eines Arbeitspakets (c60bf914) – im Dialog der Gliederung und in der Karte am
 * Zeitstrahl: Titel, Projekt (gesperrt, sobald TODOs daran hängen), Beginn und Ende. Ein
 * unvollständiger oder verdrehter Zeitraum steht als Fehler am Ende-Feld.
 */
export function WorkPackageFields({ draft, onChange, projects, locked, autoFocus = false }: {
  draft: WorkPackageDraft;
  onChange: (draft: WorkPackageDraft) => void;
  projects: GoalProject[];
  /** Das Projekt lässt sich nicht mehr wechseln. */
  locked: boolean;
  autoFocus?: boolean;
}) {
  const tw = useT().goals.workPackages;
  const options = projects.map((project) => ({ value: project.projectId, label: project.name }));
  // Ein Paket, dessen Projekt nicht mehr zum Ziel gehört, behält es als Auswahl
  if (draft.projectId && !options.some((option) => option.value === draft.projectId)) {
    options.push({ value: draft.projectId, label: draft.projectId });
  }
  const problem = rangeProblem(draft);
  const hasRange = draft.start !== '' || draft.end !== '';

  return (
    <div className="space-y-3">
      {/* Der Fokus springt bewusst hinein: Dialog und Karte öffnen sich auf einen Klick hin */}
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
