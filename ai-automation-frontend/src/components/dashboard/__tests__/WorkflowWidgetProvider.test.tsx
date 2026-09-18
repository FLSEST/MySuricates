import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import type { ResolvedWidget } from '../../../hooks/useDashboardLayout';
import type { DashboardWidgetDef } from '../widgetDefinition';
import { WORKFLOW_STATUS_WIDGET_ID } from '../workflowWidgetContract';
import { WorkflowWidgetProvider } from '../WorkflowWidgetProvider';
import { useWorkflowWidgetEntry } from '../workflowWidgetContext';

let socketEnabled = false;
let deliver: ((message: { type: 'run_completed'; runId: string; workflowId: string; status: string }) => void) | null = null;
const connected = { value: true };

vi.mock('../../../hooks/useRunCompletedSocket', () => ({
  useRunCompletedSocket: (enabled: boolean, onRunCompleted: (message: never) => void) => {
    socketEnabled = enabled;
    deliver = onRunCompleted as typeof deliver;
    return connected.value;
  },
}));

const widget = (workflowId?: string): ResolvedWidget => ({
  def: { id: WORKFLOW_STATUS_WIDGET_ID } as DashboardWidgetDef,
  instance: { instanceId: `${WORKFLOW_STATUS_WIDGET_ID}-1`, type: WORKFLOW_STATUS_WIDGET_ID, config: workflowId ? { workflowId } : undefined },
});

function Probe({ workflowId }: { workflowId?: string }) {
  const { entry, isLoading } = useWorkflowWidgetEntry(workflowId);
  return <p>{isLoading ? 'loading' : entry ? `${entry.workflowName} / ${entry.resultState}` : 'none'}</p>;
}

const entry = (name: string) => ({
  workflowId: 'wf-a1', workflowName: name, available: true,
  run: { runId: 'run-1', status: 'COMPLETED', startedAt: '2026-09-18T08:00:00Z', completedAt: null, durationMs: 1200, gateStatus: 'GREEN' },
  resultState: 'MISSING', result: null,
});

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => { server.resetHandlers(); connected.value = true; });
afterAll(() => server.close());

describe('WorkflowWidgetProvider', () => {
  it('loads the ids of the shown workflow widgets and hands each widget its entry', async () => {
    server.use(http.get('/api/v1/dashboard/workflow-results', () => HttpResponse.json([entry('Nightly QA')])));

    renderWithProviders(
      <WorkflowWidgetProvider widgets={[widget('wf-a1')]}><Probe workflowId="wf-a1" /></WorkflowWidgetProvider>,
    );

    expect(await screen.findByText('Nightly QA / MISSING')).toBeInTheDocument();
    expect(socketEnabled).toBe(true);
  });

  it('asks nothing and opens no socket without workflow widgets', async () => {
    let called = 0;
    server.use(http.get('/api/v1/dashboard/workflow-results', () => { called += 1; return HttpResponse.json([]); }));

    renderWithProviders(<WorkflowWidgetProvider widgets={[]}><Probe /></WorkflowWidgetProvider>);

    expect(await screen.findByText('none')).toBeInTheDocument();
    expect(called).toBe(0);
    expect(socketEnabled).toBe(false);
  });

  it('reloads when a run of a shown workflow ends, and ignores other workflows', async () => {
    let served = 0;
    server.use(http.get('/api/v1/dashboard/workflow-results', () => {
      served += 1;
      return HttpResponse.json([entry(served === 1 ? 'Nightly QA' : 'Nightly QA (new)')]);
    }));

    renderWithProviders(
      <WorkflowWidgetProvider widgets={[widget('wf-a1')]}><Probe workflowId="wf-a1" /></WorkflowWidgetProvider>,
    );
    await screen.findByText('Nightly QA / MISSING');

    deliver?.({ type: 'run_completed', runId: 'run-2', workflowId: 'wf-other', status: 'COMPLETED' });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(served).toBe(1);

    deliver?.({ type: 'run_completed', runId: 'run-2', workflowId: 'wf-a1', status: 'COMPLETED' });
    await waitFor(() => expect(screen.getByText('Nightly QA (new) / MISSING')).toBeInTheDocument());
  });

  it('refuses to be used outside the provider', () => {
    expect(() => renderWithProviders(<Probe workflowId="wf-a1" />)).toThrow(/WorkflowWidgetProvider/);
  });
});
