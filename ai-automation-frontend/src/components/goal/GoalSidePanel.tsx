import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { ConfirmModal } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { useDeleteMilestone } from '../../hooks/useGoalMilestones';
import { useDeleteWorkPackage } from '../../hooks/useGoalWorkPackages';
import { MilestoneDiamond } from './MilestoneDiamond';
import { SidePanelFrame } from './SidePanelFrame';
import { WorkPackageMark } from './WorkPackageMark';
import { MilestoneEditor } from './MilestoneEditor';
import { WorkPackageEditor } from './WorkPackageEditor';
import { ReachedSwitch } from './ReachedSwitch';
import { DecisionCounts } from './DecisionCounts';
import { DecisionMark } from './DecisionMark';
import { DueChip } from './TodoPlanChips';
import { GoalWorkflowChip } from './GoalWorkflowChip';
import { attachmentsAt, useGoalWorkflowsScope } from './goalWorkflowsContext';
import { STATUS_DOT } from './planDisplay';
import { rangeText } from './dragLabel';
import { packageRange } from './timelineLayout';
import type { GoalSelection } from './goalSelection';
import type { GoalDetail, GoalMilestone, GoalTodoItem, GoalWorkPackage } from '../../types';

const SECTION_TITLE = 'text-xs font-semibold text-text-secondary';
const ACTION = 'inline-flex items-center gap-1 rounded-md border border-border px-2.5 py-1 text-xs text-text-secondary hover:border-border-strong hover:text-text focus-ring';

/**
 * Die Seitenleiste der Zielseite (c60bf914, Flo: „Beim Anklicken eines Milestones sollten die TODOs
 * und Workflows in der Ansicht rechts sein … Und man sollte auch besser zu den TODOs kommen“): zu
 * einem Meilenstein seine verknüpften Arbeitspakete je Projekt mit deren TODOs und Workflows, zu einem
 * Arbeitspaket seine TODOs, Workflows und Meilensteine. Jedes TODO öffnet seinen Dialog. Angedockt,
 * als Fenster oder (unter 1024 px) als Blatt – siehe `SidePanelFrame`. × und Escape schließen.
 */
