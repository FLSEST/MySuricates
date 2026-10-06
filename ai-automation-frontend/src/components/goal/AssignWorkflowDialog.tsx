import { useId, useMemo, useState } from 'react';
import { Gauge, Hammer, Search } from 'lucide-react';
import { Button, Modal, Spinner, Toggle } from '../ui';
import { useT } from '../../i18n';
import { useWorkflows } from '../../hooks/useWorkflows';
import { useAttachGoalWorkflow } from '../../hooks/useGoalWorkflows';
import type { GoalProject, GoalWorkPackage, GoalWorkflow, GoalWorkflowRole } from '../../types';

const ROLE_ICON = { WORKER: Hammer, STATUS: Gauge } as const;

/**
 * „Workflow zuordnen“ (52bc1e8c): attach a workflow to the whole goal or to a work package, with
 * a role. The workflows from the goal's projects are offered first; „Alle Workflows zeigen“
 * opens the rest. What is already attached to the chosen goal is greyed out.
 */
export function AssignWorkflowDialog({ goalId, projects, workPackages, attachments, initialTarget, onClose }: {
  goalId: string;
  projects: GoalProject[];
  workPackages: GoalWorkPackage[];
  attachments: GoalWorkflow[];
  /** Vorbelegtes Paket; null = ganzes Ziel. */
  initialTarget: string | null;
  onClose: () => void;
}) {
  const t = useT();
  const tw = t.goals.workflows;
  const searchId = useId();
  const targetId = useId();
  const { data: workflows, isLoading } = useWorkflows();
  const attach = useAttachGoalWorkflow(goalId);
  const [query, setQuery] = useState('');
  const [showAll, setShowAll] = useState(false);
  const [target, setTarget] = useState(initialTarget ?? '');
  const [chosen, setChosen] = useState<string | null>(null);
  const [role, setRole] = useState<GoalWorkflowRole>('WORKER');
  const goalProjects = useMemo(() => new Set(projects.map((project) => project.projectId)), [projects]);
  const taken = new Set(attachments.filter((item) => (item.workPackageId ?? '') === target).map((item) => item.workflowId));

  const needle = query.trim().toLowerCase();
  const shown = (workflows ?? [])
    .filter((workflow) => showAll || goalProjects.has(workflow.projectId))
    .filter((workflow) => !needle || workflow.name.toLowerCase().includes(needle) || workflow.projectName?.toLowerCase().includes(needle))
    .sort((a, b) => a.name.localeCompare(b.name));
  const packagesByProject = projects.map((project) => ({
    project, items: workPackages.filter((item) => item.projectId === project.projectId),
  })).filter((group) => group.items.length > 0);

  const submit = () => {
    if (!chosen) return;
    attach.mutate({ workflowId: chosen, role, ...(target ? { workPackageId: target } : {}) }, { onSuccess: onClose });
  };

  return (
    <Modal open onClose={onClose} title={tw.dialogTitle} size="md" footer={(
      <>
        <Button variant="secondary" onClick={onClose}>{tw.cancel}</Button>
        <Button onClick={submit} loading={attach.isPending} disabled={!chosen || taken.has(chosen)}>{tw.attach}</Button>
      </>
    )}>
      <div className="space-y-4" data-testid="assign-workflow">
        <div className="space-y-1.5">
          <label htmlFor={targetId} className="block text-sm font-medium text-text-secondary">{tw.target}</label>
          <select id={targetId} value={target} onChange={(event) => setTarget(event.target.value)} className="input-base w-full cursor-pointer">
            <option value="">{tw.wholeGoal}</option>
            {packagesByProject.map(({ project, items }) => (
              <optgroup key={project.projectId} label={project.name}>
                {items.map((item) => <option key={item.id} value={item.id}>{item.title}</option>)}
              </optgroup>
            ))}
          </select>
        </div>

        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor={searchId} className="relative min-w-[12rem] flex-1">
              <span className="sr-only">{tw.search}</span>
              <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-text-tertiary" aria-hidden="true" />
              <input id={searchId} type="search" value={query} placeholder={tw.search} onChange={(event) => setQuery(event.target.value)}
                className="input-base w-full pl-8" />
            </label>
            <Toggle enabled={showAll} onChange={setShowAll} label={tw.showAll} ariaLabel={tw.showAll} />
          </div>
          {!showAll && <p className="text-xs text-text-tertiary">{tw.onlyGoalProjects}</p>}
          {isLoading ? (
            <div className="flex justify-center py-4"><Spinner /></div>
          ) : shown.length === 0 ? (
            <p className="rounded-md border border-dashed border-border px-3 py-4 text-center text-sm text-text-secondary">{tw.none}</p>
          ) : (
            <ul role="radiogroup" aria-label={tw.dialogTitle}
              className="max-h-64 divide-y divide-border overflow-y-auto rounded-md border border-border">
              {shown.map((workflow) => {
                const already = taken.has(workflow.id);
                const active = chosen === workflow.id;
                return (
                  <li key={workflow.id}>
                    <button type="button" role="radio" aria-checked={active} disabled={already} onClick={() => setChosen(workflow.id)}
                      className={`flex w-full items-center gap-3 px-3 py-2 text-left focus-ring disabled:cursor-not-allowed disabled:opacity-50 ${
                        active ? 'bg-accent-subtle' : 'hover:bg-surface-hover'}`}>
                      <span aria-hidden="true" className={`h-3.5 w-3.5 shrink-0 rounded-full border ${
                        active ? 'border-4 border-accent' : 'border-border-strong'}`} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm text-text">{workflow.name}</span>
                        <span className="block truncate text-xs text-text-tertiary">
                          {workflow.projectName}{already ? ` · ${tw.already}` : ''}
                        </span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        <fieldset className="space-y-1.5">
          <legend className="text-sm font-medium text-text-secondary">{tw.role}</legend>
          <div className="grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label={tw.role}>
            {(['WORKER', 'STATUS'] as const).map((option) => {
              const Icon = ROLE_ICON[option];
              const active = role === option;
              return (
                <button key={option} type="button" role="radio" aria-checked={active} onClick={() => setRole(option)}
                  className={`flex items-start gap-2 rounded-md border px-3 py-2 text-left focus-ring ${
                    active ? 'border-accent bg-accent-subtle' : 'border-border hover:border-border-strong'}`}>
                  <Icon className={`mt-0.5 h-4 w-4 shrink-0 ${active ? 'text-accent' : 'text-text-tertiary'}`} aria-hidden="true" />
                  <span>
                    <span className="block text-sm font-medium text-text">{tw.roles[option]}</span>
                    <span className="block text-xs text-text-tertiary">{tw.roleHints[option]}</span>
                  </span>
                </button>
              );
            })}
          </div>
        </fieldset>
      </div>
    </Modal>
  );
}
