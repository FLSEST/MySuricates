import { Link } from 'react-router-dom';
import { AlertTriangle, Calendar, Link2 } from 'lucide-react';
import type { AuditReport, AuditSummary } from '../../hooks/useAuditCenter';
import { useT, type Locale } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import { TriggerSourceChip } from '../workflow/TriggerSourceChip';
import { TemplateValue } from './AuditParts';

const REPORT_DATE: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };

/** Says so when the latest attempt was invalid — the report on screen is then an older one. */
export function OlderReportNotice({ audit, report, locale }: { audit: AuditSummary; report: AuditReport; locale: Locale }) {
  const t = useT();
  if (audit.latestAttemptStatus === 'VALID') return null;
  return <div role="note" data-testid="older-report-notice"
    className="flex items-start gap-2 mb-4 px-3 py-2 rounded-md border border-warning/40 bg-warning-subtle text-sm text-warning">
    <AlertTriangle className="w-4 h-4 mt-0.5 shrink-0" />
    <div>
      <p>{t.auditCenter.olderReport.replace('{date}', formatTimestamp(report.generatedAt, locale, REPORT_DATE))}</p>
      {audit.latestAttemptError && <p className="text-xs text-text-secondary mt-1 whitespace-pre-wrap">{audit.latestAttemptError}</p>}
    </div>
  </div>;
}

/**
 * The head of a report: when, from which run, started by what, in which chain or by which
 * schedule, with which definition and model (a3b89355, 46a94a34).
 */
export function ReportOrigin({ report, locale }: { report: AuditReport; locale: Locale }) {
  const t = useT();
  return <div className="flex items-center gap-x-3 gap-y-2 text-xs text-text-tertiary mb-5 flex-wrap" data-testid="report-origin">
    <span className="text-text font-medium">{t.auditCenter.reportFrom.replace('{date}', formatTimestamp(report.generatedAt, locale, REPORT_DATE))}</span>
    <Link to={`/workflows/${report.workflowId}/run/${report.workflowRunId}`} className="text-accent hover:underline">
      <TemplateValue template={t.auditCenter.runShort} placeholder="{id}"><span className="font-mono">{report.workflowRunId.slice(0, 8)}</span></TemplateValue>
    </Link>
    <TriggerSourceChip source={report.triggerSource} />
    <ReportChain report={report} />
    {!report.chainRunId && report.scheduleId && report.triggerSource !== 'SCHEDULE' && <span className="inline-flex items-center gap-1">
      <Calendar className="w-3 h-3" /> {t.auditCenter.schedule}
    </span>}
    {report.definitionVersionNumber != null && <span>{t.auditCenter.definitionVersion.replace('{number}', String(report.definitionVersionNumber))}</span>}
    {report.workflowVersion && <span>{t.auditCenter.workflowVersion.replace('{version}', report.workflowVersion)}</span>}
    <span>{report.provider ?? t.auditCenter.providerDefault} / {report.model ?? t.auditCenter.providerDefault}</span>
    <span>{t.auditCenter.findingsInSnapshot.replace('{count}', String(report.findingCount))}</span>
  </div>;
}

/** The chain a report's run was an entry of; its name links to that chain run (there is no chain detail page). */
export function ReportChain({ report }: { report: AuditReport }) {
  const t = useT();
  if (report.chainId && report.chainRunId) {
    return <Link to={`/chains/${report.chainId}/run/${report.chainRunId}`} title={t.auditCenter.openChainRun}
      className="inline-flex items-center gap-1 text-accent hover:underline" data-testid="report-chain">
      <Link2 className="w-3 h-3" /> {t.auditCenter.chainNamed.replace('{name}', report.chainName ?? report.chainId.slice(0, 8))}
    </Link>;
  }
  if (report.chainRunId) return <span className="inline-flex items-center gap-1" data-testid="report-chain"><Link2 className="w-3 h-3" /> {t.auditCenter.chainGone}</span>;
  return null;
}
