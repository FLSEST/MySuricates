import { describe, it, expect, vi } from 'vitest';
import { render, renderHook, screen } from '@testing-library/react';
import { useScheduleColumns } from '../scheduleColumns';
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

/** "Next run" never shows a date inside a skip-until pause (a776fd2a). */
describe('schedule list — next run column', () => {
  it('shows the run after the pause and says until when the schedule is paused', () => {
    nextRunCell({ ...base, pausedUntil: '2026-10-01T22:00:00Z' });

    const cell = screen.getByTestId('cell');
    expect(cell).toHaveTextContent(new Date('2026-10-02T06:00:00Z').toLocaleString());
    expect(screen.getByTestId('next-run-paused'))
      .toHaveTextContent(`paused until ${new Date('2026-10-01T22:00:00Z').toLocaleString()}`);
  });

  it('shows no pause note for a schedule the pause does not hold', () => {
    nextRunCell({ ...base, pausedUntil: null });

    expect(screen.getByTestId('cell')).toHaveTextContent(new Date('2026-10-02T06:00:00Z').toLocaleString());
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
    expect(cell).not.toHaveTextContent(new Date('2026-09-30T09:00:00Z').toLocaleString());
    expect(screen.getByTestId('next-run-paused')).toBeInTheDocument();
  });
});
