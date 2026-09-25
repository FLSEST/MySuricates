import type { AuditFindingStatus, AuditSummary } from '../hooks/useAuditCenter';
import type { TranslationKeys } from '../i18n';

export type FindingFilter = 'open' | 'accepted' | 'dismissed' | 'resolved';

/**
 * The status chips of the Audit Center's findings tab, each named like the badge of its first
 * status. "Open" is everything that still needs work or a check — the default, so settled findings
 * do not bury it (46a94a34).
 */
export const FINDING_FILTERS: Record<FindingFilter, [AuditFindingStatus, ...AuditFindingStatus[]]> = {
  open: ['OPEN', 'AWAITING_VERIFICATION'],
  accepted: ['ACCEPTED_RISK'],
  dismissed: ['DISMISSED'],
  resolved: ['RESOLVED'],
};

export const FILTER_ORDER = Object.keys(FINDING_FILTERS) as FindingFilter[];

/** The chip a status belongs to. */
export function filterOf(status: AuditFindingStatus): FindingFilter {
  return FILTER_ORDER.find((filter) => FINDING_FILTERS[filter].includes(status)) ?? 'open';
}

/** From the summary, which counts every finding — the loaded list stops at a page per status. */
export function filterCount(audit: AuditSummary, filter: FindingFilter): number {
  return FINDING_FILTERS[filter].reduce((sum, status) => sum + (audit.findingsByStatus[status] ?? 0), 0);
}

/** A deep-linked finding outside "open" switches its own chip on as well, so that it is visible. */
export function initialFindingFilter(linkedStatus: AuditFindingStatus | undefined): FindingFilter[] {
  const linked = linkedStatus ? filterOf(linkedStatus) : 'open';
  return linked === 'open' ? ['open'] : ['open', linked];
}

/** The chip's name: the badge text of its first status ("Offen", "Akzeptiert" …). */
export function filterLabel(t: TranslationKeys, filter: FindingFilter): string {
  return t.auditCenter.statusBadges[FINDING_FILTERS[filter][0]];
}

/** `current` plus `filter`, in chip order — for switching one chip on from outside the chip row. */
export function withFilter(current: FindingFilter[], filter: FindingFilter): FindingFilter[] {
  return FILTER_ORDER.filter((item) => item === filter || current.includes(item));
}
