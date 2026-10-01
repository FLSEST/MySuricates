import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { useState } from 'react';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { RECENT_RUNS_PAGE_SIZE, WorkflowRecentRuns } from '../WorkflowRecentRuns';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

function run(id: string, extra: Record<string, unknown> = {}) {
  return {
    id, workflowId: 'wf-1', workflowName: 'Deploy', projectName: null, projectPath: null, status: 'COMPLETED',
    startedAt: '2026-09-14T10:00:00Z', completedAt: null, durationMs: 2500, userRating: null, error: null,
    metadata: {}, stepCount: 1, lastStepStatus: null, lastStepPreview: null, ...extra,
  };
}

/** Serves two pages of runs and records which page/size was asked for. */
function serveRuns(requests: string[], totalPages = 2) {
  server.use(http.get('/api/v1/workflows/:id/runs', ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page'));
    requests.push(`${url.searchParams.get('page')}/${url.searchParams.get('size')}`);
    const content = page === 0
      ? [run('r1', { metadata: { notes: 'x'.repeat(90), gate_status: 'RED' } }), run('r2', { durationMs: null })]
      : [run('r3', { status: 'FAILED' })];
    return HttpResponse.json({ content, page, size: RECENT_RUNS_PAGE_SIZE, totalElements: 7, totalPages });
  }));
}

function PagedCard({ onOpenRun }: { onOpenRun: (id: string) => void }) {
  const [page, setPage] = useState(0);
  return <WorkflowRecentRuns variant="card" workflowId="wf-1" page={page} onPageChange={setPage} onOpenRun={onOpenRun} />;
}

describe('WorkflowRecentRuns', () => {
  it('list: shows the newest runs with duration and opens a run', async () => {
    const requests: string[] = [];
    serveRuns(requests);
    const onOpenRun = vi.fn();
    renderWithProviders(<WorkflowRecentRuns variant="list" workflowId="wf-1" onOpenRun={onOpenRun} />);

    expect(screen.getByRole('heading', { name: 'Recent Runs' })).toBeInTheDocument();
    const rows = await screen.findAllByRole('button');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('2.5s');
    // No duration → no duration label; the list has neither notes nor paging
    expect(rows[1]!.textContent).not.toMatch(/\d(\.\d+)?s/);
    expect(screen.queryByText(/x{80}/)).not.toBeInTheDocument();
    expect(requests).toEqual(['0/5']);

    fireEvent.click(rows[1]!);
    expect(onOpenRun).toHaveBeenCalledWith('r2');
  });

  it('list: says so when there are no runs', async () => {
    server.use(http.get('/api/v1/workflows/:id/runs', () =>
      HttpResponse.json({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 })));
    renderWithProviders(<WorkflowRecentRuns variant="list" workflowId="wf-1" onOpenRun={vi.fn()} />);
    expect(await screen.findByText('No runs yet')).toBeInTheDocument();
  });

  it('card: shows the total, gate status and shortened notes, and pages through the runs', async () => {
    const requests: string[] = [];
    serveRuns(requests);
    const onOpenRun = vi.fn();
    renderWithProviders(<PagedCard onOpenRun={onOpenRun} />);

    expect(await screen.findByText('Recent Runs (7)')).toBeInTheDocument();
    expect(await screen.findByText(`${'x'.repeat(80)}...`)).toBeInTheDocument();
    expect(screen.getByText('Critical')).toBeInTheDocument();

    fireEvent.click(screen.getByText(`${'x'.repeat(80)}...`));
    expect(onOpenRun).toHaveBeenCalledWith('r1');

    fireEvent.click(screen.getByRole('button', { name: '→' }));
    await waitFor(() => expect(screen.getByText('2 / 2')).toBeInTheDocument());
    await waitFor(() => expect(requests).toContain('1/5'));
    expect(requests[0]).toBe('0/5');
  });

  it('card: has no paging for a single page and says so without runs', async () => {
    serveRuns([], 1);
    const { unmount } = renderWithProviders(<PagedCard onOpenRun={vi.fn()} />);
    await screen.findByText('Recent Runs (7)');
    expect(screen.queryByRole('button', { name: '→' })).not.toBeInTheDocument();
    unmount();

    server.use(http.get('/api/v1/workflows/:id/runs', () =>
      HttpResponse.json({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 })));
    renderWithProviders(<PagedCard onOpenRun={vi.fn()} />);
    expect(await screen.findByText('No runs yet')).toBeInTheDocument();
    expect(screen.getByText('Recent Runs (0)')).toBeInTheDocument();
  });
});