export function GoalSidePanel({ detail, selection, today, onClose, onSelect, onOpenTodo }: {
  detail: GoalDetail;
  selection: GoalSelection;
  today: number;
  onClose: () => void;
  onSelect: (selection: GoalSelection, trigger: HTMLElement) => void;
  onOpenTodo: (todoId: string) => void;
}) {
  const t = useT();
  const tp = t.goals.panel;
  const headingId = useId();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const [editing, setEditing] = useState(false);
  const [removing, setRemoving] = useState(false);
  const removeMilestone = useDeleteMilestone(detail.goal.id);
  const removePackage = useDeleteWorkPackage(detail.goal.id);
  const milestone = selection.kind === 'milestone' ? detail.milestones.find((item) => item.id === selection.id) : undefined;
  const workPackage = selection.kind === 'workpackage' ? detail.workPackages.find((item) => item.id === selection.id) : undefined;
  const title = milestone?.title ?? workPackage?.title ?? '';

  // Beim Öffnen und bei jedem Wechsel des Eintrags steht der Fokus auf der Überschrift
  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true });
  }, [selection.kind, selection.id]);

  // Escape schließt – außer ein Dialog darüber (TODO, Bearbeiten, Löschen) hat es schon für sich genutzt
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      if (document.querySelector('[role="dialog"][aria-modal="true"]')) return;
      onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  if (!milestone && !workPackage) return null;

  return (
    <>
      <SidePanelFrame labelledBy={headingId} onClose={onClose} testId="goal-side-panel" kind={selection.kind}
        header={(
          <>
            <p className="flex items-center gap-1.5 text-xs text-text-tertiary">
              {milestone ? <MilestoneDiamond state={milestone.state} size="sm" /> : <WorkPackageMark state={workPackage!.state} />}
              {milestone ? tp.milestone : tp.workPackage}
            </p>
            <h2 id={headingId} ref={headingRef} tabIndex={-1} aria-label={tp.label.replace('{title}', title)}
              className="mt-0.5 break-words text-lg font-semibold text-text focus:outline-none">
              {title}
            </h2>
          </>
        )}
        footer={(
          <div className="mt-4 flex gap-2 border-t border-border pt-3">
            <button type="button" className={ACTION} onClick={() => setEditing(true)}>
              <Pencil className="h-3.5 w-3.5" aria-hidden="true" />{tp.edit}
            </button>
            <button type="button" className={`${ACTION} hover:text-danger`} onClick={() => setRemoving(true)}>
              <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />{tp.delete}
            </button>
          </div>
        )}>
        {milestone
          ? <MilestoneBody detail={detail} milestone={milestone} today={today} onSelect={onSelect} onOpenTodo={onOpenTodo} />
          : <PackageBody detail={detail} workPackage={workPackage!} today={today} onSelect={onSelect} onOpenTodo={onOpenTodo} />}
      </SidePanelFrame>

      {editing && milestone && (
        <MilestoneEditor goalId={detail.goal.id} milestone={milestone} workPackages={detail.workPackages} projects={detail.projects}
          onClose={() => setEditing(false)} />
      )}
      {editing && workPackage && (
        <WorkPackageEditor goalId={detail.goal.id} projects={detail.projects} workPackage={workPackage} onClose={() => setEditing(false)} />
      )}
      <ConfirmModal
        open={removing}
        onClose={() => setRemoving(false)}
        onConfirm={() => {
          const done = { onSuccess: onClose, onSettled: () => setRemoving(false) };
          if (milestone) removeMilestone.mutate(milestone.id, done);
          else removePackage.mutate(workPackage!.id, done);
        }}
        title={milestone ? t.goals.milestones.deleteTitle : t.goals.workPackages.deleteTitle}
        message={(milestone ? t.goals.milestones : t.goals.workPackages).deleteConfirm.replace('{title}', () => title)}
        confirmLabel={tp.delete}
        confirmIcon={<Trash2 className="h-4 w-4" />}
        variant="danger"
        isPending={removeMilestone.isPending || removePackage.isPending}
      />
    </>
  );
}

function Section({ title, children, testId }: { title: string; children: ReactNode; testId?: string }) {
  return (
    <section className="mt-4" data-testid={testId}>
      <h3 className={SECTION_TITLE}>{title}</h3>
      <div className="mt-1.5">{children}</div>
    </section>
  );
}

/** Fortschritt als Balken mit Zahl daneben. */
function Progress({ done, total }: { done: number; total: number }) {
  const t = useT();
  const percent = total > 0 ? Math.round((done / total) * 100) : 0;
  return (
    <span className="flex items-center gap-2 text-xs tabular-nums text-text-secondary">
      <span aria-hidden="true" className="block h-1.5 w-20 overflow-hidden rounded-full bg-border">
        <span className="block h-full rounded-full bg-success" style={{ width: `${percent}%` }} />
      </span>
      <span title={t.goals.progress.replace('{done}', String(done)).replace('{total}', String(total))}>{done}/{total}</span>
    </span>
  );
}

