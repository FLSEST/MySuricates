import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, within, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import type { ChainRun } from '../../types';
import { Runs } from '../Runs';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'de' });
});

const chainRun: ChainRun = {
  id: 'cr-1', chainId: 'c-1', chainName: 'Release', status: 'FAILED', inputContext: {}, metadata: {},
  startedAt: '2026-09-14T10:00:00Z', completedAt: null, durationMs: 4000, error: null,
  entryRuns: [
    { entryIndex: 0, workflowId: 'wf-1', workflowName: 'Build', projectName: null, workflowRunId: 'r1', status: 'COMPLETED', startedAt: null, completedAt: null, durationMs: null },
    { entryIndex: 1, workflowId: 'wf-2', workflowName: 'Deploy', projectName: null, workflowRunId: 'r2', status: 'FAILED', startedAt: null, completedAt: null, durationMs: null },
  ],
};

const workflowRun = {
  id: 'run-1', workflowId: 'wf-1', workflowName: 'Nightly', projectName: null, projectPath: null, status: 'COMPLETED',
  startedAt: '2026-09-14T10:00:00Z', completedAt: null, durationMs: 1000, userRating: null, error: null, metadata: {},
  stepCount: 1, lastStepStatus: 'COMPLETED', lastStepPreview: 'done',
};

function serve(executionQueries: string[] = [], totalPages = 0) {
  server.use(
    http.get('/api/v1/system/capabilities', () => HttpResponse.json({ routes: [], blockTypes: [], ...modulesFixture('full', []) })),
    http.get('/api/v1/executions', ({ request }) => {
      executionQueries.push(new URL(request.url).search);
      const content = totalPages > 0 ? [workflowRun] : [];
      return HttpResponse.json({ content, page: 0, size: 50, totalElements: totalPages * 50, totalPages });
    }),
    http.get('/api/v1/workflows', () => HttpResponse.json([])),
    http.get('/api/v1/projects', () => HttpResponse.json([])),
    http.get('/api/v1/chains/runs', () => HttpResponse.json({ content: [chainRun], page: 0, size: 50, totalElements: 1, totalPages: 1 })),
  );
}

describe('Runs page — filters in the app language', () => {
  it('names the statuses of the filter in German', async () => {
    serve();
    renderWithProviders(<Runs />);

    const statusSelect = await screen.findByDisplayValue('Alle Status');
    const labels = within(statusSelect).getAllByRole('option').map((o) => o.textContent);
    expect(labels).toEqual([
      'Alle Status', 'Abgeschlossen', 'Mit Fehlern abgeschlossen', 'Fehlgeschlagen', 'Läuft', 'Abgebrochen', 'Pausiert', 'Wartend', 'Unterbrochen',
    ]);
  });

  it('filters by status and shows the chain progress with the failed count in German', async () => {
    const queries: string[] = [];
    serve(queries);
    renderWithProviders(<Runs />);

    fireEvent.change(await screen.findByDisplayValue('Alle Status'), { target: { value: 'FAILED' } });
    await waitFor(() => expect(queries.some((q) => q.includes('status=FAILED'))).toBe(true));

    fireEvent.click(screen.getByRole('button', { name: 'Ketten' }));
    expect(await screen.findByText('(1 fehlgeschlagen)')).toBeInTheDocument();
    expect(screen.getByText('Release')).toBeInTheDocument();
    // Leaving the workflows tab clears its filters, also the remembered ones
    expect(localStorage.getItem('runs-status-filter')).toBe('""');
  });

  it('names what started a chain run when the backend sends it (V96), and nothing for the old shape', async () => {
    serve();
    server.use(http.get('/api/v1/chains/runs', () => HttpResponse.json({
      content: [{ ...chainRun, triggerSource: 'WISH' }, { ...chainRun, id: 'cr-2', chainName: 'Alt' }],
      page: 0, size: 50, totalElements: 2, totalPages: 1,
    })));
    renderWithProviders(<Runs />);

    fireEvent.click(await screen.findByRole('button', { name: 'Ketten' }));
    expect(await screen.findByText('Alt')).toBeInTheDocument();
    const sources = screen.getAllByTestId('chain-trigger-source');
    expect(sources).toHaveLength(1);
    expect(sources[0].textContent).toMatch(/^Gestartet durch /);
  });

  it('names the search field of both tabs by its label and sends what is typed', async () => {
    const queries: string[] = [];
    const chainQueries: string[] = [];
    serve(queries);
    server.use(http.get('/api/v1/chains/runs', ({ request }) => {
      chainQueries.push(new URL(request.url).search);
      return HttpResponse.json({ content: [chainRun], page: 0, size: 50, totalElements: 1, totalPages: 1 });
    }));
    renderWithProviders(<Runs />);

    fireEvent.change(await screen.findByLabelText('Suchen...'), { target: { value: 'Nightly' } });
    await waitFor(() => expect(queries.some((q) => q.includes('search=Nightly'))).toBe(true));

    fireEvent.click(screen.getByRole('button', { name: 'Ketten' }));
    fireEvent.change(await screen.findByLabelText('Suchen...'), { target: { value: 'Release' } });
    await waitFor(() => expect(chainQueries.some((q) => q.includes('search=Release'))).toBe(true));
  });

  it('goes back to the first page when a filter changes', async () => {
    const queries: string[] = [];
    serve(queries, 3);
    renderWithProviders(<Runs />);

    const next = await screen.findByRole('button', { name: '→' });
    // Past the first wait of the search, so nothing but the filter can reset the page
    await new Promise((r) => setTimeout(r, 350));
    fireEvent.click(next);
    await waitFor(() => expect(queries.at(-1)).toContain('page=1'));
    fireEvent.change(screen.getByDisplayValue('Alle Status'), { target: { value: 'FAILED' } });
    await waitFor(() => expect(queries.at(-1)).toContain('status=FAILED'));
    expect(queries.at(-1)).toContain('page=0');
  });
});

