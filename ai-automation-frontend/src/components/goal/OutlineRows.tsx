// Rows inside a project card of the goal outline (GoalOutline): work packages, the TODOs without a
// package and their TODO rows.
import { useId, useRef, useState } from 'react';
import { AlertTriangle, ArrowDown, ArrowUp, ListPlus, Pencil, Plus } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { ComplexityChip, EffortChip, RatedByMark } from './GoalBadges';
import { DueChip } from './TodoPlanChips';
import { DecisionMark } from './DecisionMark';
import { PackageWorkflows } from './GoalWorkflowsPanel';
import { useGoalSelection, useIsSelected } from './goalSelection';
import { WorkPackageMark } from './WorkPackageMark';
import { OutlineBar, OutlineCountColumns, OutlineToggle } from './OutlineGroup';
import { STATUS_DOT } from './planDisplay';
import { rangeText } from './dragLabel';
import { packageRange } from './timelineLayout';
import { countTodos, type OutlinePackage, type OutlineProject, type OutlineTodo } from './goalOutline';
import { PackageTodoAdder } from './PackageTodoAdder';
import type { GoalWorkPackage } from '../../types';

/** Small icon button of the outline rows, also used for the project headers in GoalOutline. */
export const ICON_BUTTON = 'rounded p-1 text-text-tertiary hover:bg-surface-hover hover:text-text focus-ring disabled:opacity-30 disabled:pointer-events-none';

interface BodyProps {
  goalId: string;
  group: OutlineProject;
  today: number;
  isOpen: (key: string, byDefault: boolean) => boolean;
  toggle: (key: string, byDefault: boolean, value?: boolean) => void;
  onOpenTodo: (todoId: string) => void;
  reordering: boolean;
  onMove: (index: number, delta: -1 | 1) => void;
  onEdit: (workPackage: GoalWorkPackage) => void;
  /** Missing for „Ohne Projekt“ and for projects that do not belong to the goal — no package can be created there. */
  onAdd?: () => void;
  /** Only for a project that comes in merely via TODOs: assign it to the goal. */
  onLink?: () => void;
  linking?: boolean;
}

/** Content of a project card: the work packages on the thread, „Ohne Arbeitspaket“, then „+ Arbeitspaket“. */
export function ProjectBody({ goalId, group, today, isOpen, toggle, onOpenTodo, reordering, onMove, onEdit, onAdd, onLink, linking }: BodyProps) {
  const t = useT();
  const to = t.goals.outline;
  const name = group.project?.name ?? to.noProject;
  const looseKey = `loose:${group.key}`;
  const empty = group.packages.length === 0 && group.loose.length === 0;
  return (
    <div className="space-y-2">
      {!group.project && <p className="text-xs text-text-tertiary">{to.noProjectHint}</p>}
      {onLink && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-warning/40 bg-warning-subtle px-3 py-2">
          <p className="min-w-0 flex-1 basis-56 text-xs text-text-secondary">{to.notLinkedHint}</p>
          <button type="button" onClick={onLink} disabled={linking}
            className="btn-base shrink-0 border border-border bg-surface-raised px-2.5 py-1 text-xs text-text hover:border-accent hover:text-accent focus-ring">
            {to.assignProject}
          </button>
        </div>
      )}
      {empty && group.project && <p className="text-sm text-text-secondary">{to.noWorkPackages}</p>}
      {(group.packages.length > 0 || group.loose.length > 0) && (
        // The thread: a vertical line on which the packages sit in order
        <ol className="relative space-y-0.5 before:absolute before:bottom-3 before:left-[7px] before:top-3 before:w-0.5 before:rounded-full before:bg-border">
          {group.packages.map((entry, index) => (
            <PackageRow key={entry.workPackage.id} goalId={goalId} projectName={group.project?.name ?? null} entry={entry} today={today}
              expanded={isOpen(`wp:${entry.workPackage.id}`, entry.workPackage.state !== 'REACHED')}
              onToggle={(value) => toggle(`wp:${entry.workPackage.id}`, entry.workPackage.state !== 'REACHED', value)}
              onOpenTodo={onOpenTodo} onEdit={() => onEdit(entry.workPackage)}
              onUp={index > 0 && !reordering ? () => onMove(index, -1) : undefined}
              onDown={index < group.packages.length - 1 && !reordering ? () => onMove(index, 1) : undefined} />
          ))}
          {group.loose.length > 0 && (
            <LooseRow nodeKey={looseKey} todos={group.loose} today={today} expanded={isOpen(looseKey, true)}
              onToggle={() => toggle(looseKey, true)} onOpenTodo={onOpenTodo} />
          )}
        </ol>
      )}
      {onAdd && (
        <button type="button" onClick={onAdd} aria-label={to.addWorkPackageIn.replace('{project}', name)}
          className="ml-5 inline-flex items-center gap-1 rounded-md border border-dashed border-border-strong px-2.5 py-1 text-xs font-medium text-text-secondary hover:border-accent hover:text-accent focus-ring">
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          {to.addWorkPackage}
        </button>
      )}
    </div>
  );
}

