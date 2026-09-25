import { describe, it, expect } from 'vitest';
import { en } from '../i18n/en';
import type { AuditSummary } from '../hooks/useAuditCenter';
import { filterCount, filterLabel, filterOf, initialFindingFilter, withFilter } from './auditFindingFilter';

const audit = {
  findingsByStatus: { OPEN: 3, AWAITING_VERIFICATION: 2, ACCEPTED_RISK: 7, RESOLVED: 250 },
} as unknown as AuditSummary;

describe('auditFindingFilter', () => {
  it('files every status under exactly one chip, awaiting verification under open', () => {
    expect(filterOf('OPEN')).toBe('open');
    expect(filterOf('AWAITING_VERIFICATION')).toBe('open');
    expect(filterOf('ACCEPTED_RISK')).toBe('accepted');
    expect(filterOf('DISMISSED')).toBe('dismissed');
    expect(filterOf('RESOLVED')).toBe('resolved');
  });

  it('counts a chip from the summary, zero for a status that is missing', () => {
    expect(filterCount(audit, 'open')).toBe(5);
    expect(filterCount(audit, 'accepted')).toBe(7);
    expect(filterCount(audit, 'dismissed')).toBe(0);
    expect(filterCount(audit, 'resolved')).toBe(250);
  });

  it('starts with open, plus the chip of a deep-linked finding outside it', () => {
    expect(initialFindingFilter(undefined)).toEqual(['open']);
    expect(initialFindingFilter('AWAITING_VERIFICATION')).toEqual(['open']);
    expect(initialFindingFilter('RESOLVED')).toEqual(['open', 'resolved']);
  });

  it('names a chip like the badge of its first status', () => {
    expect(filterLabel(en, 'open')).toBe(en.auditCenter.statusBadges.OPEN);
    expect(filterLabel(en, 'accepted')).toBe(en.auditCenter.statusBadges.ACCEPTED_RISK);
  });

  it('switches one chip on in chip order and leaves a chip that is on alone', () => {
    expect(withFilter(['resolved'], 'accepted')).toEqual(['accepted', 'resolved']);
    expect(withFilter(['open', 'accepted'], 'accepted')).toEqual(['open', 'accepted']);
  });
});
