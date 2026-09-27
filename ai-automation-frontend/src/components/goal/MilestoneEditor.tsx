import { useState, type FormEvent } from 'react';
import { Trash2 } from 'lucide-react';
import { Button, Modal } from '../ui';
import { useT } from '../../i18n';
import { useCreateMilestone, useUpdateMilestone } from '../../hooks/useGoalMilestones';
import { MilestoneFields } from './MilestoneFields';
import { ReachedSwitch } from './ReachedSwitch';
import {
  createMilestoneRequest, isMilestoneDraftValid, milestoneDraftOf, updateMilestoneRequest,
} from './milestoneForm';
import type { GoalMilestone, GoalProject, GoalWorkPackage } from '../../types';

/**
 * Meilenstein anlegen oder bearbeiten (c60bf914) – als Dialog der Gliederung: Titel, Datum,
 * verknüpfte Arbeitspakete; ohne Verknüpfung „erreicht“ von Hand. Löschen fragt der Aufrufer nach.
 */
export function MilestoneEditor({ goalId, milestone, workPackages, projects, day, onClose, onDelete }: {
  goalId: string;
  milestone?: GoalMilestone;
  workPackages: GoalWorkPackage[];
  projects: GoalProject[];
  /** Vorbelegtes Datum beim Anlegen. */
  day?: number | null;
  onClose: () => void;
  onDelete?: (milestone: GoalMilestone) => void;
}) {
  const tm = useT().goals.milestones;
  const [draft, setDraft] = useState(() => milestoneDraftOf(milestone, day));
  const create = useCreateMilestone(goalId);
  const update = useUpdateMilestone(goalId);

  const submit = (event?: FormEvent) => {
    event?.preventDefault();
    if (!milestone) {
      const request = createMilestoneRequest(draft);
      if (request) create.mutate(request, { onSuccess: onClose });
      return;
    }
    const data = updateMilestoneRequest(milestone, draft);
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
          <Button onClick={() => submit()} loading={create.isPending || update.isPending} disabled={!isMilestoneDraftValid(draft)}>
            {tm.save}
          </Button>
        </>
      }
    >
      <form onSubmit={submit} data-testid="milestone-editor">
        <MilestoneFields draft={draft} onChange={setDraft} workPackages={workPackages} projects={projects} autoFocus />
        <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
      </form>
      {milestone && <ReachedSwitch goalId={goalId} milestone={milestone} className="mt-3" />}
    </Modal>
  );
}
