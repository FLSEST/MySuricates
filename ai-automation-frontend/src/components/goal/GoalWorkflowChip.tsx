import { useRef, useState, type RefObject } from 'react';
import { Link } from 'react-router-dom';
import { ExternalLink, Gauge, Hammer, Play } from 'lucide-react';
import { Button } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { useDetachGoalWorkflow, useGoalWorkflowStart, useUpdateGoalWorkflowRole } from '../../hooks/useGoalWorkflows';
import { WorkflowResultContent } from '../dashboard/WorkflowResultContent';
import type { WorkflowWidgetEntry } from '../dashboard/workflowWidgetContract';
import { TimelinePopover } from './TimelinePopover';
import { TONE_DOT, ago, lastRunAt, lightOf, runPath, shownTone } from './goalWorkflowStatus';
import type { GoalWorkflow, GoalWorkflowRole } from '../../types';

const ROLE_ICON = { WORKER: Hammer, STATUS: Gauge } as const;
const ROLES: GoalWorkflowRole[] = ['WORKER', 'STATUS'];

/** The name shown on the chip: for the guard briefly „Wächter“, otherwise the workflow's name. */
function useShownName(attachment: GoalWorkflow) {
  const tw = useT().goals.workflows;
  return attachment.guard ? tw.guard : attachment.workflowName;
}

/** What the chip says about the state: for „liefert den Stand“ the traffic light (and its title), otherwise the run. */
function useStatusText(attachment: GoalWorkflow, entry: WorkflowWidgetEntry | undefined) {
  const t = useT();
  const tone = shownTone(entry, attachment.role === 'STATUS');
  const light = lightOf(entry);
  if (!entry?.run) return { tone, text: t.goals.workflows.noRun };
  const showsLight = attachment.role === 'STATUS' && light && tone !== 'info' && tone !== 'danger';
  return { tone, text: showsLight ? t.goals.guard.lights[light] : t.runs.statuses[entry.run.status] };
}

/**
 * A workflow on the goal or work package (52bc1e8c): role (🔨 works on it, ⏲ delivers the status),
 * dot of the last run — for „liefert den Stand“ the traffic light —, name, when, and ▶ to start. A
 * click opens the card with result, role and „Lösen“. `compact` is used on work packages.
 */
export function GoalWorkflowChip({ attachment, entry, compact = false }: {
  attachment: GoalWorkflow;
  entry: WorkflowWidgetEntry | undefined;
  compact?: boolean;
}) {
  const t = useT();
  const tw = t.goals.workflows;
  const locale = useI18nStore((s) => s.locale);
  const [open, setOpen] = useState(false);
  const [now] = useState(() => new Date());
  const triggerRef = useRef<HTMLButtonElement>(null);
  const name = useShownName(attachment);
  const start = useGoalWorkflowStart(attachment.goalId, attachment.workflowId, attachment.workPackageId, name, entry);
  const status = useStatusText(attachment, entry);
  const at = lastRunAt(entry);
  const RoleIcon = ROLE_ICON[attachment.role];
  const summary = attachment.role === 'STATUS' && !compact ? entry?.result?.title ?? null : null;
  const label = [name, tw.roles[attachment.role], status.text, at ? ago(at, now, locale) : ''].filter(Boolean).join(', ');

  return (
    <span className={`inline-flex max-w-full items-stretch rounded-md border border-border bg-surface-raised shadow-sm ${
      compact ? 'h-6 text-[11px]' : 'h-7 text-xs'}`} data-testid={`goal-workflow-${attachment.id}`} data-role={attachment.role}
      data-tone={status.tone}>
      <button ref={triggerRef} type="button" aria-haspopup="dialog" aria-expanded={open} aria-label={label}
        title={[attachment.workflowName, tw.roles[attachment.role], status.text].join(' · ')}
        onClick={() => setOpen((was) => !was)}
        className="flex min-w-0 items-center gap-1.5 rounded-l-md px-2 hover:bg-surface-hover focus-ring">
        <RoleIcon className={`${compact ? 'h-3 w-3' : 'h-3.5 w-3.5'} shrink-0 text-text-tertiary`} aria-hidden="true" />
        <span className={`h-2 w-2 shrink-0 rounded-full ${TONE_DOT[status.tone]}`} aria-hidden="true" />
        {/* The name stays readable; when space is tight, status and summary behind it shorten first */}
        <span className={`truncate text-text ${compact ? 'min-w-0 max-w-[8rem]' : 'max-w-[14rem] shrink-0'}`}>{name}</span>
        {!compact && (
          <span className="min-w-0 truncate text-text-tertiary" aria-hidden="true">
            {[status.text, at ? ago(at, now, locale) : null].filter(Boolean).join(' · ')}
          </span>
        )}
        {summary && <span className="hidden min-w-0 max-w-[12rem] truncate text-text-secondary lg:inline" aria-hidden="true">– {summary}</span>}
      </button>
      <button type="button" aria-label={tw.start.replace('{name}', name)} title={start.running ? tw.running : tw.start.replace('{name}', name)}
        disabled={start.busy} onClick={start.start}
        className="flex items-center rounded-r-md border-l border-border px-1.5 text-text-secondary hover:bg-surface-hover hover:text-accent focus-ring disabled:opacity-50">
        <Play className={compact ? 'h-3 w-3' : 'h-3.5 w-3.5'} aria-hidden="true" />
      </button>
      {open && (
        <WorkflowCard attachment={attachment} entry={entry} triggerRef={triggerRef} onClose={() => setOpen(false)}
          onStart={start.start} starting={start.starting} blocked={start.busy} />
      )}
    </span>
  );
}

