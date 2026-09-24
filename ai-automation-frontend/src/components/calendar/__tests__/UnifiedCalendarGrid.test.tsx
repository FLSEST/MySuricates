import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import type { UnifiedCalendarEvent } from '../../../types';
import { UnifiedCalendarGrid } from '../UnifiedCalendarGrid';

const requested: Array<{ from: string; to: string }> = [];
const events: UnifiedCalendarEvent[] = [{
  id: 'e1', title: 'Weekly backup', start: new Date(2026, 8, 22, 9, 0), end: new Date(2026, 8, 22, 9, 30),
  allDay: false, source: 'schedules', sourceLabel: 'Schedules', color: 'bg-info',
}];

// The adapters fetch from several APIs; the grid only needs what the hook returns.
vi.mock('../../../hooks/useCalendarAggregated', () => ({
  useCalendarAggregated: (from: string, to: string) => {
    requested.push({ from, to });
    return { events, sources: [], isLoading: false, setSourceColor: vi.fn() };
  },
}));

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 8, 24, 10, 30));
  useI18nStore.setState({ locale: 'en' });
  requested.length = 0;
});
afterEach(() => vi.useRealTimers());

// Plain text and selector queries: role queries compute the name of each of the ~50 buttons
// and made this file slow enough to time out under the load of the full suite.
const heading = () => document.querySelector('h2')!;
const lastRange = () => requested[requested.length - 1];
const todayButton = () => screen.queryByText(en.calendar.today);
const dayCell = (day: number) => screen.getByText(String(day)).closest('button')!;
// Back and forward are the icon buttons left and right of the title row
function navButtons() {
  const [prev, next] = heading().parentElement!.parentElement!.querySelectorAll(':scope > button');
  return { prev, next };
}

describe('UnifiedCalendarGrid', { timeout: 15_000 }, () => {
  it('loads the current month and steps to the next and back to today', () => {
    render(<UnifiedCalendarGrid />);
    expect(heading()).toHaveTextContent('September 2026');
    expect(lastRange().from).toBe(new Date(2026, 8, 1).toISOString());
    expect(todayButton()).toBeNull();

    fireEvent.click(navButtons().next);
    expect(heading()).toHaveTextContent('October 2026');
    expect(lastRange().from).toBe(new Date(2026, 9, 1).toISOString());
    fireEvent.click(navButtons().prev);
    expect(heading()).toHaveTextContent('September 2026');
    expect(todayButton()).toBeNull();

    fireEvent.click(navButtons().prev);
    expect(heading()).toHaveTextContent('August 2026');
    fireEvent.click(todayButton()!);
    expect(heading()).toHaveTextContent('September 2026');
  });

  it('lists the events of a selected day and opens the week of that day', () => {
    render(<UnifiedCalendarGrid />);
    fireEvent.click(dayCell(22));
    expect(screen.getByText('22. September 2026')).toBeInTheDocument();
    expect(screen.getByText('Weekly backup')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Week'));
    expect(heading()).toHaveTextContent('21. – 27. September 2026');
    expect(lastRange()).toEqual({
      from: new Date(2026, 8, 21).toISOString(),
      to: new Date(2026, 8, 27, 23, 59, 59).toISOString(),
    });
  });

  it('opens a day on double click', () => {
    render(<UnifiedCalendarGrid />);
    fireEvent.doubleClick(dayCell(3));
    expect(lastRange()).toEqual({
      from: new Date(2026, 8, 3).toISOString(),
      to: new Date(2026, 8, 3, 23, 59, 59).toISOString(),
    });
    expect(screen.getByText('Day')).toHaveClass('bg-accent');
  });
});
