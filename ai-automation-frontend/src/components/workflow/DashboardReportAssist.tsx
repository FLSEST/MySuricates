import { Sparkles, Loader2 } from 'lucide-react';
import { useT, useI18nStore } from '../../i18n';
import { apiErrorMessage } from '../../api/client';
import { formatDateTime } from '../../utils/formatTime';
import {
  useDashboardReportAssist, useDashboardReportAssistAvailable,
} from '../../hooks/useDashboardReportAssist';
import { formFromProposal, type ReportForm } from './dashboardReport';

interface DashboardReportAssistProps {
  /** The saved workflow whose latest run the proposal is derived from. */
  workflowId: string;
  onApply: (form: ReportForm) => void;
}

/**
 * Proposes the report fields from the latest run — and writes nothing on its own.
 *
 * The proposal is AI text, so it appears in a review area as plain React text nodes and only
 * reaches the block config when a human clicks "apply". That is the whole difference between this
 * and a block that lets an AI fill the dashboard unattended.
 */
export function DashboardReportAssist({ workflowId, onApply }: DashboardReportAssistProps) {
  const t = useT().workflows.dashboardReport.assist;
  const locale = useI18nStore((s) => s.locale);
  const available = useDashboardReportAssistAvailable();
  const assist = useDashboardReportAssist();

  if (!available) return null;

  const proposal = assist.data?.proposal;

  return (
    <div data-testid="dashboard-report-assist" className="rounded-lg border border-ai/30 bg-ai-subtle p-3 space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-medium text-ai flex items-center gap-1.5">
          <Sparkles className="w-3.5 h-3.5" />
          {t.heading}
        </p>
        <button
          type="button"
          data-testid="dashboard-report-assist-run"
          disabled={assist.isPending}
          className="inline-flex items-center gap-1 rounded-md bg-accent px-2 py-1 text-xs text-text-inverse disabled:opacity-60"
          onClick={() => assist.mutate(workflowId)}
        >
          {assist.isPending && <Loader2 className="h-3 w-3 animate-spin" />}
          {t.run}
        </button>
      </div>
      <p className="text-xs text-text-tertiary">{t.hint}</p>

      {assist.isError && (
        <p data-testid="dashboard-report-assist-error" className="text-xs text-danger break-words">
          {apiErrorMessage(assist.error, t.failed)}
        </p>
      )}

      {proposal && assist.data && (
        <div
          data-testid="dashboard-report-assist-proposal"
          className="space-y-1 rounded-md border border-card-border bg-surface p-2 text-xs text-text-secondary"
        >
          <p className="font-medium text-text break-words">{proposal.status}{proposal.title ? ` · ${proposal.title}` : ''}</p>
          <p
            data-testid="dashboard-report-assist-source"
            className="text-[11px] text-text-tertiary break-words"
          >
            {/* Function replacers: a '$' in a value (runId, provider) would otherwise be read as a
                replacement pattern like $& — house convention, see DashboardReportFields. */}
            {t.source
              .replace('{provider}', () => assist.data?.provider || '-')
              .replace('{runId}', () => assist.data?.runId ?? '-')
              .replace('{completedAt}', () => (assist.data?.runCompletedAt
                ? formatDateTime(assist.data.runCompletedAt, locale)
                : '-'))}
          </p>
          {proposal.kpis.map((kpi, index) => (
            <p key={`kpi-${index}`} className="break-words">{kpi.label}: {kpi.value}{kpi.trend ? ` (${kpi.trend})` : ''}</p>
          ))}
          {proposal.items.map((item, index) => (
            <p key={`item-${index}`} className="break-words">• {item}</p>
          ))}
          {proposal.summary && <p className="break-words">{proposal.summary}</p>}
          <div className="flex flex-wrap gap-2 pt-1">
            <button
              type="button"
              data-testid="dashboard-report-assist-apply"
              className="rounded-md bg-accent px-2 py-1 text-xs text-text-inverse"
              onClick={() => onApply(formFromProposal(proposal))}
            >
              {t.apply}
            </button>
            <button
              type="button"
              data-testid="dashboard-report-assist-discard"
              className="rounded-md border border-border px-2 py-1 text-xs text-text-secondary"
              onClick={() => assist.reset()}
            >
              {t.discard}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
