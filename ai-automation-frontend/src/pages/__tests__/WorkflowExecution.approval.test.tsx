import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { act, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import type { PendingApproval } from '../../hooks/useWebSocket';
import type { StepResult, WorkflowRun } from '../../types';
import { WorkflowExecution } from '../WorkflowExecution';

// A socket the test drives: when it connects and which approval its events opened.
const socket = vi.hoisted(() => {
  let state: { connected: boolean; pendingApproval: PendingApproval | null } = { connected: false, pendingApproval: null };
  const listeners = new Set<() => void>();
  return {
    snapshot: () => state,
    subscribe: (listener: () => void) => {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
    set: (next: Partial<typeof state>) => {
      state = { ...state, ...next };
      listeners.forEach((listener) => listener());
    },
    reset: () => { state = { connected: false, pendingApproval: null }; },
  };
});

vi.mock('../../hooks/useWebSocket', async () => {
  const { useSyncExternalStore } = await import('react');
  return {
    useExecutionWebSocket: () => ({
      logs: [], pendingHumanInput: null, agenticStates: new Map(), forEachStates: new Map(),
      ...useSyncExternalStore(socket.subscribe, socket.snapshot),
    }),
  };
});

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  socket.reset();
});

const APPROVAL: PendingApproval = { blockId: 'approval-1', question: 'Deploy to production?', options: ['Yes', 'No'], rejectOptions: ['No'], stepIndex: 1 };

function step(stepIndex: number, status: StepResult['status']): StepResult {
  return { id: `s${stepIndex}`, stepIndex, blockType: 'shell', input: '', output: '', status, durationMs: 44, error: null };
}

const RUNNING_RUN: WorkflowRun = {
  id: 'run-1', workflowId: 'wf-1', workflowName: 'Approval before acting', projectName: null, projectPath: null,
  status: 'RUNNING', steps: [step(0, 'COMPLETED')], startedAt: '2026-09-25T08:00:00Z', completedAt: null,
  durationMs: null, userRating: null, error: null, metadata: {},
};

/** Serves the page; the approval endpoint answers with the approval only while `endpoint.open` is set. */
function serve() {
  const endpoint = { open: false, calls: 0, answers: [] as unknown[] };
  server.use(
    http.get('/api/v1/workflows/:id', () => HttpResponse.json({ id: 'wf-1', name: 'Approval before acting', blocks: [] })),
    http.get('/api/v1/workflows/:id/runs/:runId', () => HttpResponse.json(RUNNING_RUN)),
    http.get('/api/v1/executions/:runId/approval', () => {
      endpoint.calls++;
      return HttpResponse.json(endpoint.open ? { pending: true, ...APPROVAL } : { pending: false });
    }),
    http.post('/api/v1/executions/:runId/approve', async ({ request }) => {
      endpoint.answers.push(await request.json());
      endpoint.open = false;
      return new HttpResponse(null, { status: 200 });
    }),
  );
  return endpoint;
}

function renderPage() {
  return renderWithProviders(
    <Routes><Route path="/workflows/:id/run/:runId" element={<WorkflowExecution />} /></Routes>,
    { route: '/workflows/wf-1/run/run-1' },
  );
}

describe('WorkflowExecution approval dialog', () => {
  it('shows an approval that began after the first query but before the socket connected', async () => {
    const endpoint = serve();
    renderPage();
    expect(await screen.findByText('Steps (1)')).toBeInTheDocument();
    await waitFor(() => expect(endpoint.calls).toBeGreaterThan(0));

    // The approval starts now: its socket event is gone (nobody subscribed yet), the first query said "nothing".
    endpoint.open = true;
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    act(() => socket.set({ connected: true }));

    expect(await screen.findByRole('dialog')).toHaveTextContent('Deploy to production?');
  });

  it('closes the dialog as soon as the answer went through, before the socket reports it resolved', async () => {
    const endpoint = serve();
    endpoint.open = true;
    socket.set({ connected: true, pendingApproval: APPROVAL });
    renderPage();

    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Yes' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(endpoint.answers).toEqual([{ answer: 'Yes' }]);
    expect(socket.snapshot().pendingApproval).toEqual(APPROVAL); // no approval_resolved yet
    // answered is answered: nothing left to open again
    expect(screen.queryByRole('button', { name: 'Answer open approval' })).not.toBeInTheDocument();
  });

  it('closes the dialog with X without answering, and offers the question again — in the header and after a remount', async () => {
    const endpoint = serve();
    endpoint.open = true;
    socket.set({ connected: true, pendingApproval: APPROVAL });
    const { unmount } = renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(endpoint.answers).toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: 'Answer open approval' }));
    expect(await screen.findByRole('dialog')).toHaveTextContent('Deploy to production?');
    expect(screen.queryByRole('button', { name: 'Answer open approval' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    unmount();
    renderPage();

    expect(await screen.findByRole('dialog')).toHaveTextContent('Deploy to production?');
    expect(endpoint.answers).toEqual([]);
  });
});
