import { describe, it, expect, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { setupDashboardPageTests, slot } from '../../test/dashboardPage';
import { useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { Dashboard } from '../Dashboard';

vi.mock('../../hooks/useRunCompletedSocket', () => ({ useRunCompletedSocket: () => true }));

setupDashboardPageTests();

const RESULT = {
  workflowId: 'w1', workflowName: 'Test Workflow', available: true,
  run: { runId: 'run-1', status: 'COMPLETED', startedAt: '2026-09-18T08:00:00Z', completedAt: '2026-09-18T08:02:00Z', durationMs: 120_000, gateStatus: 'GREEN' },
  resultState: 'VALID',
  result: { title: 'Nightly report', status: 'GREEN', kpis: [{ label: 'Orders', value: '1361' }], items: ['<script>alert(1)</script>'], summary: 'All good.' },
};

// Renders the whole page; see Dashboard.test.tsx for the timeout.
describe('Dashboard workflow widgets', { timeout: 20_000 }, () => {
  it('adds a workflow widget from the catalog and shows its result as text', async () => {
    const requests: string[] = [];
    server.use(http.get('/api/v1/dashboard/workflow-results', ({ request }) => {
      requests.push(new URL(request.url).search);
      return HttpResponse.json([RESULT]);
    }));

    const { container } = renderWithProviders(<Dashboard />);
    expect(await screen.findByText('Recent Runs')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add widget' }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('heading', { level: 3, name: 'Workflows' })).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add: Workflow result' }));
    const select = await within(dialog).findByRole('combobox', { name: 'Workflow' });
    // The combobox exists (disabled) before the workflow list has loaded — await the option itself,
    // or the change event below fires before jsdom's <select> has an option to select "w1" onto.
    await within(select).findByRole('option', { name: 'Test Workflow' });
    fireEvent.change(select, { target: { value: 'w1' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));

    expect(await screen.findByText('Nightly report')).toBeInTheDocument();
    expect(screen.getByText('<script>alert(1)</script>')).toBeInTheDocument();
    expect(container.querySelector('script')).toBeNull();
    await waitFor(() => expect(requests).toEqual(['?workflowIds=w1']));
  });

  it('asks once for two widgets on the same workflow and shows the neutral state without a contract', async () => {
    let requests = 0;
    server.use(http.get('/api/v1/dashboard/workflow-results', () => {
      requests += 1;
      return HttpResponse.json([{ ...RESULT, resultState: 'INVALID', result: null }]);
    }));
    useDashboardLayoutStore.setState({
      activeViewId: 'default',
      views: [{
        id: 'default', name: '', widgets: [
          { instanceId: 'workflow-status-1', type: 'workflow-status', config: { workflowId: 'w1' } },
          { instanceId: 'workflow-result-1', type: 'workflow-result', config: { workflowId: 'w1' } },
        ],
      }],
    });

    renderWithProviders(<Dashboard />);

    expect(await screen.findByText('No valid dashboard result')).toBeInTheDocument();
    expect(within(slot('workflow-status-1')!).getByText('Test Workflow')).toBeInTheDocument();
    expect(requests).toBe(1);
  });
});
