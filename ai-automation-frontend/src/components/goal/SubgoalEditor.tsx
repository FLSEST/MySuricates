import { useState, type FormEvent } from 'react';
import { Trash2 } from 'lucide-react';
import { Button, Input, Modal, Select } from '../ui';
import { useT } from '../../i18n';
import { useCreateMilestone, useUpdateMilestone } from '../../hooks/useGoalTimeline';
import { MAX_SUBGOAL_TITLE, createRequest, draftOf, isDraftValid, projectLocked, updateRequest } from './subgoalForm';
import type { GoalMilestone, GoalProject } from '../../types';

/**
 * Teilziel anlegen oder bearbeiten (c60bf914) – als Dialog, damit sich Teilziele auch außerhalb des
 * Zeitstrahls anlegen lassen: Titel, Projekt des Ziels, Datum optional. Löschen fragt der Aufrufer
 * in seinem eigenen Dialog nach.
 */
export function SubgoalEditor({ goalId, projects, milestone, projectId, onClose, onDelete }: {
  goalId: string;
  projects: GoalProject[];
  milestone?: GoalMilestone;
  /** Vorbelegtes Projekt beim Anlegen, etwa aus der Projektkarte. */
  projectId?: string | null;
  onClose: () => void;
  onDelete?: (milestone: GoalMilestone) => void;
}) {
  const t = useT();
  const tm = t.goals.milestones;
  const [draft, setDraft] = useState(() => draftOf(milestone, { projectId: projectId ?? projects[0]?.projectId ?? '' }));
  const create = useCreateMilestone(goalId);
  const update = useUpdateMilestone(goalId);
  const locked = projectLocked(milestone);
  const options = projects.map((project) => ({ value: project.projectId, label: project.name }));
  // Ein Teilziel, dessen Projekt nicht mehr zum Ziel gehört, behält es als Auswahl
  if (draft.projectId && !options.some((option) => option.value === draft.projectId)) {
    options.push({ value: draft.projectId, label: draft.projectId });
  }

  const submit = (event?: FormEvent) => {
    event?.preventDefault();
    if (!milestone) {
      const request = createRequest(draft);
      if (request) create.mutate(request, { onSuccess: onClose });
      return;
    }
    const data = updateRequest(milestone, draft);
    if (!data) return;
    if (Object.keys(data).length === 0) onClose();
    else update.mutate({ id: milestone.id, data }, { onSuccess: onClose });
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={milestone ? tm.edit : tm.create}
      size="sm"
      footer={
        <>
          {milestone && onDelete && (
            <div className="mr-auto">
              <Button variant="ghost" icon={<Trash2 className="h-4 w-4" />} onClick={() => onDelete(milestone)}
                className="text-danger hover:bg-danger-subtle hover:text-danger">
                {tm.delete}
              </Button>
            </div>
          )}
          <Button variant="secondary" onClick={onClose}>{tm.cancel}</Button>
          <Button onClick={() => submit()} loading={create.isPending || update.isPending}
            disabled={!isDraftValid(draft) || options.length === 0}>
            {tm.save}
          </Button>
        </>
      }
    >
      <form onSubmit={submit} className="space-y-3" data-testid="subgoal-editor">
        <Input label={tm.titleLabel} value={draft.title} maxLength={MAX_SUBGOAL_TITLE} autoFocus
          onChange={(event) => setDraft({ ...draft, title: event.target.value })} />
        {options.length === 0 ? (
          <p className="text-sm text-text-secondary">{tm.noProjects}</p>
        ) : (
          <div className="space-y-1">
            <Select label={tm.project} aria-label={tm.project} value={draft.projectId} options={options} disabled={locked}
              onChange={(event) => setDraft({ ...draft, projectId: event.target.value })} />
            {locked && <p className="text-xs text-text-tertiary">{tm.projectLocked}</p>}
          </div>
        )}
        <Input label={tm.dateOptional} type="date" value={draft.date}
          onChange={(event) => setDraft({ ...draft, date: event.target.value })} />
        {/* Enter im Titel speichert */}
        <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}
