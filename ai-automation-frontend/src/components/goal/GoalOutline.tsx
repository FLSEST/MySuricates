import { useCallback, useId, useMemo, useState, type KeyboardEvent } from 'react';
import { AlertTriangle, ArrowDown, ArrowUp, Pencil, Plus, Trash2 } from 'lucide-react';
import { Card, ConfirmModal } from '../ui';
import { Hint } from '../hints/Hint';
import { useI18nStore, useT } from '../../i18n';
import { useDeleteWorkPackage, useReorderWorkPackages } from '../../hooks/useGoalWorkPackages';
import { useDeleteMilestone } from '../../hooks/useGoalMilestones';
import { useAddGoalProject, useReorderGoalProjects } from '../../hooks/useGoalProjects';
import { ComplexityChip, EffortChip, RatedByMark } from './GoalBadges';
import { DueChip } from './TodoPlanChips';
import { DecisionMark } from './DecisionMark';
import { PackageWorkflows } from './GoalWorkflowsPanel';
import { WorkPackageMark } from './WorkPackageMark';
import { OutlineBar, OutlineCountColumns, OutlineGroup, OutlineToggle } from './OutlineGroup';
import { OutlineMilestones } from './OutlineMilestones';
import { WorkPackageEditor } from './WorkPackageEditor';
import { MilestoneEditor } from './MilestoneEditor';
import { AddProjectPicker } from './GoalProjectsBar';
import { STATUS_DOT } from './planDisplay';
import { rangeText } from './dragLabel';
import { packageRange } from './timelineLayout';
import { buildOutline, countTodos, moved, type OutlinePackage, type OutlineProject, type OutlineTodo } from './goalOutline';
import type { GoalDetail, GoalMilestone, GoalWorkPackage } from '../../types';

const COLLAPSE_KEY = 'mysuricates-goal-outline';

/** Auf- und Zugeklapptes je Ziel; nur Abweichungen vom Standard stehen im Speicher. */
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
    // privates Fenster oder gesperrter Speicher: die Wahl gilt dann bis zum Neuladen
  }
}

const ICON_BUTTON = 'rounded p-1 text-text-tertiary hover:bg-surface-hover hover:text-text focus-ring disabled:opacity-30 disabled:pointer-events-none';

/** Welcher Dialog offen ist: ein Arbeitspaket (neu oder bestehend) oder ein Meilenstein. */
type EditorState =
  | { kind: 'package'; workPackage?: GoalWorkPackage; projectId?: string | null }
  | { kind: 'milestone'; milestone?: GoalMilestone }
  | null;

/** Was nach der Rückfrage gelöscht wird. */
type Removing = { kind: 'package'; item: GoalWorkPackage } | { kind: 'milestone'; item: GoalMilestone } | null;

/**
 * Gliederung eines Ziels (c60bf914), die Standardansicht der Zielseite: oben die Meilensteine, dann
 * je Projekt eine Karte mit Balken und Zählern, darin die Arbeitspakete an einem Faden in ihrer
 * Reihenfolge – jedes mit Zeitraum –, darunter „Ohne Arbeitspaket“; am Ende „Ohne Projekt“.
 * Aufgeklappt stehen die TODOs mit Status, Fälligkeit und Einschätzung. Abgeschlossene Pakete sind
 * zugeklappt. Tastatur: ↑ ↓ wechseln die Zeile, → klappt auf, ← zu; Enter und Leertaste schalten um.
 */
