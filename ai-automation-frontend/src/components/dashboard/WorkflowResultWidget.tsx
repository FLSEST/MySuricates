import { useNavigate } from 'react-router-dom';
import { ClipboardList } from 'lucide-react';
import { Card } from '../ui';
import { useT, useI18nStore } from '../../i18n';
import { formatRelativeTime } from '../../utils/formatTime';
import type { WidgetProps } from './widgetDefinition';
import { WidgetSkeleton } from './WidgetSkeleton';
import { isWorkflowId, WORKFLOW_RESULT_STATUS_DOT as STATUS_DOT } from './workflowWidgetContract';
import { useWorkflowWidgetEntry } from './workflowWidgetContext';
import { WorkflowWidgetMessage } from './WorkflowWidgetStates';
import { WorkflowResultContent } from './WorkflowResultContent';

/**
 * Widget B (spec E3): what a workflow reported through the dashboard_widget contract. Every value
 * here went through the backend's validation and the client-side contract check, and every one of
 * them is rendered as a React text node — no markup, no markdown, no links, no images. Status and
 * trend are enums, so the only thing they can pick is a class name (fixed tables in the contract and in WorkflowResultContent).
 *
 * Without a valid contract the widget says so and offers the run; it never shows raw output and
 * never invents a green result.
 */
export function WorkflowResultWidget({ config }: WidgetProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const texts = t.dashboard.workflowWidgets;
  const workflowId = isWorkflowId(config.workflowId) ? config.workflowId : undefined;
  const { entry, isLoading, error } = useWorkflowWidgetEntry(workflowId);

  if (!workflowId) return <WorkflowWidgetMessage label={texts.result} message={texts.unconfigured} />;
  if (error) return <WorkflowWidgetMessage label={texts.result} message={texts.loadFailed} />;
  if (!entry) return isLoading ? <WidgetSkeleton size="third" /> : <WorkflowWidgetMessage label={texts.result} message={texts.noRun} />;
  if (!entry.available) return <WorkflowWidgetMessage label={texts.result} message={texts.unavailable} />;

  const name = entry.workflowName ?? texts.result;
  if (!entry.run) return <WorkflowWidgetMessage label={name} message={texts.noRun} />;
  if (!entry.result) {
    return (
      <WorkflowWidgetMessage
        label={texts.noResult}
        message={entry.resultState === 'INVALID' ? texts.resultInvalid : texts.resultMissing}
        workflowId={entry.workflowId}
        runId={entry.run.runId}
      />
    );
  }

  const { result, run } = entry;
  return (
    <Card className="flex flex-col gap-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="flex items-center gap-1.5 text-sm font-medium text-text break-words">
            <span className={`h-2 w-2 shrink-0 rounded-full ${STATUS_DOT[result.status]}`} aria-hidden="true" />
            {result.title ?? name}
          </p>
          {/*
            Provenance, not authorship: the contract reaches us the same way whether a run's own
            `dashboard-report` block wrote it (deterministic, no AI call) or an AI step printed the
            marker. The response carries no source field — adding one would widen a deliberately
            closed contract — so the label says only what is certain, and stays neutral: the `ai`
            token would claim an AI was involved.
          */}
          <p className="mt-0.5 flex items-center gap-1 text-[10px] uppercase tracking-wide text-text-secondary">
            <ClipboardList className="h-3 w-3 shrink-0" />
            {texts.reportedByRun}
          </p>
        </div>
        <span className="shrink-0 rounded-full bg-surface-raised px-2 py-0.5 text-[10px] font-medium text-text-secondary">
          {result.status}
        </span>
      </div>

      <WorkflowResultContent result={result} />

      <div className="flex items-center justify-between gap-2 text-xs text-text-tertiary">
        <span>{run.startedAt ? formatRelativeTime(run.startedAt, locale) : '-'}</span>
        <button
          type="button"
          onClick={() => navigate(`/workflows/${entry.workflowId}/run/${run.runId}`)}
          aria-label={`${texts.openRun}: ${name}`}
          className="text-accent hover:text-accent-hover"
        >
          {texts.openRun}
        </button>
      </div>
    </Card>
  );
}
