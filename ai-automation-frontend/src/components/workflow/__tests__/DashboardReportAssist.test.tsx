import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DashboardReportAssist } from '../DashboardReportAssist';
import { useDashboardReportAssist, useDashboardReportAssistAvailable } from '../../../hooks/useDashboardReportAssist';
import { useI18nStore } from '../../../i18n';

vi.mock('../../../hooks/useDashboardReportAssist', () => ({
  useDashboardReportAssist: vi.fn(),
  useDashboardReportAssistAvailable: vi.fn(),
}));

const PROPOSAL = {
  runId: 'run-1',
  runCompletedAt: '2026-09-18T08:02:00Z',
  provider: 'CLAUDE',
  proposal: {
    title: 'Nightly QA',
    status: 'YELLOW' as const,
    kpis: [{ label: 'Hunts', value: '1361', trend: 'UP' as const }],
    items: ['<script>alert(1)</script>'],
    summary: 'Ein Befund.',
  },
};

function mockAssist(data: typeof PROPOSAL | undefined, mutate = vi.fn()) {
  vi.mocked(useDashboardReportAssist).mockReturnValue({
    mutate, data, isPending: false, isError: false, reset: vi.fn(),
  } as never);
  return mutate;
}

describe('DashboardReportAssist', () => {
  beforeEach(() => {
    useI18nStore.setState({ locale: 'en' });
    vi.mocked(useDashboardReportAssistAvailable).mockReturnValue(true);
    mockAssist(undefined);
  });

  it('renders nothing when the assist is not available', () => {
    vi.mocked(useDashboardReportAssistAvailable).mockReturnValue(false);

    render(<DashboardReportAssist workflowId="wf-a1" onApply={() => {}} />);

    expect(screen.queryByTestId('dashboard-report-assist')).not.toBeInTheDocument();
  });

  it('asks for a proposal for this workflow when the button is clicked', () => {
    const mutate = mockAssist(undefined);

    render(<DashboardReportAssist workflowId="wf-a1" onApply={() => {}} />);
    fireEvent.click(screen.getByTestId('dashboard-report-assist-run'));

    expect(mutate).toHaveBeenCalledWith('wf-a1');
  });

  /** The point of the whole feature: nothing is written before a human says so. */
  it('shows the proposal and writes nothing until "apply" is clicked', () => {
    const onApply = vi.fn();
    mockAssist(PROPOSAL);

    render(<DashboardReportAssist workflowId="wf-a1" onApply={onApply} />);

    const panel = screen.getByTestId('dashboard-report-assist-proposal');
    expect(panel).toHaveTextContent('Nightly QA');
    expect(panel).toHaveTextContent('YELLOW');
    expect(panel).toHaveTextContent('1361');
    expect(onApply).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('dashboard-report-assist-apply'));

    expect(onApply).toHaveBeenCalledTimes(1);
    expect(onApply.mock.calls[0][0]).toEqual({
      status: 'YELLOW',
      title: 'Nightly QA',
      summary: 'Ein Befund.',
      kpis: [{ label: 'Hunts', value: '1361', trend: 'UP' }],
      items: ['<script>alert(1)</script>'],
    });
  });

  /** Markup in a proposal is ordinary text — shown, never interpreted. */
  it('renders a hostile item as text, not as markup', () => {
    mockAssist(PROPOSAL);

    render(<DashboardReportAssist workflowId="wf-a1" onApply={() => {}} />);

    const panel = screen.getByTestId('dashboard-report-assist-proposal');
    expect(panel).toHaveTextContent('<script>alert(1)</script>');
    expect(panel.querySelector('script')).toBeNull();
    expect(panel.querySelector('img')).toBeNull();
    expect(panel.querySelector('a')).toBeNull();
  });

  it('drops the proposal on "discard" without writing anything', () => {
    const onApply = vi.fn();
    const reset = vi.fn();
    vi.mocked(useDashboardReportAssist).mockReturnValue({
      mutate: vi.fn(), data: PROPOSAL, isPending: false, isError: false, reset,
    } as never);

    render(<DashboardReportAssist workflowId="wf-a1" onApply={onApply} />);
    fireEvent.click(screen.getByTestId('dashboard-report-assist-discard'));

    expect(reset).toHaveBeenCalledTimes(1);
    expect(onApply).not.toHaveBeenCalled();
  });
});
