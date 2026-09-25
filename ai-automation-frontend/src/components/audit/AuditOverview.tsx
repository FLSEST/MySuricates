import { ArrowRight, ShieldCheck } from 'lucide-react';
import type { AuditSummary } from '../../hooks/useAuditCenter';
import { useT, type Locale } from '../../i18n';
import { formatRelativeTime } from '../../utils/formatTime';
import { CenteredLoading, GateBadge, TemplateValue, Trend } from './AuditParts';

const OVERVIEW_COLUMNS = 'grid-cols-[minmax(240px,2fr)_130px_110px_180px_150px_32px]';

/** Every audit with gate, trend, open and accepted findings and its last run; a row opens the audit. */
export function AuditOverview({ audits, loading, projectNames, locale, onOpen }: {
  audits: AuditSummary[]; loading: boolean; projectNames: Map<string, string>; locale: Locale;
  onOpen: (audit: AuditSummary) => void;
}) {
  const t = useT();
  if (loading) return <CenteredLoading />;
  if (!audits.length) {
    return <div className="flex-1 flex flex-col items-center justify-center text-text-secondary gap-3">
      <ShieldCheck className="w-10 h-10 opacity-40" />
      <p>{t.auditCenter.empty}</p>
    </div>;
  }
  return (
    <div className="flex-1 min-h-0 overflow-y-auto border-y border-border">
      <div className={`grid ${OVERVIEW_COLUMNS} gap-4 px-4 py-2 text-xs font-medium text-text-tertiary uppercase`}>
        <span>{t.auditCenter.colAudit}</span><span>{t.auditCenter.colGate}</span><span>{t.auditCenter.colTrend}</span>
        <span>{t.auditCenter.colOpenFindings}</span><span>{t.auditCenter.colLastRun}</span><span />
      </div>
      {audits.map((audit) => (
        <button key={`${audit.projectId}:${audit.auditKey}`} onClick={() => onOpen(audit)}
          className={`w-full grid ${OVERVIEW_COLUMNS} gap-4 items-center px-4 py-4 border-t border-border text-left hover:bg-surface-raised transition-colors`}>
          <span className="min-w-0">
            <span className="block font-medium text-text truncate">{audit.title}</span>
            <span className="block text-xs text-text-tertiary truncate">{projectNames.get(audit.projectId) ?? audit.projectId} · {audit.category}</span>
          </span>
          <GateBadge status={audit.gateStatus} />
          <Trend trend={audit.trend} />
          <FindingCounts audit={audit} />
          <span className="text-xs text-text-secondary">
            {formatRelativeTime(audit.generatedAt, locale)}
            {audit.stale && <span className="block text-warning">{t.auditCenter.stale}</span>}
            {audit.latestAttemptStatus === 'INVALID' && <span className="block text-danger">{t.auditCenter.lastAttemptInvalid}</span>}
          </span>
          <ArrowRight className="w-4 h-4 text-text-tertiary" />
        </button>
      ))}
    </div>
  );
}

/** Open findings (with their severities) and, apart from them, the accepted risks. */
function FindingCounts({ audit }: { audit: AuditSummary }) {
  const t = useT();
  const counts = audit.openFindingsBySeverity;
  const open = Object.values(counts).reduce((sum, count) => sum + count, 0);
  const accepted = audit.findingsByStatus.ACCEPTED_RISK ?? 0;
  return <span className="text-sm text-text" data-testid="finding-counts">
    <TemplateValue template={t.auditCenter.openCount} placeholder="{count}"><strong>{open}</strong></TemplateValue>
    {accepted > 0 && <span className="text-text-tertiary" data-testid="accepted-count"> · {t.auditCenter.acceptedCount.replace('{count}', String(accepted))}</span>}
    <span className="block text-xs text-text-tertiary">{t.auditCenter.severityCounts.replace('{critical}', String(counts.CRITICAL ?? 0)).replace('{high}', String(counts.HIGH ?? 0))}</span>
  </span>;
}
