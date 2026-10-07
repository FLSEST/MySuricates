import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { WorkflowWidgetContext, type WorkflowWidgetValue } from '../workflowWidgetContext';
import type { WorkflowWidgetEntry } from '../workflowWidgetContract';
import { WorkflowStatusWidget } from '../WorkflowStatusWidget';

const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => navigate,
}));

const ENTRY: WorkflowWidgetEntry = {
  workflowId: 'wf-a1',
  workflowName: 'Nightly QA',
  available: true,
  run: {
    runId: 'run-1', status: 'COMPLETED', startedAt: '2026-09-18T08:00:00Z',
    completedAt: '2026-09-18T08:02:00Z', durationMs: 120_000, gateStatus: 'YELLOW',
  },
  resultState: 'VALID',
  result: { status: 'GREEN', title: 'never shown by widget A', kpis: [], items: ['never shown'], summary: 'never shown' },
};

function render(entry: WorkflowWidgetEntry | null, extra: Partial<WorkflowWidgetValue> = {}, config: Record<string, string> = { workflowId: 'wf-a1' }) {
  const value: WorkflowWidgetValue = {
    entries: new Map(entry ? [[entry.workflowId, entry]] : []),
    isLoading: false,
    error: null,
    ...extra,
  };
  return renderWithProviders(
    <WorkflowWidgetContext.Provider value={value}>
      <WorkflowStatusWidget instanceId="workflow-status-1" config={config} />
    </WorkflowWidgetContext.Provider>,
  );
}

beforeEach(() => {
  navigate.mockClear();
  useI18nStore.setState({ locale: 'en' });
});

describe('WorkflowStatusWidget', () => {
  it('shows name, status, time, duration and gate of the latest run', () => {
    render(ENTRY);

    expect(screen.getByText('Nightly QA')).toBeInTheDocument();
    // StatusBadge prints the translated status label (runs.statuses).
    expect(screen.getByText('Completed')).toBeInTheDocument();
    expect(screen.getByText('2.0m')).toBeInTheDocument();
    expect(screen.getByText('YELLOW')).toBeInTheDocument();
  });

  it('never shows anything from the contract', () => {
    render(ENTRY);

    expect(screen.queryByText(/never shown/)).not.toBeInTheDocument();
  });

  it('opens the run without ever rendering a link', () => {
    const { container } = render(ENTRY);

    fireEvent.click(screen.getByRole('button', { name: 'Open run: Nightly QA' }));

    expect(navigate).toHaveBeenCalledWith('/workflows/wf-a1/run/run-1');
    expect(container.querySelector('a')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
  });

  it('marks a failed latest run', () => {
    render({ ...ENTRY, run: { ...ENTRY.run!, status: 'FAILED' } });

    expect(screen.getByText('The latest run failed.')).toBeInTheDocument();
  });

  it.each([
    ['without a configured workflow', {} as Record<string, string>, null, 'No workflow selected.'],
    ['for a deleted workflow', { workflowId: 'wf-a1' }, { ...ENTRY, available: false, workflowName: null, run: null }, 'This workflow no longer exists.'],
    ['without a run', { workflowId: 'wf-a1' }, { ...ENTRY, run: null }, 'No run yet.'],
  ])('shows its own state %s', (_name, config, entry, message) => {
    render(entry as WorkflowWidgetEntry | null, {}, config);

    expect(screen.getByText(message)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Open run/ })).not.toBeInTheDocument();
  });

  it('shows a skeleton while loading and a message when loading failed', () => {
    const { unmount } = render(null, { isLoading: true });
    expect(screen.getByTestId('widget-skeleton')).toBeInTheDocument();
    unmount();

    render(null, { error: new Error('offline') });
    expect(screen.getByText('Could not load the workflow data.')).toBeInTheDocument();
  });
});