/** The card of a workflow on the goal: last run, for „liefert den Stand“ the result, role, start and detach. */
function WorkflowCard({ attachment, entry, triggerRef, onClose, onStart, starting, blocked }: {
  attachment: GoalWorkflow;
  entry: WorkflowWidgetEntry | undefined;
  triggerRef: RefObject<HTMLElement | null>;
  onClose: () => void;
  onStart: () => void;
  starting: boolean;
  /** Is starting right now or still running — then no second start. */
  blocked: boolean;
}) {
  const t = useT();
  const tw = t.goals.workflows;
  const locale = useI18nStore((s) => s.locale);
  const [now] = useState(() => new Date());
  const updateRole = useUpdateGoalWorkflowRole(attachment.goalId);
  const detach = useDetachGoalWorkflow(attachment.goalId);
  const status = useStatusText(attachment, entry);
  const light = lightOf(entry);
  const at = lastRunAt(entry);

  return (
    <TimelinePopover label={tw.card.replace('{name}', attachment.workflowName)} onClose={onClose} triggerRef={triggerRef} wide>
      <div className="space-y-3 text-sm" data-testid="goal-workflow-card">
        <div className="flex items-start justify-between gap-2">
          <p className="min-w-0 break-words font-medium text-text">{attachment.workflowName}</p>
          <Link to={`/workflows/${attachment.workflowId}`} className="inline-flex shrink-0 items-center gap-1 text-xs text-text-secondary hover:text-accent">
            {tw.openWorkflow}
            <ExternalLink className="h-3 w-3" aria-hidden="true" />
          </Link>
        </div>

        <div className="flex items-center gap-2 text-xs text-text-secondary">
          <span className={`h-2 w-2 shrink-0 rounded-full ${TONE_DOT[shownTone(entry, false)]}`} aria-hidden="true" />
          {entry?.run ? (
            <Link to={runPath(attachment.workflowId, entry.run.runId)} className="hover:text-accent">
              {tw.lastRun.replace('{status}', t.runs.statuses[entry.run.status])}{at ? ` · ${ago(at, now, locale)}` : ''}
            </Link>
          ) : tw.noRun}
        </div>

        {attachment.role === 'STATUS' && (
          <div className="space-y-2 rounded-md border border-border bg-surface p-2.5" data-testid="goal-workflow-result">
            {light && entry?.result ? (
              <>
                <p className="flex items-center gap-1.5 text-xs font-medium text-text">
                  <span className={`h-2.5 w-2.5 rounded-full ${TONE_DOT[status.tone]}`} aria-hidden="true" />
                  {t.goals.guard.lights[light]}{entry.result.title ? ` – ${entry.result.title}` : ''}
                </p>
                <WorkflowResultContent result={entry.result} />
              </>
            ) : <p className="text-xs text-text-tertiary">{tw.noResult}</p>}
          </div>
        )}

        <fieldset className="space-y-1">
          <legend className="text-xs font-medium text-text-secondary">{tw.role}</legend>
          {attachment.guard ? (
            <p className="text-xs text-text-secondary">{tw.roles.STATUS} · {tw.guardNote}</p>
          ) : (
            <div className="inline-flex rounded-md border border-border p-0.5" role="radiogroup" aria-label={tw.role}>
              {ROLES.map((role) => {
                const Icon = ROLE_ICON[role];
                const active = attachment.role === role;
                return (
                  <button key={role} type="button" role="radio" aria-checked={active} disabled={updateRole.isPending}
                    title={tw.roleHints[role]}
                    onClick={() => !active && updateRole.mutate({ id: attachment.id, role })}
                    className={`inline-flex items-center gap-1 rounded px-2 py-1 text-xs focus-ring ${
                      active ? 'bg-accent text-text-inverse' : 'text-text-secondary hover:text-text'}`}>
                    <Icon className="h-3 w-3" aria-hidden="true" />
                    {tw.roles[role]}
                  </button>
                );
              })}
            </div>
          )}
        </fieldset>

        <div className="flex items-center justify-between gap-2 border-t border-border pt-2">
          {attachment.guard ? <span /> : (
            <button type="button" disabled={detach.isPending} onClick={() => detach.mutate(attachment.id, { onSuccess: onClose })}
              className="rounded px-2 py-1 text-xs font-medium text-danger hover:bg-danger-subtle focus-ring disabled:opacity-50">
              {tw.detach}
            </button>
          )}
          <Button size="sm" icon={<Play className="h-3.5 w-3.5" />} loading={starting} disabled={blocked} onClick={onStart}>
            {tw.startLabel}
          </Button>
        </div>
      </div>
    </TimelinePopover>
  );
}
