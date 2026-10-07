import { useCallback, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { ArrowDown, ArrowUp, Trash2 } from 'lucide-react';
import { Card, ConfirmModal } from '../ui';
import { Hint } from '../hints/Hint';
import { useT } from '../../i18n';
import { useDeleteWorkPackage, useReorderWorkPackages } from '../../hooks/useGoalWorkPackages';
import { useDeleteMilestone } from '../../hooks/useGoalMilestones';
import { useAddGoalProject, useReorderGoalProjects } from '../../hooks/useGoalProjects';
import { OutlineGroup } from './OutlineGroup';
import { ICON_BUTTON, ProjectBody } from './OutlineRows';
import { OutlineMilestones } from './OutlineMilestones';
import { WorkPackageEditor } from './WorkPackageEditor';
import { MilestoneEditor } from './MilestoneEditor';
import { AddProjectPicker } from './GoalProjectsBar';
import { NO_PROJECT, buildOutline, moved, type OutlineProject } from './goalOutline';
import { useRevealInView } from './goalReveal';
import type { GoalDetail, GoalMilestone, GoalWorkPackage } from '../../types';

const COLLAPSE_KEY = 'mysuricates-goal-outline';

/** Expanded and collapsed state per goal; only deviations from the default are kept in storage. */
function readExpanded(goalId: string): Record<string, boolean> {
  try {
    const all = JSON.parse(localStorage.getItem(COLLAPSE_KEY) ?? '{}') as Record<string, Record<string, boolean>>;
    return all[goalId] ?? {};
  } catch {
    return {};
  }
}

function storeExpanded(goalId: string, state: Record<string, boolean>) {
  try {
    const all = JSON.parse(localStorage.getItem(COLLAPSE_KEY) ?? '{}') as Record<string, Record<string, boolean>>;
    localStorage.setItem(COLLAPSE_KEY, JSON.stringify({ ...all, [goalId]: state }));
  } catch {
    // private window or blocked storage: the choice then holds until reload
  }
}

/** Which dialog is open: a work package (new or existing) or a milestone. */
type EditorState =
  | { kind: 'package'; workPackage?: GoalWorkPackage; projectId?: string | null }
  | { kind: 'milestone'; milestone?: GoalMilestone }
  | null;

/** What gets deleted after the confirmation. */
type Removing = { kind: 'package'; item: GoalWorkPackage } | { kind: 'milestone'; item: GoalMilestone } | null;

/**
 * Outline of a goal (c60bf914), the default view of the goal page: milestones on top, then
 * one card per project with bar and counters, inside it the work packages on a thread in their
 * order — each with its period —, below it „Ohne Arbeitspaket“; at the end „Ohne Projekt“.
 * Expanded, the TODOs show with status, due date and estimate. Completed packages are
 * collapsed. Keyboard: ↑ ↓ change the row, → expands, ← collapses; Enter and Space toggle.
 */
export function GoalOutline({ detail, today, onOpenTodo }: {
  detail: GoalDetail;
  /** Day (`timelineLayout.dayOf`) against which „overdue“ is judged. */
  today: number;
  onOpenTodo: (todoId: string) => void;
}) {
  const t = useT();
  const to = t.goals.outline;
  const goalId = detail.goal.id;
  const helpId = useId();
  const outline = useMemo(() => buildOutline(detail), [detail]);
  const [expanded, setExpanded] = useState(() => readExpanded(goalId));
  const [editor, setEditor] = useState<EditorState>(null);
  const [removing, setRemoving] = useState<Removing>(null);
  const reorderPackages = useReorderWorkPackages(goalId);
  const reorderProjects = useReorderGoalProjects(goalId);
  const addProject = useAddGoalProject(goalId);
  const removePackage = useDeleteWorkPackage(goalId);
  const removeMilestone = useDeleteMilestone(goalId);

  const isOpen = useCallback((key: string, byDefault: boolean) => expanded[key] ?? byDefault, [expanded]);
  const toggle = useCallback((key: string, byDefault: boolean, value?: boolean) => {
    setExpanded((current) => {
      const next = { ...current, [key]: value ?? !(current[key] ?? byDefault) };
      storeExpanded(goalId, next);
      return next;
    });
  }, [goalId]);

  // „Zeigen“ after adding (85889063): expand project and package or „Ohne Arbeitspaket“
  const rootRef = useRef<HTMLElement>(null);
  const todoIds = useMemo(() => new Set(detail.todos.map((todo) => todo.id)), [detail.todos]);
  useRevealInView(goalId, rootRef, (id) => todoIds.has(id), (todoId) => {
    const todo = detail.todos.find((item) => item.id === todoId);
    const workPackage = todo?.workPackageId ? detail.workPackages.find((item) => item.id === todo.workPackageId) : undefined;
    const projectKey = workPackage ? workPackage.projectId ?? NO_PROJECT : todo?.projectId ?? NO_PROJECT;
    toggle(`project:${projectKey}`, true, true);
    if (workPackage) toggle(`wp:${workPackage.id}`, workPackage.state !== 'REACHED', true);
    else toggle(`loose:${projectKey}`, true, true);
  });

  /** ↑ ↓ Home End move from button to button, → ← expand and collapse. */
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    if (target.dataset.outlineToggle === undefined) return;
    const toggles = [...event.currentTarget.querySelectorAll<HTMLElement>('[data-outline-toggle]')]
      .filter((element) => !element.closest('[hidden]'));
    const index = toggles.indexOf(target);
    const focus = (next: number) => toggles[Math.max(0, Math.min(toggles.length - 1, next))]?.focus();
    const open = target.getAttribute('aria-expanded') === 'true';
    switch (event.key) {
      case 'ArrowDown': focus(index + 1); break;
      case 'ArrowUp': focus(index - 1); break;
      case 'Home': focus(0); break;
      case 'End': focus(toggles.length - 1); break;
      case 'ArrowRight': if (!open) target.click(); break;
      case 'ArrowLeft': if (open) target.click(); break;
      default: return;
    }
    event.preventDefault();
  };

  const moveProject = (index: number, delta: -1 | 1) => {
    const ids = moved(outline.projects.filter((group) => group.linked).map((group) => group.key), index, delta);
    if (ids) reorderProjects.mutate(ids);
  };
  const movePackage = (group: OutlineProject, index: number, delta: -1 | 1) => {
    const ids = moved(group.packages.map((entry) => entry.workPackage.id), index, delta);
    if (ids) reorderPackages.mutate(ids);
  };

  const linkedCount = outline.projects.filter((group) => group.linked).length;
  const renderProject = (group: OutlineProject, index: number) => {
    const name = group.project?.name ?? to.noProject;
    const key = `project:${group.key}`;
    return (
      <OutlineGroup key={group.key} nodeKey={key} bodyId={`${helpId}-${group.key}`} title={name} counts={group.counts}
        testId={`outline-project-${group.key}`}
        expanded={isOpen(key, true)} onToggle={() => toggle(key, true)}
        badge={(
          <>
            {group.project?.archived && <span className="shrink-0 text-xs text-text-tertiary">({t.goals.projects.archived})</span>}
            {group.project && !group.linked && (
              <span className="shrink-0 rounded-full border border-warning/40 bg-warning-subtle px-1.5 text-[11px] font-medium text-warning">
                {to.notLinked}
              </span>
            )}
          </>
        )}
        actions={group.project && group.linked && linkedCount > 1 && (
          <>
            <button type="button" className={ICON_BUTTON} disabled={index === 0 || reorderProjects.isPending}
              aria-label={to.moveProjectUp.replace('{name}', name)} title={to.moveProjectUp.replace('{name}', name)}
              onClick={() => moveProject(index, -1)}>
              <ArrowUp className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
            <button type="button" className={ICON_BUTTON} disabled={index === linkedCount - 1 || reorderProjects.isPending}
              aria-label={to.moveProjectDown.replace('{name}', name)} title={to.moveProjectDown.replace('{name}', name)}
              onClick={() => moveProject(index, 1)}>
              <ArrowDown className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
          </>
        )}>
        <ProjectBody goalId={goalId} group={group} today={today} isOpen={isOpen} toggle={toggle} onOpenTodo={onOpenTodo}
          reordering={reorderPackages.isPending}
          onMove={(packageIndex, delta) => movePackage(group, packageIndex, delta)}
          onEdit={(workPackage) => setEditor({ kind: 'package', workPackage })}
          onAdd={group.project && group.linked ? () => setEditor({ kind: 'package', projectId: group.key }) : undefined}
          onLink={group.project && !group.linked ? () => addProject.mutate(group.key) : undefined}
          linking={addProject.isPending} />
      </OutlineGroup>
    );
  };

  return (
    <section ref={rootRef} aria-label={to.label} data-testid="goal-outline" className="space-y-3">
      <Hint id="goal-outline" />
      <p id={helpId} className="sr-only">{to.keyboardHelp}</p>
      <OutlineMilestones detail={detail} onAdd={() => setEditor({ kind: 'milestone' })}
        onEdit={(milestone) => setEditor({ kind: 'milestone', milestone })} />
      {detail.projects.length === 0 && (
        <Card className="flex flex-wrap items-center gap-3" data-testid="outline-no-projects">
          <p className="min-w-0 flex-1 basis-60 text-sm text-text-secondary">{to.emptyProjects}</p>
          <AddProjectPicker goalId={goalId} linked={detail.projects} className="text-sm" />
        </Card>
      )}
      <div className="space-y-3" onKeyDown={onKeyDown} aria-describedby={helpId}>
        {outline.projects.map(renderProject)}
        {outline.noProject && renderProject(outline.noProject, -1)}
      </div>

      {editor?.kind === 'package' && (
        <WorkPackageEditor goalId={goalId} projects={detail.projects} workPackage={editor.workPackage} projectId={editor.projectId}
          onClose={() => setEditor(null)}
          onDelete={(item) => {
            setEditor(null);
            setRemoving({ kind: 'package', item });
          }} />
      )}
      {editor?.kind === 'milestone' && (
        <MilestoneEditor goalId={goalId} milestone={editor.milestone} workPackages={detail.workPackages} projects={detail.projects}
          onClose={() => setEditor(null)}
          onDelete={(item) => {
            setEditor(null);
            setRemoving({ kind: 'milestone', item });
          }} />
      )}
      <RemoveDialog removing={removing} onClose={() => setRemoving(null)}
        pending={removePackage.isPending || removeMilestone.isPending}
        onConfirm={() => {
          if (!removing) return;
          const done = { onSettled: () => setRemoving(null) };
          if (removing.kind === 'package') removePackage.mutate(removing.item.id, done);
          else removeMilestone.mutate(removing.item.id, done);
        }} />
    </section>
  );
}

/** Confirmation before deleting a package or milestone. */
function RemoveDialog({ removing, pending, onClose, onConfirm }: {
  removing: Removing;
  pending: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const t = useT();
  const words = removing?.kind === 'milestone' ? t.goals.milestones : t.goals.workPackages;
  return (
    <ConfirmModal
      open={removing !== null}
      onClose={onClose}
      onConfirm={onConfirm}
      title={words.deleteTitle}
      message={removing ? words.deleteConfirm.replace('{title}', () => removing.item.title) : null}
      confirmLabel={words.delete}
      confirmIcon={<Trash2 className="h-4 w-4" />}
      variant="danger"
      isPending={pending}
    />
  );
}