export function GoalOutline({ detail, today, onOpenTodo }: {
  detail: GoalDetail;
  /** Tag (`timelineLayout.dayOf`), gegen den „überfällig“ gilt. */
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

  /** ↑ ↓ Home End wandern von Knopf zu Knopf, → ← klappen auf und zu. */
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
        <ProjectBody group={group} today={today} isOpen={isOpen} toggle={toggle} onOpenTodo={onOpenTodo}
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
    <section aria-label={to.label} data-testid="goal-outline" className="space-y-3">
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

interface BodyProps {
  group: OutlineProject;
  today: number;
  isOpen: (key: string, byDefault: boolean) => boolean;
  toggle: (key: string, byDefault: boolean) => void;
  onOpenTodo: (todoId: string) => void;
  reordering: boolean;
  onMove: (index: number, delta: -1 | 1) => void;
  onEdit: (workPackage: GoalWorkPackage) => void;
  /** Fehlt bei „Ohne Projekt“ und bei Projekten, die nicht zum Ziel gehören – dort lässt sich kein Paket anlegen. */
  onAdd?: () => void;
  /** Nur bei einem Projekt, das bloß über TODOs hereinkommt: es dem Ziel zuordnen. */
  onLink?: () => void;
  linking?: boolean;
}

/** Inhalt einer Projektkarte: die Arbeitspakete am Faden, „Ohne Arbeitspaket“, dann „+ Arbeitspaket“. */
function ProjectBody({ group, today, isOpen, toggle, onOpenTodo, reordering, onMove, onEdit, onAdd, onLink, linking }: BodyProps) {
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
        // Der Faden: eine senkrechte Linie, auf der die Pakete der Reihe nach sitzen
        <ol className="relative space-y-0.5 before:absolute before:bottom-3 before:left-[7px] before:top-3 before:w-0.5 before:rounded-full before:bg-border">
          {group.packages.map((entry, index) => (
            <PackageRow key={entry.workPackage.id} entry={entry} today={today}
              expanded={isOpen(`wp:${entry.workPackage.id}`, entry.workPackage.state !== 'REACHED')}
              onToggle={() => toggle(`wp:${entry.workPackage.id}`, entry.workPackage.state !== 'REACHED')}
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

function PackageRow({ entry, today, expanded, onToggle, onOpenTodo, onEdit, onUp, onDown }: {
  entry: OutlinePackage;
  today: number;
  expanded: boolean;
  onToggle: () => void;
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
  const range = packageRange(workPackage);
  return (
    <li data-testid={`outline-package-${workPackage.id}`}>
      <div className="flex items-center gap-2 rounded-md hover:bg-surface-hover/60">
        <span className="relative z-10 flex w-4 shrink-0 justify-center bg-surface-raised py-0.5"><WorkPackageMark state={workPackage.state} /></span>
        <OutlineToggle nodeKey={`wp:${workPackage.id}`} expanded={expanded} controls={bodyId} onToggle={onToggle}>
          <span className="min-w-0">
            <span title={workPackage.title}
              className={`line-clamp-2 break-words text-sm font-medium ${done ? 'text-text-secondary' : 'text-text'}`}>
              {workPackage.title}
            </span>
            <span className={`flex flex-wrap gap-x-2 text-[11px] ${workPackage.state === 'OVERDUE' ? 'text-danger' : 'text-text-tertiary'}`}>
              <span className="tabular-nums">{range ? rangeText(range, locale) : tw.noRange}</span>
              {workPackage.state !== 'OPEN' && <span>{tw.states[workPackage.state]}</span>}
              {workPackage.reopened && <span>{tw.reopened}</span>}
            </span>
          </span>
        </OutlineToggle>
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
          <button type="button" className={ICON_BUTTON} onClick={onEdit}
            aria-label={t.goals.outline.edit.replace('{title}', workPackage.title)} title={tw.edit}>
            <Pencil className="h-3.5 w-3.5" aria-hidden="true" />
          </button>
        </div>
      </div>
      {/* Workflows am Paket (52bc1e8c); zugeordnet wird im Kopf („Workflow zuordnen“, Wofür) oder in der Paket-Karte am Zeitstrahl */}
      <PackageWorkflows workPackageId={workPackage.id} className="pb-1 pl-6" />
      <div id={bodyId} hidden={!expanded} className="pb-1 pl-6">
        {todos.length === 0
          ? <p className="px-1.5 py-1 text-xs text-text-tertiary">{t.goals.outline.noTodos}</p>
          : <TodoRows todos={todos} today={today} onOpenTodo={onOpenTodo} />}
      </div>
    </li>
  );
}

/** „Ohne Arbeitspaket“: die TODOs des Projekts, die noch in keinem Paket stehen. */
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
        {/* Platz der drei Knöpfe der Paket-Zeilen, damit die Zähler fluchten */}
        <span aria-hidden="true" className="w-[66px] shrink-0" />
      </div>
      <div id={bodyId} hidden={!expanded} className="pb-1 pl-6">
        <TodoRows todos={todos} today={today} onOpenTodo={onOpenTodo} />
      </div>
    </li>
  );
}

/** TODO-Zeilen: Statuspunkt, Titel (öffnet den Dialog), Fälligkeit, Einschätzung, ❓/✔ (entscheiden, 68a5e24e) und ⚠ bei Altfällen. */
function TodoRows({ todos, today, onOpenTodo }: { todos: OutlineTodo[]; today: number; onOpenTodo: (todoId: string) => void }) {
  const t = useT();
  const to = t.goals.outline;
  return (
    <ul className="space-y-px">
      {todos.map(({ todo, mismatch }) => {
        const done = todo.status === 'DONE';
        return (
          <li key={todo.id} data-testid="outline-todo" className="flex items-start gap-2 rounded-md px-1.5 py-1 hover:bg-surface-hover/60">
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

/** Rückfrage vor dem Löschen eines Pakets oder Meilensteins. */
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