function MilestoneBody({ detail, milestone, today, onSelect, onOpenTodo }: {
  detail: GoalDetail;
  milestone: GoalMilestone;
  today: number;
  onSelect: (selection: GoalSelection, trigger: HTMLElement) => void;
  onOpenTodo: (todoId: string) => void;
}) {
  const t = useT();
  const tp = t.goals.panel;
  const tm = t.goals.milestones;
  const locale = useI18nStore((s) => s.locale);
  const linked = detail.workPackages.filter((item) => milestone.workPackageIds.includes(item.id));
  const groups = [
    ...detail.projects.map((project) => ({ key: project.projectId, name: project.name })),
    { key: '', name: tp.noProject },
  ].map((group) => ({
    ...group,
    items: linked.filter((item) => (group.key ? item.projectId === group.key
      : !item.projectId || !detail.projects.some((project) => project.projectId === item.projectId))),
  })).filter((group) => group.items.length > 0);

  return (
    <>
      <p className={`mt-1 flex flex-wrap gap-x-2 text-sm ${milestone.state === 'OVERDUE' ? 'text-danger' : 'text-text-secondary'}`}>
        <span className="tabular-nums">{formatDate(milestone.dueDate, locale)}</span>
        <span>· {tm.states[milestone.state]}</span>
        {milestone.reopened && <span>· {tm.reopened}</span>}
      </p>
      <ReachedSwitch goalId={detail.goal.id} milestone={milestone} className="mt-2" />
      <Section title={tp.linkedPackages} testId="panel-linked-packages">
        {groups.length === 0 ? <p className="text-sm text-text-tertiary">{tp.noLinkedPackages}</p> : (
          <div className="space-y-4">
            {groups.map((group) => (
              <div key={group.key || 'none'} role="group" aria-label={group.name}>
                <p className="text-[11px] font-semibold uppercase tracking-wide text-text-tertiary">{group.name}</p>
                <ul className="mt-1 space-y-3">
                  {group.items.map((item) => (
                    <li key={item.id} data-testid={`panel-package-${item.id}`}
                      className="rounded-lg border border-border bg-surface px-3 py-2">
                      <button type="button" onClick={(event) => onSelect({ kind: 'workpackage', id: item.id }, event.currentTarget)}
                        aria-label={tp.openPackage.replace('{title}', item.title)}
                        className="flex w-full items-center gap-2 rounded text-left focus-ring hover:text-accent">
                        <WorkPackageMark state={item.state} />
                        <span className="min-w-0 flex-1 truncate text-sm font-medium text-text">{item.title}</span>
                        <PackageRange item={item} />
                      </button>
                      <div className="mt-1 flex items-center gap-3">
                        <Progress done={item.done} total={item.total} />
                        <DecisionCounts openQuestions={item.openQuestions} decided={item.decided} className="text-xs" />
                      </div>
                      <PackageTodos todos={detail.todos} workPackageId={item.id} today={today} onOpenTodo={onOpenTodo} />
                      <PackageWorkflowChips workPackageId={item.id} />
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}
      </Section>
    </>
  );
}

function PackageBody({ detail, workPackage, today, onSelect, onOpenTodo }: {
  detail: GoalDetail;
  workPackage: GoalWorkPackage;
  today: number;
  onSelect: (selection: GoalSelection, trigger: HTMLElement) => void;
  onOpenTodo: (todoId: string) => void;
}) {
  const t = useT();
  const tp = t.goals.panel;
  const tw = t.goals.workPackages;
  const locale = useI18nStore((s) => s.locale);
  const workflows = useGoalWorkflowsScope();
  const project = detail.projects.find((item) => item.projectId === workPackage.projectId);
  const milestones = detail.milestones.filter((item) => item.workPackageIds.includes(workPackage.id));

  return (
    <>
      <p className={`mt-1 flex flex-wrap gap-x-2 text-sm ${workPackage.state === 'OVERDUE' ? 'text-danger' : 'text-text-secondary'}`}>
        <span>{project?.name ?? tp.noProject}</span>
        <span className="tabular-nums">· <PackageRange item={workPackage} /></span>
        {workPackage.state !== 'OPEN' && <span>· {tw.states[workPackage.state]}</span>}
        {workPackage.reopened && <span>· {tw.reopened}</span>}
      </p>
      <div className="mt-2 flex items-center gap-3">
        <Progress done={workPackage.done} total={workPackage.total} />
        <DecisionCounts openQuestions={workPackage.openQuestions} decided={workPackage.decided} className="text-xs" />
      </div>
      <Section title={tp.todos} testId="panel-todos">
        <PackageTodos todos={detail.todos} workPackageId={workPackage.id} today={today} onOpenTodo={onOpenTodo} empty />
      </Section>
      <Section title={t.goals.workflows.title} testId="panel-workflows">
        <PackageWorkflowChips workPackageId={workPackage.id} empty />
        {workflows?.supported && (
          <button type="button" onClick={() => workflows.openAssign(workPackage.id)}
            className="mt-1.5 inline-flex items-center gap-1 rounded text-xs text-text-secondary hover:text-accent focus-ring">
            <Plus className="h-3.5 w-3.5" aria-hidden="true" />{t.goals.workflows.assign}
          </button>
        )}
      </Section>
      {!detail.legacyBackend && (
        <Section title={tp.milestones} testId="panel-milestones">
          {milestones.length === 0 ? <p className="text-sm text-text-tertiary">{tp.noMilestones}</p> : (
            <ul className="space-y-1">
              {milestones.map((item) => (
                <li key={item.id}>
                  <button type="button" onClick={(event) => onSelect({ kind: 'milestone', id: item.id }, event.currentTarget)}
                    aria-label={tp.openMilestone.replace('{title}', item.title)}
                    className="flex w-full items-center gap-2 rounded px-1 py-0.5 text-left text-sm hover:bg-surface-hover focus-ring">
                    <MilestoneDiamond state={item.state} size="sm" />
                    <span className="min-w-0 flex-1 truncate text-text">{item.title}</span>
                    <span className="shrink-0 text-xs tabular-nums text-text-tertiary">{formatDate(item.dueDate, locale)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Section>
      )}
    </>
  );
}

function PackageRange({ item }: { item: GoalWorkPackage }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const range = packageRange(item);
  return <span className="shrink-0 text-xs tabular-nums text-text-tertiary">{range ? rangeText(range, locale) : t.goals.workPackages.noRange}</span>;
}

/** Die TODOs eines Pakets: ein Klick (oder Enter) öffnet den TODO-Dialog; ❓/✔ entscheidet daneben. */
function PackageTodos({ todos, workPackageId, today, onOpenTodo, empty = false }: {
  todos: GoalTodoItem[];
  workPackageId: string;
  today: number;
  onOpenTodo: (todoId: string) => void;
  empty?: boolean;
}) {
  const t = useT();
  const own = todos.filter((todo) => todo.workPackageId === workPackageId && todo.status !== 'ARCHIVED');
  if (own.length === 0) return empty ? <p className="text-sm text-text-tertiary">{t.goals.workPackages.noTodos}</p> : null;
  return (
    <ul className="mt-1.5 space-y-px">
      {own.map((todo) => {
        const done = todo.status === 'DONE';
        return (
          <li key={todo.id} className="flex items-center gap-2 rounded-md px-1 py-0.5 hover:bg-surface-hover/60" data-testid="panel-todo">
            <span aria-hidden="true" className={`h-2 w-2 shrink-0 rounded-full ${STATUS_DOT[todo.status]}`} />
            <button type="button" onClick={() => onOpenTodo(todo.id)} title={todo.title}
              aria-label={`${t.goals.outline.openTodo.replace('{title}', todo.title)} (${t.todos.statuses[todo.status]})`}
              className={`min-w-0 flex-1 truncate rounded text-left text-sm focus-ring hover:text-accent ${
                done ? 'text-text-tertiary line-through' : 'text-text'}`}>
              {todo.title}
            </button>
            <DueChip todo={todo} today={today} />
            <DecisionMark todo={todo} />
          </li>
        );
      })}
    </ul>
  );
}

/** Workflows eines Pakets als Chips mit ▶ – dieselbe Sperre gegen Doppelstarts wie überall. */
function PackageWorkflowChips({ workPackageId, empty = false }: { workPackageId: string; empty?: boolean }) {
  const t = useT();
  const scope = useGoalWorkflowsScope();
  const items = scope ? attachmentsAt(scope.attachments, workPackageId) : [];
  if (items.length === 0) return empty ? <p className="text-sm text-text-tertiary">{t.goals.workflows.packageEmpty}</p> : null;
  return (
    <div className="mt-1.5 flex flex-wrap gap-1">
      {items.map((attachment) => (
        <GoalWorkflowChip key={attachment.id} attachment={attachment} entry={scope!.results.get(attachment.workflowId)} compact />
      ))}
    </div>
  );
}
