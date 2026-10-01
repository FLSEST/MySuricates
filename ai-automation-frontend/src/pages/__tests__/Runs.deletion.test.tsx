import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import type { WorkflowRunListItem } from '../../types';
import { Runs } from '../Runs';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const run: WorkflowRunListItem = {
  id: 'run-1',
  workflowId: 'wf-1',
  workflowName: 'Nightly report',
  projectName: null,
  projectPath: null,
  status: 'COMPLETED',
  startedAt: new Date().toISOString(),
  completedAt: new Date().toISOString(),
  durationMs: 1200,
  userRating: null,
  error: null,
  metadata: {},
  stepCount: 1,
  lastStepStatus: 'COMPLETED',
  lastStepPreview: 'done',
};

function serve(runDeletionAllowed: boolean | undefined) {
  server.use(
    http.get('/api/v1/system/capabilities', () => HttpResponse.json({
      routes: [],
      blockTypes: [],
      ...modulesFixture('full', []),
      ...(runDeletionAllowed === undefined ? {} : { runDeletionAllowed }),
    })),
    http.get('/api/v1/executions', () => HttpResponse.json({
      content: [run], page: 0, size: 50, totalElements: 1, totalPages: 1,
    })),
    http.get('/api/v1/workflows', () => HttpResponse.json([])),
    http.get('/api/v1/projects', () => HttpResponse.json([])),
    http.get('/api/v1/chains/runs', () => HttpResponse.json({
      content: [], page: 0, size: 50, totalElements: 0, totalPages: 0,
    })),
  );
}

describe('Runs page — run deletion switch (automation.executions.allow-delete)', () => {
  it('shows the delete button when the instance allows deleting runs', async () => {
    serve(true);
    renderWithProviders(<Runs />);

    expect(await screen.findByText('Nightly report')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Delete run' })).toBeInTheDocument();
  });

  it('hides the delete button when the instance forbids deleting runs', async () => {
    serve(false);
    renderWithProviders(<Runs />);

    expect(await screen.findByText('Nightly report')).toBeInTheDocument();
    // Row rendered first; the button must be gone once the capabilities have arrived
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Delete run' })).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Delete run' })).not.toBeInTheDocument();
  });

  // 23635ef6: the app's own dialog instead of window.confirm; it sits in the table row, so its
  // clicks must not open the run
  it('asks in a dialog before deleting and stays on the list while it is answered', async () => {
    serve(true);
    const deleted: string[] = [];
    server.use(http.delete('/api/v1/executions/:id', ({ params }) => {
      deleted.push(String(params.id));
      return new HttpResponse(null, { status: 204 });
    }));
    renderWithProviders(
      <Routes>
        <Route path="/" element={<Runs />} />
        <Route path="/workflows/:id/run/:runId" element={<p>run page</p>} />
      </Routes>,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Delete run' }));
    const dialog = screen.getByRole('dialog', { name: 'Delete run' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('run page')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Delete run' }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Delete run' })).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(deleted).toEqual(['run-1']));
    expect(screen.queryByText('run page')).not.toBeInTheDocument();
  });

  it('keeps the button for an older backend that does not report the switch', async () => {
    serve(undefined);
    renderWithProviders(<Runs />);

    expect(await screen.findByRole('button', { name: 'Delete run' })).toBeInTheDocument();
  });
});
