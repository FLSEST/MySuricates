import { useNavigate } from 'react-router-dom';
import { ArrowDown, ArrowUp, Minus, Sparkles } from 'lucide-react';
import { Card } from '../ui';
import { useT, useI18nStore } from '../../i18n';
import { formatRelativeTime } from '../../utils/formatTime';
import type { WidgetProps } from './widgetDefinition';
import { WidgetSkeleton } from './WidgetSkeleton';
import { isWorkflowId, type WorkflowResultStatus, type WorkflowResultTrend } from './workflowWidgetContract';
import { useWorkflowWidgetEntry } from './workflowWidgetContext';
import { WorkflowWidgetMessage } from './WorkflowWidgetStates';

/** Fixed classes per contract value — Tailwind needs literal class names, and enums are all we allow. */
const STATUS_DOT: Record<WorkflowResultStatus, string> = {
  GREEN: 'bg-success',
  YELLOW: 'bg-warning',
  RED: 'bg-danger',
};

const TREND_ICON: Record<WorkflowResultTrend, typeof ArrowUp> = {
  UP: ArrowUp,
  DOWN: ArrowDown,
  FLAT: Minus,
};

/**
 * Widget B (spec E3): what a workflow reported through the dashboard_widget contract. Every value
 * here went through the backend's validation and the client-side contract check, and every one of
 * them is rendered as a React text node — no markup, no markdown, no links, no images. Status and
 * trend are enums, so the only thing they can pick is a class name from the tables above.
 *
 * Without a valid contract the widget says so and offers the run; it never shows raw output and
 * never invents a green result.
 */
export function WorkflowResultWidget({ config }: WidgetProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const texts = t.dashboard.workflowWidgets;
  const trendLabels: Record<WorkflowResultTrend, string> = {
    UP: texts.trendUp, DOWN: texts.trendDown, FLAT: texts.trendFlat,
  };
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
          <p className="mt-0.5 flex items-center gap-1 text-[10px] uppercase tracking-wide text-ai">
            <Sparkles className="h-3 w-3 shrink-0" />
            {texts.aiGenerated}
          </p>
        </div>
        <span className="shrink-0 rounded-full bg-ai-subtle px-2 py-0.5 text-[10px] font-medium text-ai">
          {result.status}
        </span>
      </div>

      {result.kpis.length > 0 && (
        <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-xs">
          {result.kpis.map((kpi, index) => {
            const TrendIcon = kpi.trend ? TREND_ICON[kpi.trend] : null;
            return (
              <div key={`${kpi.label}-${index}`} className="contents">
                <dt className="min-w-0 break-words text-text-secondary">{kpi.label}</dt>
                <dd className="flex items-center justify-end gap-1 break-words font-medium text-text">
                  {kpi.value}
                  {TrendIcon && kpi.trend && (
                    <TrendIcon className="h-3 w-3 shrink-0 text-text-tertiary" aria-label={trendLabels[kpi.trend]} />
                  )}
                </dd>
              </div>
            );
          })}
        </dl>
      )}

      {result.items.length > 0 && (
        // A CSS list marker, not a literal "• " text node: the latter would merge with the item text
        // into one DOM text node and break exact text matching (both in tests and for assistive tech).
        <ul className="list-disc space-y-1 pl-4 text-xs text-text-secondary">
          {result.items.map((item, index) => (
            <li key={`${index}-${item}`} className="break-words">{item}</li>
          ))}
        </ul>
      )}

      {result.summary && <p className="text-xs text-text-secondary break-words">{result.summary}</p>}

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
