import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import type { WorkflowRun } from '../../types';
import { WorkflowExecution } from '../WorkflowExecution';

vi.mock('../../hooks/useWebSocket', () => ({
  useExecutionWebSocket: () => ({
    logs: [], connected: false, pendingApproval: null, pendingHumanInput: null,
    agenticStates: new Map(), forEachStates: new Map(),
  }),
}));

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

function serve(userRating: number | null) {
  const run: WorkflowRun = {
    id: 'run-1', workflowId: 'wf-1', workflowName: 'Deploy', projectName: null, projectPath: null, status: 'COMPLETED',
    steps: [], startedAt: '2026-09-24T08:00:00Z', completedAt: '2026-09-24T08:01:00Z', durationMs: 60_000,
    userRating, error: null, metadata: {},
  };
  server.use(
    http.get('/api/v1/workflows/:id', () => HttpResponse.json({ id: 'wf-1', name: 'Deploy', blocks: [] })),
    http.get('/api/v1/workflows/:id/runs/:runId', () => HttpResponse.json(run)),
    http.get('/api/v1/executions/:runId/approval', () => HttpResponse.json({ pending: false })),
  );
}

function renderPage() {
  renderWithProviders(
    <Routes>
      <Route path="/workflows/:id/run/:runId" element={<WorkflowExecution />} />
    </Routes>,
    { route: '/workflows/wf-1/run/run-1' },
  );
}

describe('WorkflowExecution rating', () => {
  // c4a69992: the stars were the text glyph U+2605, which the default UI font lacks - without a
  // fallback font the browser drew empty boxes. They are icons now, independent of any font.
  it('draws the stars as icons, not as a text glyph', async () => {
    serve(null);
    renderPage();

    const stars = await screen.findAllByRole('button', { name: /^[1-5]\/5$/ });
    expect(stars).toHaveLength(5);
    for (const star of stars) {
      expect(star.querySelector('svg')).not.toBeNull();
      expect(star.textContent).not.toContain('★');
    }
  });

  it('fills exactly the stars up to the rating', async () => {
    serve(3);
    renderPage();

    await screen.findAllByRole('button', { name: /^[1-5]\/5$/ });
    const filled = [1, 2, 3, 4, 5].map((n) =>
      screen.getByRole('button', { name: `${n}/5` }).getAttribute('aria-pressed'));
    expect(filled).toEqual(['true', 'true', 'true', 'false', 'false']);
    expect(screen.getByRole('button', { name: '3/5' }).querySelector('svg')).toHaveAttribute('fill', 'currentColor');
    expect(screen.getByRole('button', { name: '4/5' }).querySelector('svg')).toHaveAttribute('fill', 'none');
  });
});
