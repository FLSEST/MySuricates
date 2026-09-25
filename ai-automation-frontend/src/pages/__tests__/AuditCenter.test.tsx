import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { AuditCenter } from '../AuditCenter';
import type { AuditFinding, AuditReport, AuditSummary } from '../../hooks/useAuditCenter';

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
  unknownRuleKey: false,
  unverifiedEvidence: false,
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

// d75fbfef: dispositionNote existed only in the API — the page accepted without a reason
describe('AuditCenter disposition reason', () => {
  const open: AuditFinding = { ...accepted, id: 'f-open', status: 'OPEN', dispositionNote: null, dispositionAt: null, dispositionEvidence: null };

  function useOpenFindingEndpoints(patches: unknown[]) {
    useAuditEndpoints();
    server.use(
      http.get(`${BASE}/audit-findings/:id`, () => HttpResponse.json(open)),
      http.get(`${BASE}/audit-findings`, () =>
        HttpResponse.json({ content: [open], page: 0, size: 200, totalElements: 1, totalPages: 1 })),
      http.patch(`${BASE}/audit-findings/:id`, async ({ request }) => {
        patches.push(await request.json());
        return HttpResponse.json({ ...open, status: 'ACCEPTED_RISK' });
      }),
    );
  }

  it('asks for the reason before accepting a risk and sends it along', async () => {
    const patches: unknown[] = [];
    useOpenFindingEndpoints(patches);
    renderWithProviders(<AuditCenter />, { route: '/?finding=f-open' });

    fireEvent.change(await screen.findByTestId('finding-status'), { target: { value: 'ACCEPTED_RISK' } });
    expect(patches).toEqual([]); // nothing saved yet — the reason comes first

    const form = await screen.findByTestId('disposition-form');
    fireEvent.change(form.querySelector('textarea')!, { target: { value: 'Tragbar bis zum Split' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(patches).toEqual([
      { status: 'ACCEPTED_RISK', dispositionNote: 'Tragbar bis zum Split' },
    ]));
  });

  it('saves other statuses right away, without asking', async () => {
    const patches: unknown[] = [];
    useOpenFindingEndpoints(patches);
    renderWithProviders(<AuditCenter />, { route: '/?finding=f-open' });

    fireEvent.change(await screen.findByTestId('finding-status'), { target: { value: 'AWAITING_VERIFICATION' } });

    await waitFor(() => expect(patches).toEqual([{ status: 'AWAITING_VERIFICATION' }]));
    expect(screen.queryByTestId('disposition-form')).toBeNull();
  });
});

// 46a94a34: the report tab showed provider and version only — not when, from which run, started by what
describe('AuditCenter report head', () => {
  const runId = 'd08d6770-72dd-4129-8c6d-aa9289681bb6';
  const report: AuditReport = {
    id: 'rep-1',
    projectId: 'p1',
    workflowId: 'w1',
    workflowRunId: runId,
    auditKey: 'mysuricates.health',
    category: 'quality',
    title: 'Health Audit',
    markdownContent: 'Body of the shown report',
    gateStatus: 'YELLOW',
    gateDescription: '6/10',
    provider: 'CLAUDE',
    model: 'opus',
    workflowVersion: '3.0.0',
    ingestionStatus: 'VALID',
    ingestionError: null,
    generatedAt: '2026-09-22T07:35:00Z',
    findingCount: 44,
    triggerSource: 'SCHEDULE',
    scheduleId: 'sched-1',
    chainRunId: 'chain-run-1',
    chainId: 'chain-1',
    chainName: 'Weekly audits',
    definitionVersionNumber: 12,
  };
  const invalid: AuditReport = {
    ...report, id: 'rep-2', workflowRunId: 'e1a2b3c4-0000-0000-0000-000000000000', markdownContent: '',
    ingestionStatus: 'INVALID', ingestionError: 'Envelope missing', generatedAt: '2026-09-23T07:35:00Z',
    gateStatus: null, gateDescription: null, findingCount: 0,
  };

  function useReports(reports: AuditReport[], auditSummary: AuditSummary = summary) {
    useAuditEndpoints();
    server.use(
      http.get(`${BASE}/audits`, () => HttpResponse.json([auditSummary])),
      http.get(`${BASE}/audits/:key/reports`, () =>
        HttpResponse.json({ content: reports, page: 0, size: 50, totalElements: reports.length, totalPages: 1 })),
    );
  }

  async function openAudit() {
    renderWithProviders(<AuditCenter />);
    fireEvent.click(await screen.findByText('Health Audit'));
  }

  it('names date, run, trigger, chain and definition of the shown report', async () => {
    useReports([report]);
    await openAudit();

    const head = await screen.findByTestId('report-origin');
    expect(head).toHaveTextContent(/Report from .*2026/);
    expect(within(head).getByRole('link', { name: 'Run d08d6770' })).toHaveAttribute('href', `/workflows/w1/run/${runId}`);
    expect(within(head).getByTestId('trigger-source')).toHaveTextContent('Started byschedule');
    expect(within(head).getByRole('link', { name: /Chain Weekly audits/ })).toHaveAttribute('href', '/chains/chain-1/run/chain-run-1');
    expect(head).toHaveTextContent('Definition #12');
    expect(head).toHaveTextContent('Workflow v3.0.0');
    expect(head).toHaveTextContent('CLAUDE / opus');
    // the chain's schedule is behind the chain, not a second origin
    expect(within(head).queryByText('Schedule')).toBeNull();
    expect(screen.getByText('Body of the shown report')).toBeInTheDocument();
    expect(screen.getByTestId('evidence-run')).toHaveAttribute('href', `/workflows/w1/run/${runId}`);
  });

  it('names the schedule of a run without chain whose trigger was not recorded', async () => {
    useReports([{ ...report, triggerSource: null, chainRunId: null, chainId: null, chainName: null }]);
    await openAudit();

    const head = await screen.findByTestId('report-origin');
    expect(within(head).getByText('Schedule')).toBeInTheDocument();
    expect(within(head).queryByTestId('trigger-source')).toBeNull();
    expect(within(head).queryByTestId('report-chain')).toBeNull();
  });

  it('shows what an older backend sends, without origin fields', async () => {
    const legacy: Partial<AuditReport> = { ...report };
    for (const key of ['triggerSource', 'scheduleId', 'chainRunId', 'chainId', 'chainName', 'definitionVersionNumber'] as const) delete legacy[key];
    useReports([legacy as AuditReport]);
    await openAudit();

    const head = await screen.findByTestId('report-origin');
    expect(head).toHaveTextContent(/Report from .*2026/);
    expect(within(head).getByRole('link', { name: 'Run d08d6770' })).toBeInTheDocument();
    expect(head).not.toHaveTextContent('Definition #');
    expect(within(head).queryByTestId('trigger-source')).toBeNull();
  });

  it('shows the report the summary names, not the first valid one of the page', async () => {
    const newer: AuditReport = { ...report, id: 'rep-9', markdownContent: 'Body of a newer report', generatedAt: '2026-09-24T07:35:00Z' };
    useReports([newer, report]);
    await openAudit();

    expect(await screen.findByText('Body of the shown report')).toBeInTheDocument();
    expect(screen.queryByText('Body of a newer report')).toBeNull();
  });

  it('says so when the latest attempt was invalid and the shown report is older', async () => {
    useReports([invalid, report], { ...summary, latestAttemptStatus: 'INVALID', latestAttemptError: 'Envelope missing' });
    await openAudit();

    const notice = await screen.findByTestId('older-report-notice');
    expect(notice).toHaveTextContent(/The latest attempt was invalid\. Showing the older report from .*2026/);
    expect(notice).toHaveTextContent('Envelope missing');
    expect(screen.getByText('Body of the shown report')).toBeInTheDocument();
    // the header link follows the report on screen, not the failed attempt
    expect(screen.getByTestId('evidence-run')).toHaveAttribute('href', `/workflows/w1/run/${runId}`);
  });

  it('shows no notice while the latest attempt is valid', async () => {
    useReports([report]);
    await openAudit();

    await screen.findByTestId('report-origin');
    expect(screen.queryByTestId('older-report-notice')).toBeNull();
  });

  it('shows trigger and chain on every history entry', async () => {
    useReports([invalid, { ...report, triggerSource: 'MANUAL', chainRunId: null, chainId: null, chainName: null }]);
    await openAudit();
    fireEvent.click(await screen.findByRole('button', { name: /History/ }));

    const [first, second] = await screen.findAllByTestId('report-history-entry');
    expect(within(first!).getByTestId('trigger-source')).toHaveTextContent('schedule');
    expect(within(first!).getByTestId('report-chain')).toHaveTextContent('Chain Weekly audits');
    expect(within(second!).getByTestId('trigger-source')).toHaveTextContent('manual');
    expect(within(second!).queryByTestId('report-chain')).toBeNull();
  });
});
