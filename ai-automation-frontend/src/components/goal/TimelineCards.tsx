import { useState, type FormEvent, type RefObject } from 'react';
import { Button } from '../ui';
import { useT } from '../../i18n';
import { useUpdateWorkPackage } from '../../hooks/useGoalWorkPackages';
import { useCreateMilestone, useUpdateMilestone } from '../../hooks/useGoalMilestones';
import { TimelinePopover } from './TimelinePopover';
import { WorkPackageFields } from './WorkPackageFields';
import { MilestoneFields } from './MilestoneFields';
import { ReachedSwitch } from './ReachedSwitch';
import { PackageWorkflowList } from './GoalWorkflowsPanel';
import { useGoalWorkflowsScope } from './goalWorkflowsContext';
import { WorkPackageMark } from './WorkPackageMark';
import { MilestoneDiamond } from './MilestoneDiamond';
import { isPackageDraftValid, packageDraftOf, projectLocked, updatePackageRequest } from './workPackageForm';
import {
  createMilestoneRequest, isMilestoneDraftValid, milestoneDraftOf, updateMilestoneRequest,
} from './milestoneForm';
import type { FloatingAlign, FloatingAnchor } from './floating';
import type { GoalMilestone, GoalProject, GoalTodoItem, GoalWorkPackage } from '../../types';

/** Wo die Karte schwebt (`floating.ts`) und wer sie geöffnet hat – er bekommt danach den Fokus zurück. */
interface Placement {
  anchor?: FloatingAnchor;
  align?: FloatingAlign;
  triggerRef?: RefObject<HTMLElement | null>;
}

const DELETE_BUTTON = 'btn-base mr-auto px-3 py-1.5 text-xs text-danger hover:bg-danger-subtle';

/**
 * Karte eines Arbeitspakets am Zeitstrahl (c60bf914) – nach Klick oder Enter auf Balken, Zeilennamen
 * oder Eintrag der Leiste: Titel, Projekt, Beginn und Ende, Stand und die TODOs. Speichern schickt
 * nur, was sich geändert hat; Löschen fragt der Zeitstrahl in seinem eigenen Dialog nach.
 */
export function WorkPackageCard({ goalId, workPackage, projects, todos, onClose, onDelete, ...placement }: {
  goalId: string;
  workPackage: GoalWorkPackage;
  projects: GoalProject[];
  todos: GoalTodoItem[];
  onClose: () => void;
  onDelete: () => void;
} & Placement) {
  const t = useT();
  const tw = t.goals.workPackages;
  const [draft, setDraft] = useState(() => packageDraftOf(workPackage));
  const update = useUpdateWorkPackage(goalId);
  const assigned = todos.filter((todo) => todo.workPackageId === workPackage.id && todo.status !== 'ARCHIVED');
  const workflows = useGoalWorkflowsScope();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const data = updatePackageRequest(workPackage, draft);
    if (!data) return;
    if (Object.keys(data).length === 0) onClose();
    else update.mutate({ id: workPackage.id, data }, { onSuccess: onClose });
  };

  return (
    <TimelinePopover label={tw.card.replace('{title}', workPackage.title)} onClose={onClose} wide {...placement}>
      <form onSubmit={submit} className="space-y-3" data-testid="work-package-card">
        <p className="flex items-center gap-2 text-xs text-text-secondary">
          <WorkPackageMark state={workPackage.state} />
          <span>{tw.states[workPackage.state]}</span>
          <span className="tabular-nums">
            {tw.progress.replace('{done}', String(workPackage.done)).replace('{total}', String(workPackage.total))}
          </span>
          {workPackage.reopened && <span className="text-warning">{tw.reopened}</span>}
        </p>
        <WorkPackageFields draft={draft} onChange={setDraft} projects={projects} locked={projectLocked(workPackage)} />
        <div>
          <p className="text-xs font-medium text-text-secondary">{tw.todos}</p>
          {assigned.length === 0 ? (
            <p className="text-xs text-text-tertiary">{tw.noTodos}</p>
          ) : (
            <ul className="mt-1 max-h-28 space-y-0.5 overflow-y-auto text-xs">
              {assigned.map((todo) => (
                <li key={todo.id} title={todo.title}
                  className={`truncate ${todo.status === 'DONE' ? 'text-text-tertiary line-through' : 'text-text'}`}>
                  {todo.title}
                </li>
              ))}
            </ul>
          )}
        </div>
        <PackageWorkflowList workPackageId={workPackage.id} onAssign={() => {
          onClose();
          workflows?.openAssign(workPackage.id);
        }} />
        <div className="flex items-center gap-2 border-t border-border pt-2">
          <button type="button" onClick={onDelete} className={DELETE_BUTTON}>{tw.delete}</button>
          <Button type="button" variant="ghost" size="sm" onClick={onClose}>{tw.cancel}</Button>
          <Button type="submit" size="sm" loading={update.isPending} disabled={!isPackageDraftValid(draft)}>{tw.save}</Button>
        </div>
      </form>
    </TimelinePopover>
  );
}

/**
 * Karte eines Meilensteins am Zeitstrahl (c60bf914): Titel, Datum, verknüpfte Arbeitspakete nach
 * Projekt, Stand „2/3 Pakete“ – ohne Verknüpfung stattdessen „erreicht“ von Hand – und Löschen. Ohne
 * `milestone` legt sie einen neuen an (vorbelegt mit `day`).
 */
export function MilestoneCard({ goalId, milestone, day, workPackages, projects, onClose, onDelete, ...placement }: {
  goalId: string;
  milestone?: GoalMilestone;
  day?: number | null;
  workPackages: GoalWorkPackage[];
  projects: GoalProject[];
  onClose: () => void;
  onDelete?: () => void;
} & Placement) {
  const t = useT();
  const tm = t.goals.milestones;
  const [draft, setDraft] = useState(() => milestoneDraftOf(milestone, day));
  const create = useCreateMilestone(goalId);
  const update = useUpdateMilestone(goalId);

  const submit = (event: FormEvent) => {
    event.preventDefault();
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
    <TimelinePopover label={milestone ? tm.card.replace('{title}', milestone.title) : tm.create} onClose={onClose} wide {...placement}>
      <form onSubmit={submit} className="space-y-3" data-testid="milestone-card">
        <p className="flex items-center gap-2 text-xs text-text-secondary">
          {milestone ? (
            <>
              <MilestoneDiamond state={milestone.state} />
              <span>{tm.states[milestone.state]}</span>
              {milestone.reopened && <span className="text-warning">{tm.reopened}</span>}
            </>
          ) : <span className="text-sm font-semibold text-text">{tm.create}</span>}
        </p>
        <MilestoneFields draft={draft} onChange={setDraft} workPackages={workPackages} projects={projects} />
        {milestone && <ReachedSwitch goalId={goalId} milestone={milestone} />}
        <div className="flex items-center gap-2 border-t border-border pt-2">
          {milestone && onDelete && <button type="button" onClick={onDelete} className={DELETE_BUTTON}>{tm.delete}</button>}
          <Button type="button" variant="ghost" size="sm" onClick={onClose} className={milestone ? '' : 'ml-auto'}>{tm.cancel}</Button>
          <Button type="submit" size="sm" loading={create.isPending || update.isPending} disabled={!isMilestoneDraftValid(draft)}>
            {tm.save}
          </Button>
        </div>
      </form>
    </TimelinePopover>
  );
}