function PackageRow({ goalId, projectName, entry, today, expanded, onToggle, onOpenTodo, onEdit, onUp, onDown }: {
  goalId: string;
  projectName: string | null;
  entry: OutlinePackage;
  today: number;
  expanded: boolean;
  /** Without a value toggle, with a value open or close. */
  onToggle: (value?: boolean) => void;
  onOpenTodo: (todoId: string) => void;
  onEdit: () => void;
  onUp?: () => void;
  onDown?: () => void;
}) {
  const t = useT();
  const tw = t.goals.workPackages;
  const locale = useI18nStore((s) => s.locale);
  const bodyId = useId();
  const { workPackage, todos, counts } = entry;
  const done = workPackage.state === 'REACHED';
  const selection = useGoalSelection();
  const selected = useIsSelected('workpackage', workPackage.id);
  const range = packageRange(workPackage);
  // „+ TODO“ on the package (85889063): field below the TODOs, the package expands for it
  const [adding, setAdding] = useState(false);
  const addRef = useRef<HTMLButtonElement>(null);
  const addLabel = t.goals.add.todoIn.replace('{title}', () => workPackage.title);
  const closeAdder = () => {
    setAdding(false);
    addRef.current?.focus();
  };
  return (
    <li data-testid={`outline-package-${workPackage.id}`}>
      <div className="flex items-center gap-2 rounded-md hover:bg-surface-hover/60">
        <span className="relative z-10 flex w-4 shrink-0 justify-center bg-surface-raised py-0.5"><WorkPackageMark state={workPackage.state} /></span>
        <OutlineToggle nodeKey={`wp:${workPackage.id}`} expanded={expanded} controls={bodyId} onToggle={() => onToggle()} className="shrink-0"
          label={t.goals.outline.toggle.replace('{title}', workPackage.title)} />
        {/* The title opens the side panel with the package's TODOs and workflows (c60bf914) */}
        <button type="button" onClick={(event) => selection?.select({ kind: 'workpackage', id: workPackage.id }, event.currentTarget)}
          aria-label={t.goals.panel.openPackage.replace('{title}', workPackage.title)} aria-current={selected || undefined}
          className={`min-w-0 flex-1 rounded-md px-1 py-0.5 text-left focus-ring hover:text-accent ${selected ? 'bg-accent-subtle' : ''}`}>
          <span title={workPackage.title}
            className={`line-clamp-2 break-words text-sm font-medium ${done ? 'text-text-secondary' : 'text-text'}`}>
            {workPackage.title}
          </span>
          <span className={`flex flex-wrap gap-x-2 text-[11px] ${workPackage.state === 'OVERDUE' ? 'text-danger' : 'text-text-tertiary'}`}>
            <span className="tabular-nums">{range ? rangeText(range, locale) : tw.noRange}</span>
            {workPackage.state !== 'OPEN' && <span>{tw.states[workPackage.state]}</span>}
            {workPackage.reopened && <span>{tw.reopened}</span>}
          </span>
        </button>
        <OutlineBar counts={counts} className="hidden h-1 w-14 shrink-0 sm:block" />
        <OutlineCountColumns counts={counts} />
        <div className="flex shrink-0 items-center">
          <button type="button" className={ICON_BUTTON} disabled={!onUp} onClick={onUp}
            aria-label={`${tw.moveUp}: ${workPackage.title}`} title={tw.moveUp}>
            <ArrowUp className="h-3.5 w-3.5" aria-hidden="true" />
          </button>
          <button type="button" className={ICON_BUTTON} disabled={!onDown} onClick={onDown}
            aria-label={`${tw.moveDown}: ${workPackage.title}`} title={tw.moveDown}>
            <ArrowDown className="h-3.5 w-3.5" aria-hidden="true" />
          </button>
          <button ref={addRef} type="button" className={ICON_BUTTON} aria-label={addLabel} title={addLabel} aria-expanded={adding}
            onClick={() => {
              setAdding(true);
              onToggle(true);
            }}>
            <ListPlus className="h-3.5 w-3.5" aria-hidden="true" />
          </button>
          <button type="button" className={ICON_BUTTON} onClick={onEdit}
            aria-label={t.goals.outline.edit.replace('{title}', workPackage.title)} title={tw.edit}>
            <Pencil className="h-3.5 w-3.5" aria-hidden="true" />
          </button>
        </div>
      </div>
      {/* Workflows on the package (52bc1e8c); assigning happens in the header (assign workflow, "what for") or in the package card on the timeline */}
      <PackageWorkflows workPackageId={workPackage.id} className="pb-1 pl-6" />
      <div id={bodyId} hidden={!expanded} className="pb-1 pl-6">
        {todos.length === 0 && !adding
          ? <p className="px-1.5 py-1 text-xs text-text-tertiary">{t.goals.outline.noTodos}</p>
          : <TodoRows todos={todos} today={today} onOpenTodo={onOpenTodo} />}
        {adding && expanded && (
          <PackageTodoAdder goalId={goalId} workPackage={workPackage} projectName={projectName} onClose={closeAdder} />
        )}
      </div>
    </li>
  );
}

