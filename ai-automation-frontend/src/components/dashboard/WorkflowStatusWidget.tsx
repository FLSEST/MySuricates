import { useNavigate } from 'react-router-dom';
import { AlertTriangle, Clock } from 'lucide-react';
import { Card } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { useT, useI18nStore } from '../../i18n';
import { formatDateTime, formatDuration, formatRelativeTime } from '../../utils/formatTime';
import type { WidgetProps } from './widgetDefinition';
import { WidgetSkeleton } from './WidgetSkeleton';
import { isWorkflowId, type WorkflowResultStatus } from './workflowWidgetContract';
import { useWorkflowWidgetEntry } from './workflowWidgetContext';
import { WorkflowWidgetMessage } from './WorkflowWidgetStates';

/** Fixed classes per gate value — Tailwind needs literal class names. */
const GATE_CLASSES: Record<WorkflowResultStatus, string> = {
  GREEN: 'text-success',
  YELLOW: 'text-warning',
  RED: 'text-danger',
};

/**
 * Widget A (spec E3): status of a workflow's latest run — name, run status, time, duration, quality
 * gate and whether it failed. Platform facts only; this widget never reads the dashboard_widget
 * contract and never renders workflow output.
 */
export function WorkflowStatusWidget({ config }: WidgetProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const texts = t.dashboard.workflowWidgets;
  const workflowId = isWorkflowId(config.workflowId) ? config.workflowId : undefined;
  const { entry, isLoading, error } = useWorkflowWidgetEntry(workflowId);

  if (!workflowId) return <WorkflowWidgetMessage label={texts.status} message={texts.unconfigured} />;
  if (error) return <WorkflowWidgetMessage label={texts.status} message={texts.loadFailed} />;
  if (!entry) return isLoading ? <WidgetSkeleton size="third" /> : <WorkflowWidgetMessage label={texts.status} message={texts.noRun} />;
  if (!entry.available) return <WorkflowWidgetMessage label={texts.status} message={texts.unavailable} />;

  const name = entry.workflowName ?? texts.status;
  const run = entry.run;
  if (!run) return <WorkflowWidgetMessage label={name} message={texts.noRun} />;

  return (
    <Card className={`flex flex-col gap-2 ${run.status === 'FAILED' ? 'border-danger/40' : ''}`}>
      <div className="flex items-start justify-between gap-2">
        <p className="min-w-0 text-sm font-medium text-text break-words">{name}</p>
        <StatusBadge status={run.status} />
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
        <dt className="text-text-tertiary">{texts.lastRun}</dt>
        <dd className="text-text-secondary">
          {run.startedAt
            ? `${formatDateTime(run.startedAt, locale)} · ${formatRelativeTime(run.startedAt, locale)}`
            : '-'}
        </dd>
        <dt className="text-text-tertiary">{texts.duration}</dt>
        <dd className="flex items-center gap-1 text-text-secondary">
          <Clock className="h-3 w-3 shrink-0" />
          {formatDuration(run.durationMs)}
        </dd>
        {run.gateStatus && (
          <>
            <dt className="text-text-tertiary">{texts.gate}</dt>
            <dd className={`font-medium ${GATE_CLASSES[run.gateStatus]}`}>{run.gateStatus}</dd>
          </>
        )}
      </dl>
      {run.status === 'FAILED' && (
        <p className="flex items-center gap-1 text-xs text-danger">
          <AlertTriangle className="h-3 w-3 shrink-0" />
          {texts.latestFailed}
        </p>
      )}
      <button
        type="button"
        onClick={() => navigate(`/workflows/${entry.workflowId}/run/${run.runId}`)}
        aria-label={`${texts.openRun}: ${name}`}
        className="self-start text-xs text-accent hover:text-accent-hover"
      >
        {texts.openRun}
      </button>
    </Card>
  );
}
