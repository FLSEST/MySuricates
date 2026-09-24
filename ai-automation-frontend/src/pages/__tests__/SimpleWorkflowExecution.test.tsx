import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen, fireEvent, within } from '@testing-library/react';
import { Routes, Route } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { useSettingsStore } from '../../stores/settingsStore';
import type { StepResult, WorkflowRun } from '../../types';
import { SimpleWorkflowExecution } from '../SimpleWorkflowExecution';

// No socket in jsdom: the page only needs the connection flag and the pending dialogs.
vi.mock('../../hooks/useWebSocket', () => ({
  useExecutionWebSocket: () => ({ connected: false, pendingApproval: null, pendingHumanInput: null }),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: true, hintsEnabled: true, dismissedHints: {} });
});

function step(stepIndex: number, overrides: Partial<StepResult> = {}): StepResult {
  return {
    id: `step-${stepIndex}`,
    stepIndex,
    blockType: 'simple',
    blockName: `Block ${stepIndex}`,
    input: '',
    output: `Output of block ${stepIndex}`,
    status: 'COMPLETED',
    durationMs: 1000,
    error: null,
    metadata: { blockIndex: stepIndex, provider: 'CLAUDE', model: 'opus', requestedModel: 'sonnet' },
    ...overrides,
  };
}

function baseRun(overrides: Partial<WorkflowRun>): WorkflowRun {
  return {
    id: 'run-1',
    workflowId: 'wf-1',
    workflowName: 'Weekly report',
    projectName: null,
    projectPath: null,
    status: 'COMPLETED',
    steps: [step(0), step(1)],
    startedAt: '2026-09-24T08:00:00Z',
    completedAt: '2026-09-24T08:01:00Z',
    durationMs: 60_000,
    userRating: null,
    error: null,
    metadata: {},
    ...overrides,
  };
}

function serve(run: WorkflowRun) {
  server.use(
    http.get('/api/v1/workflows/:id', () => HttpResponse.json({ id: 'wf-1', name: 'Weekly report', blocks: [] })),
    http.get('/api/v1/workflows/:id/runs/:runId', () => HttpResponse.json(run)),
    http.get('/api/v1/executions/:runId/approval', () => HttpResponse.json({ pending: false })),
  );
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/workflows/:id/run/:runId" element={<SimpleWorkflowExecution />} />
    </Routes>,
    { route: '/workflows/wf-1/run/run-1' },
  );
}

describe('SimpleWorkflowExecution', () => {
  it('shows the report with a translated status and keeps the steps collapsed', async () => {
    serve(baseRun({
      metadata: {
        dashboard_widget: {
          title: 'Sales this week', status: 'GREEN',
          kpis: [{ label: 'Orders', value: '42' }], items: ['Best day: Tuesday'], summary: 'A good week.',
        },
      },
    }));
    renderPage();

    expect(await screen.findByText('Sales this week')).toBeInTheDocument();
    expect(screen.getByText('All fine')).toBeInTheDocument();
    expect(screen.queryByText('GREEN')).not.toBeInTheDocument();
    expect(screen.getByText('Orders')).toBeInTheDocument();
    expect(screen.getByText('Best day: Tuesday')).toBeInTheDocument();

    // With a report, the last output sits behind "Full output"; the steps behind "Details".
    expect(screen.queryByText('Output of block 1')).not.toBeInTheDocument();
    expect(screen.queryByTestId('run-details')).not.toBeInTheDocument();
    const details = screen.getByRole('button', { name: /Details \(for the technically curious\)/ });
    expect(details).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(screen.getByRole('button', { name: 'Full output' }));
    expect(screen.getByTestId('run-result-output')).toHaveTextContent('Output of block 1');
  });

  it('shows the last output without a report, and never provider/model pills — not even in the details', async () => {
    serve(baseRun({
      steps: [step(0), step(1, { output: '## Summary\nAll **done**.\ngate_status: GREEN\nSaved to /tmp/out/report.md' })],
      metadata: { gate_status: 'GREEN', todo_items: [{ title: 'a' }, { title: 'b' }] },
    }));
    renderPage();

    const output = await screen.findByTestId('run-result-output');
    expect(within(output).getByRole('heading', { name: 'Summary' })).toBeInTheDocument();
    expect(output).not.toHaveTextContent('gate_status');
    expect(screen.getByRole('button', { name: 'Open: /tmp/out/report.md' })).toBeInTheDocument();
    expect(screen.getByText('2 tasks created')).toBeInTheDocument();
    expect(document.querySelector('[data-gate-status="GREEN"]')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /Details \(for the technically curious\)/ }));
    const details = screen.getByTestId('run-details');
    expect(within(details).getByText('Output of block 0')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/provider=|model=|requested=/);
  });

  it('turns an error object into plain text with the failed step and a way to Pro', async () => {
    serve(baseRun({
      status: 'FAILED',
      steps: [step(0), step(1, { status: 'FAILED', output: '', error: 'exit code 2: file not found', blockName: 'Fetch data' })],
      error: { type: 'EXECUTION', stepIndex: 1, details: { exitCode: 2 } },
    }));
    renderPage();

    expect(await screen.findByText('The run failed')).toBeInTheDocument();
    expect(screen.getByTestId('run-failure-reason')).toHaveTextContent('exit code 2: file not found');
    expect(screen.getByText('At step ‘Fetch data’')).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('"exitCode"');
    expect(screen.getByRole('button', { name: 'Investigate in Pro mode' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Investigate in Pro mode' }));
    expect(useSettingsStore.getState().simpleMode).toBe(false);
  });

  it('shows only a progress list while the run is going', async () => {
    serve(baseRun({
      status: 'RUNNING',
      completedAt: null,
      steps: [step(0), step(1, { status: 'RUNNING', output: 'partial output' })],
    }));
    renderPage();

    const progress = await screen.findByTestId('run-progress');
    expect(within(progress).getByText('Block 0')).toBeInTheDocument();
    expect(within(progress).getByText('Block 1')).toBeInTheDocument();
    expect(screen.queryByText('Output of block 0')).not.toBeInTheDocument();
    expect(screen.queryByText('partial output')).not.toBeInTheDocument();
    expect(screen.queryByTestId('run-result')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Details/ })).not.toBeInTheDocument();
  });

  it('says so when a report was sent but did not match the contract', async () => {
    serve(baseRun({ metadata: { dashboard_widget_error: 'INVALID' } }));
    renderPage();

    expect(await screen.findByText('The run tried to deliver a report, but it could not be read.')).toBeInTheDocument();
    expect(screen.getByTestId('run-result-output')).toHaveTextContent('Output of block 1');
  });

  it('says a cancelled run was cancelled', async () => {
    serve(baseRun({ status: 'CANCELLED' }));
    renderPage();

    expect(await screen.findByText('The run was cancelled before it finished.')).toBeInTheDocument();
    expect(screen.queryByTestId('run-result')).not.toBeInTheDocument();
    expect(screen.queryByTestId('run-failure')).not.toBeInTheDocument();
  });
});