/** „Ohne Arbeitspaket“: the project's TODOs that are not yet in any package. */
function LooseRow({ nodeKey, todos, today, expanded, onToggle, onOpenTodo }: {
  nodeKey: string;
  todos: OutlineTodo[];
  today: number;
  expanded: boolean;
  onToggle: () => void;
  onOpenTodo: (todoId: string) => void;
}) {
  const to = useT().goals.outline;
  const bodyId = useId();
  const counts = countTodos(todos.map((entry) => entry.todo));
  return (
    <li data-testid={`outline-${nodeKey.replace(':', '-')}`}>
      <div className="flex items-center gap-2 rounded-md hover:bg-surface-hover/60">
        <span className="relative z-10 flex w-4 shrink-0 justify-center">
          <span aria-hidden="true" className="h-2 w-2 rounded-full border-2 border-border-strong bg-surface-raised" />
        </span>
        <OutlineToggle nodeKey={nodeKey} expanded={expanded} controls={bodyId} onToggle={onToggle}>
          <span className="truncate text-sm italic text-text-secondary">{to.noWorkPackage}</span>
        </OutlineToggle>
        <OutlineBar counts={counts} className="hidden h-1 w-14 shrink-0 sm:block" />
        <OutlineCountColumns counts={counts} />
        {/* Room for the four buttons of the package rows so the counters line up */}
        <span aria-hidden="true" className="w-[88px] shrink-0" />
      </div>
      <div id={bodyId} hidden={!expanded} className="pb-1 pl-6">
        <TodoRows todos={todos} today={today} onOpenTodo={onOpenTodo} />
      </div>
    </li>
  );
}

/** TODO rows: status dot, title (opens the dialog), due date, estimate, ❓/✔ (decide, 68a5e24e) and ⚠ for legacy cases. */
function TodoRows({ todos, today, onOpenTodo }: { todos: OutlineTodo[]; today: number; onOpenTodo: (todoId: string) => void }) {
  const t = useT();
  const to = t.goals.outline;
  return (
    <ul className="space-y-px">
      {todos.map(({ todo, mismatch }) => {
        const done = todo.status === 'DONE';
        return (
          <li key={todo.id} data-testid="outline-todo" data-todo-id={todo.id} className="flex items-start gap-2 rounded-md px-1.5 py-1 hover:bg-surface-hover/60">
            <span aria-hidden="true" className={`mt-[7px] h-2 w-2 shrink-0 rounded-full ${STATUS_DOT[todo.status]}`} />
            <div className="flex min-w-0 flex-1 flex-col gap-x-2 gap-y-1 sm:flex-row sm:items-center">
              <button type="button" onClick={() => onOpenTodo(todo.id)} title={todo.title}
                aria-label={`${to.openTodo.replace('{title}', todo.title)} (${t.todos.statuses[todo.status]})`}
                className={`min-w-0 flex-1 break-words rounded text-left text-sm focus-ring hover:text-accent sm:truncate ${
                  done ? 'text-text-tertiary line-through' : 'text-text'}`}>
                {todo.title}
              </button>
              <span className="flex shrink-0 flex-wrap items-center gap-1">
                {mismatch && (
                  <AlertTriangle className="h-3.5 w-3.5 text-warning" role="img" aria-label={to.mismatch}>
                    <title>{to.mismatch}</title>
                  </AlertTriangle>
                )}
                <DueChip todo={todo} today={today} />
                <ComplexityChip value={todo.complexity} />
                <EffortChip value={todo.suggestedEffort} />
                <RatedByMark ratedBy={todo.ratedBy} />
                <DecisionMark todo={todo} />
              </span>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
