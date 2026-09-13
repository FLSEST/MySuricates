import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { AuditCenter } from '../AuditCenter';
import type { AuditFinding, AuditSummary } from '../../hooks/useAuditCenter';

const BASE = '/api/v1';

const summary: AuditSummary = {
  projectId: 'p1',
  auditKey: 'mysuricates.health',
  category: 'quality',
  title: 'Health Audit',
  workflowId: 'w1',
  latestRunId: 'run-1',
  latestReportId: 'rep-1',
  gateStatus: 'YELLOW',
  gateDescription: null,
  generatedAt: '2026-09-01T08:00:00Z',
  latestAttemptStatus: 'VALID',
  latestAttemptError: null,
  trend: 'UNCHANGED',
  stale: false,
  findingsByStatus: { OPEN: 1, ACCEPTED_RISK: 2 },
  openFindingsBySeverity: { HIGH: 1 },
};

const accepted: AuditFinding = {
  id: 'f-accepted',
  projectId: 'p1',
  auditKey: 'mysuricates.health',
  fingerprint: 'abc',
  ruleKey: 'size.class',
  subject: 'Big.java',
  severity: 'HIGH',
  title: 'Big.java ist zu gross',
  description: 'Klasse ueber der Decke',
  evidence: 'Big.java: 340 LOC',
  tags: [],
  status: 'ACCEPTED_RISK',
  firstSeenRunId: 'run-0',
  lastSeenRunId: 'run-1',
  firstSeenAt: '2026-08-01T08:00:00Z',
  lastSeenAt: '2026-09-01T08:00:00Z',
  occurrenceCount: 3,
  regressionCount: 0,
  resolvedAt: '2026-08-15T10:00:00Z',
  dispositionNote: 'Bewusst gross, Split geplant',
  dispositionEvidence: 'Big.java: 320 LOC',
  dispositionAt: '2026-08-15T10:00:00Z',
  todos: [],
};

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function useAuditEndpoints() {
  server.use(
    http.get(`${BASE}/audits`, () => HttpResponse.json([summary])),
    http.get(`${BASE}/audits/:key/reports`, () =>
      HttpResponse.json({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })),
    http.get(`${BASE}/audit-findings/:id`, () => HttpResponse.json(accepted)),
    http.get(`${BASE}/audit-findings`, () =>
      HttpResponse.json({ content: [accepted], page: 0, size: 200, totalElements: 1, totalPages: 1 })),
    http.get(`${BASE}/reports`, () => HttpResponse.json([])),
  );
}

describe('AuditCenter accepted findings', () => {
  it('shows the accepted count next to the open findings in the overview', async () => {
    useAuditEndpoints();
    renderWithProviders(<AuditCenter />);

    const acceptedCount = await screen.findByTestId('accepted-count');
    expect(acceptedCount).toHaveTextContent('of which 2 accepted');
  });

  it('shows disposition snapshot, acceptance date and review date on a deep-linked finding', async () => {
    useAuditEndpoints();
    renderWithProviders(<AuditCenter />, { route: '/?finding=f-accepted' });

    const disposition = await screen.findByTestId('disposition');
    expect(disposition).toHaveTextContent('Accepted on');
    expect(disposition).toHaveTextContent('review due on');
    expect(disposition).toHaveTextContent('Bewusst gross, Split geplant');
    expect(disposition).toHaveTextContent('Big.java: 320 LOC');
    // 2026-08-15 + 180 days = 2027-02-11
    expect(disposition).toHaveTextContent(/2027/);
  });
});
