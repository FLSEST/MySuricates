import { Link } from 'react-router-dom';
import { AlertTriangle, CheckCircle2 } from 'lucide-react';
import { Badge } from '../ui';
import type { AuditReport } from '../../hooks/useAuditCenter';
import { useT, type Locale } from '../../i18n';
import { formatDateTime } from '../../utils/formatTime';
import { scheduleTriggerLink } from '../../utils/entityLinks';
import { TriggerSourceChip } from '../workflow/TriggerSourceChip';
import { GateBadge } from './AuditParts';
import { ReportChain } from './ReportOrigin';

/** Every attempt of an audit, valid or not, each with gate, trigger, chain and a link to its run. */
export function ReportHistory({ reports, locale }: { reports: AuditReport[]; locale: Locale }) {
  const t = useT();
  return <div className="divide-y divide-border">
    {reports.map((report) => <div key={report.id} className="py-4 flex items-start justify-between gap-4" data-testid="report-history-entry">
      <div className="flex items-start gap-3">
        {report.ingestionStatus === 'VALID' ? <CheckCircle2 className="w-5 h-5 text-success mt-0.5" /> : <AlertTriangle className="w-5 h-5 text-danger mt-0.5" />}
        <div>
          <div className="flex gap-2 items-center flex-wrap text-xs">
            <GateBadge status={report.gateStatus} /><Badge variant={report.ingestionStatus === 'VALID' ? 'success' : 'danger'}>{report.ingestionStatus}</Badge>
            <TriggerSourceChip source={report.triggerSource}
              to={report.chainRunId ? null : scheduleTriggerLink(report.triggerSource, report.scheduleId)} />
            <ReportChain report={report} />
          </div>
          <p className="text-sm text-text-secondary mt-2">{report.gateDescription ?? report.ingestionError ?? t.auditCenter.noDescription}</p>
          <p className="text-xs text-text-tertiary mt-1">{formatDateTime(report.generatedAt, locale)} · {t.auditCenter.findingsCount.replace('{count}', String(report.findingCount))} · v{report.workflowVersion ?? '?'}</p>
        </div>
      </div>
      <Link to={`/workflows/${report.workflowId}/run/${report.workflowRunId}`} className="text-xs text-accent hover:underline shrink-0">{t.auditCenter.openRun}</Link>
    </div>)}
  </div>;
}
