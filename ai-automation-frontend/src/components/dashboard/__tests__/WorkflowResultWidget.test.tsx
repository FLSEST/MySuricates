import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { WorkflowWidgetContext, type WorkflowWidgetValue } from '../workflowWidgetContext';
import type { WorkflowWidgetEntry } from '../workflowWidgetContract';
import { WorkflowResultWidget } from '../WorkflowResultWidget';

const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => navigate,
}));

// Text that looks like markup, links, images and entities. It must appear literally — and must not
// turn into an element. The guard file itself excludes __tests__, so these strings may live here.
const HOSTILE = [
  '<script>alert(1)</script>',
  '<img src=x onerror=alert(1)>',
  '[klick](https://example.invalid)',
  '![pwn](https://example.invalid/p.png)',
  'https://example.invalid/?key=abc',
  '&lt;b&gt;fett&lt;/b&gt;',
  '**fett** `code`',
];

const ENTRY: WorkflowWidgetEntry = {
  workflowId: 'wf-a1',
  workflowName: 'Nightly QA',
  available: true,
  run: {
    runId: 'run-1', status: 'COMPLETED', startedAt: '2026-09-18T08:00:00Z',
    completedAt: '2026-09-18T08:02:00Z', durationMs: 120_000, gateStatus: 'GREEN',
  },
  resultState: 'VALID',
  result: {
    title: 'Nightly QA report',
    status: 'YELLOW',
    kpis: [{ label: 'Orders', value: '1361', trend: 'UP' }, { label: 'Fehler', value: '12' }],
    items: ['3 Bestellungen ohne Versanddatum'],
    summary: 'Zwei Befunde, keiner blockierend.',
  },
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
      <WorkflowResultWidget instanceId="workflow-result-1" config={config} />
    </WorkflowWidgetContext.Provider>,
  );
}

beforeEach(() => {
  navigate.mockClear();
  useI18nStore.setState({ locale: 'en' });
});

describe('WorkflowResultWidget', () => {
  it('shows title, status, kpis, items and summary of the contract', () => {
    render(ENTRY);

    expect(screen.getByText('Nightly QA report')).toBeInTheDocument();
    expect(screen.getByText('Orders')).toBeInTheDocument();
    expect(screen.getByText('1361')).toBeInTheDocument();
    expect(screen.getByText('3 Bestellungen ohne Versanddatum')).toBeInTheDocument();
    expect(screen.getByText('Zwei Befunde, keiner blockierend.')).toBeInTheDocument();
    expect(screen.getByLabelText('rising')).toBeInTheDocument();
  });

  /**
   * The same contract arrives from an AI step's marker and from a run's own deterministic
   * `dashboard-report` block, and the response does not say which. So the line may claim only
   * provenance — never that an AI wrote it.
   */
  it('labels the result by where it came from, not by who wrote it', () => {
    render(ENTRY);

    expect(screen.getByText('Reported by the run')).toBeInTheDocument();
    expect(screen.queryByText(/AI-generated/i)).not.toBeInTheDocument();
  });

  it('renders hostile-looking content as literal text, never as markup', () => {
    const { container } = render({
      ...ENTRY,
      result: {
        status: 'RED',
        title: HOSTILE[0],
        kpis: [{ label: HOSTILE[1]!, value: HOSTILE[2]! }],
        // Ends at 6 (exclusive), not open-ended: HOSTILE[6] is used separately as the summary below,
        // and each HOSTILE string must render in exactly one place for the getByText loop to find it.
        items: HOSTILE.slice(3, 6),
        summary: HOSTILE[6],
      },
    });

    for (const text of HOSTILE) expect(screen.getByText(text)).toBeInTheDocument();
    // The widget's own lucide icons are <svg>, so svg is not on this list — everything that could
    // load or navigate is (spec E3, guard test 2).
    for (const tag of ['script', 'img', 'iframe', 'a', 'object', 'embed', 'link', 'form']) {
      expect(container.querySelector(tag)).toBeNull();
    }
    // Nothing became an attribute either.
    expect(container.querySelector('[href], [src], [srcset]')).toBeNull();
    expect(container.innerHTML).not.toContain('<b>');
  });

  it.each([
    ['MISSING', 'The last run did not report a dashboard result.'],
    ['INVALID', 'The last run’s dashboard result did not match the contract.'],
  ] as const)('shows the neutral state for %s and offers the run', (resultState, message) => {
    render({ ...ENTRY, resultState, result: null });

    expect(screen.getByText('No valid dashboard result')).toBeInTheDocument();
    expect(screen.getByText(message)).toBeInTheDocument();
    // Never a substitute green.
    expect(screen.queryByText('GREEN')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Open run' }));
    expect(navigate).toHaveBeenCalledWith('/workflows/wf-a1/run/run-1');
  });

  it.each([
    ['without a configured workflow', {} as Record<string, string>, null, 'No workflow selected.'],
    ['for a deleted workflow', { workflowId: 'wf-a1' }, { ...ENTRY, available: false, workflowName: null, run: null, result: null, resultState: 'MISSING' as const }, 'This workflow no longer exists.'],
    ['without a run', { workflowId: 'wf-a1' }, { ...ENTRY, run: null, result: null, resultState: 'MISSING' as const }, 'No run yet.'],
  ])('shows its own state %s', (_name, config, entry, message) => {
    render(entry as WorkflowWidgetEntry | null, {}, config);

    expect(screen.getByText(message)).toBeInTheDocument();
  });

  it('shows a skeleton while loading and a message when loading failed', () => {
    const { unmount } = render(null, { isLoading: true });
    expect(screen.getByTestId('widget-skeleton')).toBeInTheDocument();
    unmount();

    render(null, { error: new Error('offline') });
    expect(screen.getByText('Could not load the workflow data.')).toBeInTheDocument();
  });

  it('falls back to the workflow name when the contract has no title', () => {
    render({ ...ENTRY, result: { status: 'GREEN', kpis: [], items: [] } });

    expect(screen.getByText('Nightly QA')).toBeInTheDocument();
  });
});
