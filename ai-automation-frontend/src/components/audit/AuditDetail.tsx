import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, ExternalLink, FileText, History } from 'lucide-react';
import { type AuditFindingStatus, type AuditSummary, useShownAuditReport } from '../../hooks/useAuditCenter';
import { useT, type Locale } from '../../i18n';
import { filterCount, initialFindingFilter } from '../../utils/auditFindingFilter';
import { AuditFindings } from './AuditFindings';
import { AuditMarkdown, CenteredLoading, GateBadge, Trend } from './AuditParts';
import { ReportHistory } from './ReportHistory';
import { OlderReportNotice, ReportOrigin } from './ReportOrigin';

export type AuditTab = 'report' | 'history' | 'findings';

/** One audit: its header with links, then the current report, the history or the findings. */
export function AuditDetail({ audit, tab, setTab, locale, projectName, requestedFinding, requestedFindingStatus }: {
  audit: AuditSummary; tab: AuditTab; setTab: (tab: AuditTab) => void; locale: Locale;
  projectName?: string; requestedFinding: string | null; requestedFindingStatus?: AuditFindingStatus;
}) {
  const t = useT();
  const { reports, shown, markdown, markdownLoading, isLoading } = useShownAuditReport(audit);
  // Kept here, not in the tab: switching to the report and back keeps the chosen chips.
  const [findingFilter, setFindingFilter] = useState(() => initialFindingFilter(requestedFindingStatus));
  // The header link follows the report on screen, like the run link in its head — not the latest attempt.
  const evidenceRun = shown ? `/workflows/${shown.workflowId}/run/${shown.workflowRunId}` : `/workflows/${audit.workflowId}/run/${audit.latestRunId}`;
  return (
    <div className="flex-1 min-h-0 flex flex-col gap-4">
      <div className="flex items-center justify-between gap-4 border-b border-border pb-4">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <h2 className="text-lg font-semibold text-text truncate">{audit.title}</h2>
            <GateBadge status={audit.gateStatus} />
            <Trend trend={audit.trend} />
          </div>
          <p className="text-xs text-text-tertiary mt-1">{projectName ?? audit.projectId} · {audit.auditKey}</p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <Link className="text-xs text-accent hover:underline inline-flex items-center gap-1" to={`/workflows/${audit.workflowId}`}>
            {t.auditCenter.workflowLink} <ExternalLink className="w-3 h-3" />
          </Link>
          <Link className="text-xs text-accent hover:underline inline-flex items-center gap-1" to={evidenceRun} data-testid="evidence-run">
            {t.auditCenter.evidenceRun} <ExternalLink className="w-3 h-3" />
          </Link>
        </div>
      </div>
      <div className="flex gap-1">
        <TabButton active={tab === 'report'} onClick={() => setTab('report')} icon={<FileText className="w-4 h-4" />}>{t.auditCenter.tabReport}</TabButton>
        <TabButton active={tab === 'history'} onClick={() => setTab('history')} icon={<History className="w-4 h-4" />}>{t.auditCenter.tabHistory}</TabButton>
        <TabButton active={tab === 'findings'} onClick={() => setTab('findings')} icon={<AlertTriangle className="w-4 h-4" />}>{t.auditCenter.tabFindings.replace('{count}', String(filterCount(audit, 'open')))}</TabButton>
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto border-t border-border pt-5">
        {tab !== 'findings' && isLoading ? <CenteredLoading /> : null}
        {tab === 'report' && shown && <div className="max-w-5xl">
          <OlderReportNotice audit={audit} report={shown} locale={locale} />
          <ReportOrigin report={shown} locale={locale} />
          {markdownLoading ? <CenteredLoading /> : <AuditMarkdown content={markdown ?? ''} />}
        </div>}
        {tab === 'history' && <ReportHistory reports={reports} locale={locale} />}
        {tab === 'findings' && <AuditFindings audit={audit} filter={findingFilter} setFilter={setFindingFilter}
          requestedFinding={requestedFinding} locale={locale} />}
      </div>
    </div>
  );
}

function TabButton({ active, onClick, icon, children }: { active: boolean; onClick: () => void; icon: React.ReactNode; children: React.ReactNode }) {
  return <button onClick={onClick} className={`inline-flex items-center gap-2 px-3 py-2 text-sm border-b-2 ${active ? 'border-accent text-accent' : 'border-transparent text-text-secondary hover:text-text'}`}>{icon}{children}</button>;
}
