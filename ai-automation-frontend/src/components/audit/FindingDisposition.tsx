import type { AuditFinding } from '../../hooks/useAuditCenter';
import { useT, type Locale } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';

const REVIEW_INTERVAL_MS = 180 * 24 * 60 * 60 * 1000;

/** How a finding was filed: date, review date of an accepted risk, the reason and the evidence snapshot. */
export function FindingDisposition({ finding, locale }: { finding: AuditFinding; locale: Locale }) {
  const t = useT();
  if (!finding.dispositionAt && !finding.dispositionEvidence && !finding.dispositionNote) return null;
  const formatDay = (value: string | Date) => formatDate(value, locale, { day: '2-digit', month: '2-digit', year: 'numeric' });
  const reviewDue = finding.dispositionAt ? new Date(new Date(finding.dispositionAt).getTime() + REVIEW_INTERVAL_MS) : null;
  return <div className="mt-3 text-xs text-text-tertiary border-l-2 border-warning/60 pl-3 space-y-1" data-testid="disposition">
    {finding.dispositionAt && <p>
      {t.auditCenter.acceptedAt} {formatDay(finding.dispositionAt)}
      {finding.status === 'ACCEPTED_RISK' && reviewDue && <> · {t.auditCenter.reviewDue} {formatDay(reviewDue)}</>}
    </p>}
    {finding.dispositionNote && <p className="whitespace-pre-wrap text-text-secondary">{t.auditCenter.dispositionNote}: {finding.dispositionNote}</p>}
    {finding.dispositionEvidence && <details>
      <summary className="cursor-pointer">{t.auditCenter.evidenceSnapshot}</summary>
      <pre className="font-mono whitespace-pre-wrap mt-1">{finding.dispositionEvidence}</pre>
    </details>}
  </div>;
}
