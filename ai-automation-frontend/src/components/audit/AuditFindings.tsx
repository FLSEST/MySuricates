import { useEffect, useRef, type Dispatch, type SetStateAction } from 'react';
import { FilterChips } from '../ui';
import { FINDINGS_PAGE_SIZE, type AuditSummary, useAuditFindingsByStatus } from '../../hooks/useAuditCenter';
import { useFindingActions } from '../../hooks/useFindingActions';
import { useT, type Locale } from '../../i18n';
import { FILTER_ORDER, FINDING_FILTERS, filterCount, filterLabel, type FindingFilter } from '../../utils/auditFindingFilter';
import { AuditFindingItem } from './AuditFindingItem';
import { CenteredLoading } from './AuditParts';

/**
 * The findings of one audit behind status chips — "open" by default, counts from the summary
 * (46a94a34). The chosen chips live in the caller, so they survive switching tabs.
 */
export function AuditFindings({ audit, filter, setFilter, requestedFinding, locale }: {
  audit: AuditSummary; filter: FindingFilter[]; setFilter: Dispatch<SetStateAction<FindingFilter[]>>;
  requestedFinding: string | null; locale: Locale;
}) {
  const t = useT();
  const { findings, capped, isLoading } = useAuditFindingsByStatus(audit.projectId, audit.auditKey,
    filter.flatMap((item) => FINDING_FILTERS[item]));
  const actions = useFindingActions(filter, setFilter);
  const auditHasFindings = FILTER_ORDER.some((item) => filterCount(audit, item) > 0);
  // The deep-linked finding comes into view once (6ff1c580) — when the list is complete, so rows of a
  // status that arrives later cannot push it out again.
  const linkedRow = useRef<HTMLElement>(null);
  const scrolledTo = useRef<string | null>(null);
  const linkedListed = !!requestedFinding && findings.some((finding) => finding.id === requestedFinding);
  useEffect(() => {
    if (isLoading || !linkedListed || scrolledTo.current === requestedFinding) return;
    scrolledTo.current = requestedFinding;
    linkedRow.current?.scrollIntoView?.({ block: 'start' });
  }, [isLoading, linkedListed, requestedFinding]);
  return <div className="space-y-3">
    <FilterChips ariaLabel={t.auditCenter.findingsFilterLabel} selected={filter} onChange={setFilter}
      options={FILTER_ORDER.map((value) => ({ value, label: filterLabel(t, value), count: filterCount(audit, value) }))} />
    {isLoading && !findings.length && <CenteredLoading />}
    {!isLoading && !findings.length && <p className="text-sm text-text-secondary">
      {auditHasFindings ? t.auditCenter.noFindingsInFilter : t.auditCenter.noFindings}
    </p>}
    {findings.length > 0 && <div className="divide-y divide-border">
      {findings.map((finding) => <AuditFindingItem key={finding.id} finding={finding} actions={actions}
        highlighted={requestedFinding === finding.id} workflowId={audit.workflowId} locale={locale}
        sectionRef={requestedFinding === finding.id ? linkedRow : undefined} />)}
    </div>}
    {capped && <p className="text-xs text-text-tertiary">{t.auditCenter.findingsCapped.replace('{size}', String(FINDINGS_PAGE_SIZE))}</p>}
  </div>;
}
