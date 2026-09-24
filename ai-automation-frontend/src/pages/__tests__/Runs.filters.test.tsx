import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, within, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import type { ChainRun } from '../../types';
import { Runs } from '../Runs';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
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

function serve(executionQueries: string[] = []) {
  server.use(
    http.get('/api/v1/system/capabilities', () => HttpResponse.json({ routes: [], blockTypes: [], ...modulesFixture('full', []) })),
    http.get('/api/v1/executions', ({ request }) => {
      executionQueries.push(new URL(request.url).search);
      return HttpResponse.json({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
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
      'Alle Status', 'Abgeschlossen', 'Abgeschlossen (alt)', 'Fehlgeschlagen', 'Läuft', 'Abgebrochen', 'Pausiert', 'Wartend', 'Unterbrochen',
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
});
