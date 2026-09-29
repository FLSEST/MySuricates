import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, renderHook, screen } from '@testing-library/react';
import { useScheduleColumns } from '../scheduleColumns';
import { useI18nStore } from '../../../i18n';
import { formatTimestamp } from '../../../utils/localeFormat';
import type { ScheduledWorkflow } from '../../../types';

const base: ScheduledWorkflow = {
  id: 'sc1', workflowId: 'w1', workflowName: 'Nightly', projectName: null, chainId: null, chainName: null,
  targetType: 'WORKFLOW', cronExpression: '0 0 8 * * *', scheduleType: 'CRON', scheduledAt: null,
  delayExpression: null, description: '', inputContext: {}, enabled: true,
  nextRunAt: '2026-10-02T06:00:00Z', lastRunAt: null,
  createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
};

function nextRunCell(s: ScheduledWorkflow) {
  const { result } = renderHook(() => useScheduleColumns({ onEdit: vi.fn(), onDelete: vi.fn(), onToggle: vi.fn() }));
  const column = result.current.find((c) => c.key === 'nextRun')!;
  return render(<div data-testid="cell">{column.render(s)}</div>);
}

beforeEach(() => useI18nStore.setState({ locale: 'en' }));

/** "Next run" never shows a date inside a skip-until pause (a776fd2a). */
describe('schedule list — next run column', () => {
  it('shows the run after the pause and says until when the schedule is paused', () => {
    nextRunCell({ ...base, pausedUntil: '2026-10-01T22:00:00Z' });

    const cell = screen.getByTestId('cell');
    expect(cell).toHaveTextContent(formatTimestamp('2026-10-02T06:00:00Z', 'en'));
    expect(screen.getByTestId('next-run-paused'))
      .toHaveTextContent(`paused until ${formatTimestamp('2026-10-01T22:00:00Z', 'en')}`);
  });

  it('shows no pause note for a schedule the pause does not hold', () => {
    nextRunCell({ ...base, pausedUntil: null });

    expect(screen.getByTestId('cell')).toHaveTextContent(formatTimestamp('2026-10-02T06:00:00Z', 'en'));
    expect(screen.queryByTestId('next-run-paused')).not.toBeInTheDocument();
    expect(screen.queryByTestId('next-run-maintenance')).not.toBeInTheDocument();
  });

  /** "Next run" says that nothing starts while the maintenance switch holds the schedule (7704b1e9). */
  it('shows that nothing starts in maintenance mode instead of a date', () => {
    nextRunCell({ ...base, nextRunAt: null, pausedUntil: null, maintenance: true });

    expect(screen.getByTestId('next-run-maintenance')).toHaveTextContent('Maintenance mode – nothing starts');
    // No placeholder dash and no date next to it — the note is the whole cell.
    expect(screen.getByTestId('cell')).toHaveTextContent(/^Maintenance mode – nothing starts$/);
  });

  it('shows no date for a one-off the pause lets expire', () => {
    nextRunCell({
      ...base, scheduleType: 'ONCE', cronExpression: '', scheduledAt: '2026-09-30T09:00:00Z',
      nextRunAt: null, pausedUntil: '2026-10-01T22:00:00Z',
    });

    const cell = screen.getByTestId('cell');
    expect(cell).not.toHaveTextContent(formatTimestamp('2026-09-30T09:00:00Z', 'en'));
    expect(screen.getByTestId('next-run-paused')).toBeInTheDocument();
  });

  // Dates follow the app language, not the browser's (e86f6544).
  it('writes the dates in the app language', () => {
    useI18nStore.setState({ locale: 'de' });
    nextRunCell({ ...base, pausedUntil: '2026-10-01T22:00:00Z' });

    expect(screen.getByTestId('cell')).toHaveTextContent(formatTimestamp('2026-10-02T06:00:00Z', 'de'));
    expect(screen.getByTestId('next-run-paused')).toHaveTextContent(formatTimestamp('2026-10-01T22:00:00Z', 'de'));
    expect(formatTimestamp('2026-10-02T06:00:00Z', 'de')).toMatch(/^2\.10\.2026, \d{2}:00:00$/);
  });
});

/** Schedule type badge: recurring stays neutral, one-off and delayed carry theme tokens (19ebb8a5). */
describe('schedule list — type column', () => {
  function typeBadge(s: ScheduledWorkflow) {
    const { result } = renderHook(() => useScheduleColumns({ onEdit: vi.fn(), onDelete: vi.fn(), onToggle: vi.fn() }));
    const column = result.current.find((c) => c.key === 'type')!;
    render(<div data-testid="cell">{column.render(s)}</div>);
    return screen.getByTestId('cell').firstElementChild!;
  }

  it.each([
    ['CRON', ['bg-surface-hover', 'text-text-secondary']],
    ['ONCE', ['bg-info-subtle', 'text-info']],
    ['DELAYED', ['bg-accent-subtle', 'text-accent']],
  ] as const)('colours %s with theme tokens', (scheduleType, classes) => {
    expect(typeBadge({ ...base, scheduleType })).toHaveClass(...classes);
  });
});
