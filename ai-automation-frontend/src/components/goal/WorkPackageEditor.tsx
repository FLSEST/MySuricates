import { useState, type FormEvent } from 'react';
import { Trash2 } from 'lucide-react';
import { Button, Modal } from '../ui';
import { useT } from '../../i18n';
import { useCreateWorkPackage, useUpdateWorkPackage } from '../../hooks/useGoalWorkPackages';
import { WorkPackageFields } from './WorkPackageFields';
import {
  createPackageRequest, isPackageDraftValid, packageDraftOf, projectLocked, updatePackageRequest,
} from './workPackageForm';
import type { GoalProject, GoalWorkPackage } from '../../types';

/**
 * Arbeitspaket anlegen oder bearbeiten (c60bf914) – als Dialog der Gliederung: Titel, Projekt des
 * Ziels, Zeitraum optional. Löschen fragt der Aufrufer in seinem eigenen Dialog nach.
 */
export function WorkPackageEditor({ goalId, projects, workPackage, projectId, onClose, onDelete }: {
  goalId: string;
  projects: GoalProject[];
  workPackage?: GoalWorkPackage;
  /** Vorbelegtes Projekt beim Anlegen, etwa aus der Projektkarte. */
  projectId?: string | null;
  onClose: () => void;
  onDelete?: (workPackage: GoalWorkPackage) => void;
}) {
  const tw = useT().goals.workPackages;
  const [draft, setDraft] = useState(() => packageDraftOf(workPackage, { projectId: projectId ?? projects[0]?.projectId ?? '' }));
  const create = useCreateWorkPackage(goalId);
  const update = useUpdateWorkPackage(goalId);

  const submit = (event?: FormEvent) => {
    event?.preventDefault();
    if (!workPackage) {
      const request = createPackageRequest(draft);
      if (request) create.mutate(request, { onSuccess: onClose });
      return;
    }
    const data = updatePackageRequest(workPackage, draft);
    if (!data) return;
    if (Object.keys(data).length === 0) onClose();
    else update.mutate({ id: workPackage.id, data }, { onSuccess: onClose });
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={workPackage ? tw.edit : tw.create}
      size="sm"
      footer={
        <>
          {workPackage && onDelete && (
            <div className="mr-auto">
              <Button variant="ghost" icon={<Trash2 className="h-4 w-4" />} onClick={() => onDelete(workPackage)}
                className="text-danger hover:bg-danger-subtle hover:text-danger">
                {tw.delete}
              </Button>
            </div>
          )}
          <Button variant="secondary" onClick={onClose}>{tw.cancel}</Button>
          <Button onClick={() => submit()} loading={create.isPending || update.isPending}
            disabled={!isPackageDraftValid(draft) || projects.length === 0}>
            {tw.save}
          </Button>
        </>
      }
    >
      <form onSubmit={submit} data-testid="work-package-editor">
        <WorkPackageFields draft={draft} onChange={setDraft} projects={projects} locked={projectLocked(workPackage)} autoFocus />
        {/* Enter im Titel speichert */}
        <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}
