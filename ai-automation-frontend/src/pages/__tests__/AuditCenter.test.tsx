import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { AuditCenter } from '../AuditCenter';
import { ToastContainer } from '../../components/ui';
import { useToastStore } from '../../stores/toastStore';
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
  useToastStore.setState({ toasts: [] });
});
afterAll(() => server.close());

/** `/audit-findings` like the backend: `status` narrows the list, `total` overrides the count of a status. */
function findingsPage(findings: () => AuditFinding[], total: Partial<Record<string, number>> = {}) {
  return ({ request }: { request: Request }) => {
    const status = new URL(request.url).searchParams.get('status');
    const content = findings().filter((finding) => !status || finding.status === status);
    const totalElements = (status && total[status]) || content.length;
    return HttpResponse.json({ content, page: 0, size: 200, totalElements, totalPages: 1 });
  };
}

function useAuditEndpoints() {
  server.use(
    http.get(`${BASE}/audits`, () => HttpResponse.json([summary])),
    http.get(`${BASE}/audits/:key/reports`, () =>
      HttpResponse.json({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })),
    http.get(`${BASE}/audit-findings/:id`, () => HttpResponse.json(accepted)),
    http.get(`${BASE}/audit-findings`, findingsPage(() => [accepted])),
    http.get(`${BASE}/reports`, () => HttpResponse.json([])),
  );
}

