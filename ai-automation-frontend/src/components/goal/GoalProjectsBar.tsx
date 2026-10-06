import { forwardRef, useId, useState } from 'react';
import { FolderOpen, Unlink, X } from 'lucide-react';
import { ConfirmModal } from '../ui';
import { useT } from '../../i18n';
import { apiErrorStatus } from '../../api/client';
import { useProjects } from '../../hooks/useProjects';
import { useAddGoalProject, useRemoveGoalProject } from '../../hooks/useGoalProjects';
import type { GoalProject, GoalWorkPackage } from '../../types';

/** The server's text for an error — for 409 it names what still hangs on the project. */
function serverText(err: unknown): string | null {
  const text = err && typeof err === 'object' && 'error' in err ? (err as { error: unknown }).error : null;
  return typeof text === 'string' && text ? text : null;
}

/**
 * Selection „Projekt hinzufügen“: all non-archived projects that the goal still lacks. A
 * choice assigns immediately. Without another project the selection disappears.
 */
export const AddProjectPicker = forwardRef<HTMLSelectElement, { goalId: string; linked: GoalProject[]; className?: string }>(
  function AddProjectPicker({ goalId, linked, className = '' }, ref) {
    const tp = useT().goals.projects;
    const { data: all = [] } = useProjects();
    const add = useAddGoalProject(goalId);
    const known = new Set(linked.map((project) => project.projectId));
    const available = all.filter((project) => !project.archivedAt && !known.has(project.id));
    if (available.length === 0) return null;
    return (
      <select ref={ref} aria-label={tp.add} value="" disabled={add.isPending}
        onChange={(event) => event.target.value && add.mutate(event.target.value)}
        className={`input-base w-auto max-w-full cursor-pointer py-1 text-xs ${className}`}>
        <option value="">{`+ ${tp.add}`}</option>
        {available.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
      </select>
    );
  },
);

/**
 * A goal's projects (c60bf914) as chips with status, plus „Projekt hinzufügen“. Removing asks
 * for confirmation and first names what hangs on it (from the detail). If the server still answers with 409,
 * the same dialog offers the cascade: delete work packages, detach TODOs from the goal.
 */
export function GoalProjectsBar({ goalId, projects, workPackages }: {
  goalId: string;
  projects: GoalProject[];
  workPackages: GoalWorkPackage[];
}) {
  const t = useT();
  const tp = t.goals.projects;
  const labelId = useId();
  const [removing, setRemoving] = useState<GoalProject | null>(null);
  const [conflict, setConflict] = useState<string | null>(null);
  const remove = useRemoveGoalProject(goalId);

  const close = () => {
    setRemoving(null);
    setConflict(null);
  };
  const confirm = () => {
    if (!removing) return;
    remove.mutate({ projectId: removing.projectId, cascade: conflict !== null }, {
      onSuccess: close,
      onError: (err) => {
        if (apiErrorStatus(err) === 409) setConflict(serverText(err) ?? '');
        else close();
      },
    });
  };
  const packageCount = (project: GoalProject) => workPackages.filter((item) => item.projectId === project.projectId).length;

  return (
    <div role="group" aria-labelledby={labelId} className="flex flex-wrap items-center gap-2" data-testid="goal-projects">
      <span id={labelId} className="sr-only">{tp.label}</span>
      {projects.map((project) => (
        <span key={project.projectId}
          className="inline-flex max-w-full items-center gap-1.5 rounded-full border border-border bg-surface py-0.5 pl-2.5 pr-1 text-xs text-text">
          <FolderOpen className="h-3.5 w-3.5 shrink-0 text-text-tertiary" aria-hidden="true" />
          <span className="min-w-0 truncate font-medium" title={project.name}>{project.name}</span>
          {project.archived && <span className="text-text-tertiary">({tp.archived})</span>}
          <span className="tabular-nums text-text-tertiary"
            title={tp.progress.replace('{done}', String(project.done)).replace('{total}', String(project.total))}>
            <span aria-hidden="true">{project.done}/{project.total}</span>
            <span className="sr-only">{tp.progress.replace('{done}', String(project.done)).replace('{total}', String(project.total))}</span>
          </span>
          <button type="button" onClick={() => setRemoving(project)} aria-label={tp.remove.replace('{name}', project.name)}
            title={tp.remove.replace('{name}', project.name)}
            className="rounded-full p-1 text-text-tertiary hover:bg-danger-subtle hover:text-danger focus-ring">
            <X className="h-3 w-3" aria-hidden="true" />
          </button>
        </span>
      ))}
      <AddProjectPicker goalId={goalId} linked={projects} />

      <ConfirmModal
        open={removing !== null}
        onClose={close}
        onConfirm={confirm}
        title={tp.removeTitle}
        confirmLabel={conflict !== null ? tp.cascadeAction : tp.removeAction}
        confirmIcon={<Unlink className="h-4 w-4" />}
        variant="danger"
        isPending={remove.isPending}
      >
        {removing && (
          <div className="space-y-2 text-sm text-text-secondary">
            <p className="text-text">{tp.removeConfirm.replace('{name}', () => removing.name)}</p>
            <p>
              {packageCount(removing) === 0 && removing.total === 0
                ? tp.removeEmpty
                : tp.removeCounts.replace('{packages}', String(packageCount(removing))).replace('{todos}', String(removing.total))}
            </p>
            {conflict !== null && (
              <div role="alert" className="space-y-1 rounded-lg border border-warning/40 bg-warning-subtle p-3">
                {conflict && <p className="text-text">{conflict}</p>}
                <p>{tp.cascadeHint}</p>
              </div>
            )}
          </div>
        )}
      </ConfirmModal>
    </div>
  );
}
