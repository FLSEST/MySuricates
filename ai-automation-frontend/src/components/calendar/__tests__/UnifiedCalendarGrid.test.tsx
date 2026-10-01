import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import type { UnifiedCalendarEvent } from '../../../types';
import { UnifiedCalendarGrid } from '../UnifiedCalendarGrid';

const requested: Array<{ from: string; to: string }> = [];
let limits: Array<{ sourceId: string; label: string; shown: number; total: number }> = [];
const events: UnifiedCalendarEvent[] = [{
  id: 'e1', title: 'Weekly backup', start: new Date(2026, 8, 22, 9, 0), end: new Date(2026, 8, 22, 9, 30),
  allDay: false, source: 'schedules', sourceLabel: 'Schedules', color: 'bg-info',
}];

// The adapters fetch from several APIs; the grid only needs what the hook returns.
vi.mock('../../../hooks/useCalendarAggregated', () => ({
  useCalendarAggregated: (from: string, to: string) => {
    requested.push({ from, to });
    return { events, sources: [], limits, isLoading: false, setSourceColor: vi.fn() };
  },
}));

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 8, 24, 10, 30));
  useI18nStore.setState({ locale: 'en' });
  requested.length = 0;
  limits = [];
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
    expect(screen.getByText('September 22, 2026')).toBeInTheDocument();
    expect(screen.getByText('Weekly backup')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Week'));
    // ICU puts thin spaces around the dash; toHaveTextContent folds them into plain ones
    expect(heading()).toHaveTextContent('September 21 – 27, 2026');
    expect(lastRange()).toEqual({
      from: new Date(2026, 8, 21).toISOString(),
      to: new Date(2026, 8, 27, 23, 59, 59).toISOString(),
    });
  });

  // 6bb7ed36: the week and the selected day read „21. – 27. September“ in the English UI too
  it('writes the header and the selected day the way the UI language writes dates', () => {
    useI18nStore.setState({ locale: 'de' });
    render(<UnifiedCalendarGrid />);
    expect(heading()).toHaveTextContent('September 2026');
    fireEvent.click(dayCell(22));
    expect(screen.getByText('22. September 2026')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Woche'));
    expect(heading()).toHaveTextContent('21.–27. September 2026');
    fireEvent.click(navButtons().next);
    expect(heading()).toHaveTextContent('28. September – 4. Oktober 2026');

    act(() => useI18nStore.setState({ locale: 'en' }));
    expect(heading()).toHaveTextContent('September 28 – October 4, 2026');
  });

  // fbd56d63: the runs source is cut at 1000 per range; the calendar names the cut like the schedule view
  it('shows no cut notice while every source delivered all its events', () => {
    render(<UnifiedCalendarGrid />);
    expect(screen.queryByTestId('calendar-source-limited')).toBeNull();
  });

  it.each([
    ['en', 'The calendar shows the newest 1,000 of 1,500 runs in this range – earlier days are missing some.'],
    ['de', 'Der Kalender zeigt die neuesten 1.000 von 1.500 Ausführungen in diesem Zeitraum – an früheren Tagen fehlen welche.'],
  ] as const)('names the cut runs in the UI language (%s)', (locale, text) => {
    useI18nStore.setState({ locale });
    limits = [{ sourceId: 'runs', label: 'Runs', shown: 1000, total: 1500 }];
    render(<UnifiedCalendarGrid />);
    expect(screen.getByTestId('calendar-source-limited')).toHaveTextContent(text);
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