describe('AuditCenter accepted findings', () => {
  // 46a94a34: „davon 2 akzeptiert“ was wrong — the open count never contains accepted findings
  it('shows open and accepted findings side by side in the overview', async () => {
    useAuditEndpoints();
    renderWithProviders(<AuditCenter />);

    const counts = await screen.findByTestId('finding-counts');
    expect(counts).toHaveTextContent('open 1 · accepted 2');
    expect(counts).toHaveTextContent('(0 critical, 1 high)');
    expect(counts).not.toHaveTextContent('of which');
  });

  it('leaves the accepted part out while nothing is accepted', async () => {
    useAuditEndpoints();
    server.use(http.get(`${BASE}/audits`, () => HttpResponse.json([{ ...summary, findingsByStatus: { OPEN: 1 } }])));
    renderWithProviders(<AuditCenter />);

    expect(await screen.findByTestId('finding-counts')).toHaveTextContent(/^open 1\(0 critical, 1 high\)$/);
    expect(screen.queryByTestId('accepted-count')).toBeNull();
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
      http.get(`${BASE}/audit-findings`, findingsPage(() => [open])),
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

// 46a94a34: the findings tab counted and listed every status — 164 findings, 8 of them open
describe('AuditCenter findings filter', () => {
  const finding = (id: string, status: AuditFinding['status'], title: string): AuditFinding => ({
    ...accepted, id, status, title, dispositionNote: null, dispositionAt: null, dispositionEvidence: null,
  });
  const counted: AuditSummary = {
    ...summary,
    findingsByStatus: { OPEN: 1, AWAITING_VERIFICATION: 1, ACCEPTED_RISK: 1, RESOLVED: 250 },
    openFindingsBySeverity: { HIGH: 2 },
  };

  /** Serves `initial` like the backend, PATCH included; returns the statuses the page asked for. */
  function useFindings(initial: AuditFinding[]) {
    const store = new Map(initial.map((item) => [item.id, item]));
    const requested: string[] = [];
    useAuditEndpoints();
    server.use(
      http.get(`${BASE}/audits`, () => HttpResponse.json([counted])),
      http.get(`${BASE}/audit-findings/:id`, ({ params }) => HttpResponse.json(store.get(String(params.id)))),
      http.get(`${BASE}/audit-findings`, (info) => {
        requested.push(new URL(info.request.url).searchParams.get('status') ?? 'ALL');
        return findingsPage(() => [...store.values()], { RESOLVED: 250 })(info);
      }),
      http.patch(`${BASE}/audit-findings/:id`, async ({ params, request }) => {
        const id = String(params.id);
        store.set(id, { ...store.get(id)!, ...(await request.json() as Partial<AuditFinding>) });
        return HttpResponse.json(store.get(id));
      }),
    );
    return requested;
  }

  const all = [
    finding('f-1', 'OPEN', 'Open finding'),
    finding('f-2', 'AWAITING_VERIFICATION', 'Awaiting finding'),
    finding('f-3', 'ACCEPTED_RISK', 'Accepted finding'),
    finding('f-4', 'RESOLVED', 'Resolved finding'),
  ];

  const chips = () => within(screen.getByRole('group', { name: 'Filter findings by status' }));

  async function openFindingsTab() {
    renderWithProviders(<AuditCenter />);
    fireEvent.click(await screen.findByText('Health Audit'));
    fireEvent.click(screen.getByRole('button', { name: /^Findings/ }));
  }

  it('lists the open findings only, and the accepted ones once their chip is on', async () => {
    const requested = useFindings(all);
    await openFindingsTab();

    expect(await screen.findByText('Open finding')).toBeInTheDocument();
    expect(screen.getByText('Awaiting finding')).toBeInTheDocument();
    expect(screen.queryByText('Accepted finding')).toBeNull();
    expect(screen.queryByText('Resolved finding')).toBeNull();
    expect(chips().getByRole('button', { name: /^Open/ })).toHaveAttribute('aria-pressed', 'true');
    expect(chips().getByRole('button', { name: /^Accepted/ })).toHaveAttribute('aria-pressed', 'false');
    expect([...requested].sort()).toEqual(['AWAITING_VERIFICATION', 'OPEN']);

    fireEvent.click(chips().getByRole('button', { name: /^Accepted/ }));

    expect(await screen.findByText('Accepted finding')).toBeInTheDocument();
    expect(screen.queryByText('Resolved finding')).toBeNull();
    expect(requested).not.toContain('RESOLVED');
  });

  it('counts from the summary, not from the loaded page', async () => {
    useFindings(all);
    await openFindingsTab();

    expect(screen.getByRole('button', { name: /^Findings/ })).toHaveTextContent('Findings (2 open)');
    expect(chips().getByRole('button', { name: /^Open/ })).toHaveTextContent('Open2');
    expect(chips().getByRole('button', { name: /^Accepted/ })).toHaveTextContent('Accepted1');
    expect(chips().getByRole('button', { name: /^Dismissed/ })).toHaveTextContent('Dismissed0');
    expect(chips().getByRole('button', { name: /^Resolved/ })).toHaveTextContent('Resolved250');
    await screen.findByText('Open finding');
    expect(screen.queryByText(/At most 200 findings per status/)).toBeNull();

    fireEvent.click(chips().getByRole('button', { name: /^Resolved/ }));

    expect(await screen.findByText('Resolved finding')).toBeInTheDocument();
    expect(await screen.findByText('At most 200 findings per status are loaded; the counts include all of them.')).toBeInTheDocument();
  });

  it('says when no finding has the chosen status', async () => {
    useFindings([finding('f-4', 'RESOLVED', 'Resolved finding')]);
    await openFindingsTab();

    expect(await screen.findByText('No findings with the selected status.')).toBeInTheDocument();
  });

  it('switches the chip of a deep-linked accepted finding on, so that it is visible', async () => {
    useFindings(all);
    renderWithProviders(<AuditCenter />, { route: '/?finding=f-3' });

    expect(await screen.findByText('Accepted finding')).toBeInTheDocument();
    expect(chips().getByRole('button', { name: /^Accepted/ })).toHaveAttribute('aria-pressed', 'true');
    expect(chips().getByRole('button', { name: /^Open/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText('Resolved finding')).toBeNull();
  });

  it('drops a filed finding from the list and switches its chip on from the toast', async () => {
    useFindings(all);
    renderWithProviders(<><AuditCenter /><ToastContainer /></>, { route: '/?finding=f-1' });

    const section = (await screen.findByText('Open finding')).closest('section')!;
    fireEvent.change(within(section).getByTestId('finding-status'), { target: { value: 'ACCEPTED_RISK' } });
    fireEvent.change(within(section).getByRole('textbox'), { target: { value: 'Tragbar bis zum Split' } });
    fireEvent.click(within(section).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(screen.queryByText('Open finding')).toBeNull());
    expect(screen.getByText('Awaiting finding')).toBeInTheDocument();
    expect(await screen.findByText('Filed: now under “Accepted”')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Show' }));

    expect(chips().getByRole('button', { name: /^Accepted/ })).toHaveAttribute('aria-pressed', 'true');
    const moved = (await screen.findByText('Open finding')).closest('section')!;
    expect(within(moved).getByTestId('finding-status')).toHaveValue('ACCEPTED_RISK');
  });

  it('keeps quiet when the new status stays in view', async () => {
    useFindings(all);
    renderWithProviders(<><AuditCenter /><ToastContainer /></>, { route: '/?finding=f-1' });

    const section = (await screen.findByText('Open finding')).closest('section')!;
    fireEvent.change(within(section).getByTestId('finding-status'), { target: { value: 'AWAITING_VERIFICATION' } });

    await waitFor(() => expect(within(section).getByTestId('finding-status')).toHaveValue('AWAITING_VERIFICATION'));
    expect(screen.getByText('Open finding')).toBeInTheDocument();
    expect(useToastStore.getState().toasts).toEqual([]);
  });
});
