import { Sparkles, Loader2 } from 'lucide-react';
import { useT } from '../../i18n';
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

      {proposal && (
        <div
          data-testid="dashboard-report-assist-proposal"
          className="space-y-1 rounded-md border border-card-border bg-surface p-2 text-xs text-text-secondary"
        >
          <p className="font-medium text-text">{proposal.status}{proposal.title ? ` · ${proposal.title}` : ''}</p>
          {proposal.kpis.map((kpi, index) => (
            <p key={`kpi-${index}`}>{kpi.label}: {kpi.value}{kpi.trend ? ` (${kpi.trend})` : ''}</p>
          ))}
          {proposal.items.map((item, index) => (
            <p key={`item-${index}`}>• {item}</p>
          ))}
          {proposal.summary && <p>{proposal.summary}</p>}
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
