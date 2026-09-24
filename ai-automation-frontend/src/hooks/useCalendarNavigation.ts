import { useMemo, useState } from 'react';

export type CalendarViewMode = 'month' | 'week' | 'day';

/** Monday 00:00 of the week that contains `date` — weeks start on Monday. */
export function getMonday(date: Date): Date {
  const d = new Date(date);
  const day = d.getDay();
  const diff = day === 0 ? -6 : 1 - day;
  d.setDate(d.getDate() + diff);
  d.setHours(0, 0, 0, 0);
  return d;
}

function addDays(date: Date, days: number): Date {
  const d = new Date(date);
  d.setDate(d.getDate() + days);
  return d;
}

/**
 * What the calendar shows: the view mode, the month and its selected day (month view), the date of
 * the week or day view, the range to load for it (`from`/`to`, ISO), stepping back and forth and
 * the way back to today. Week and day open on the selected day, without one on the 1st of the month.
 */
export function useCalendarNavigation() {
  const now = new Date();
  const [viewMode, setViewMode] = useState<CalendarViewMode>('month');
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth());
  const [selectedDay, setSelectedDay] = useState<number | null>(now.getDate());
  const [currentDate, setCurrentDate] = useState(now);

  const weekStart = useMemo(() => getMonday(currentDate), [currentDate]);

  const { from, to } = useMemo(() => {
    if (viewMode === 'month') {
      return {
        from: new Date(year, month, 1).toISOString(),
        to: new Date(year, month + 1, 0, 23, 59, 59).toISOString(),
      };
    }
    if (viewMode === 'week') {
      const sunday = addDays(weekStart, 6);
      sunday.setHours(23, 59, 59);
      return { from: weekStart.toISOString(), to: sunday.toISOString() };
    }
    const dayStart = new Date(currentDate.getFullYear(), currentDate.getMonth(), currentDate.getDate());
    const dayEnd = new Date(currentDate.getFullYear(), currentDate.getMonth(), currentDate.getDate(), 23, 59, 59);
    return { from: dayStart.toISOString(), to: dayEnd.toISOString() };
  }, [viewMode, year, month, currentDate, weekStart]);

  /** One month, week or day back (-1) or forward (1); a month step clears the selected day. */
  const step = (direction: 1 | -1) => {
    if (viewMode === 'month') {
      const target = new Date(year, month + direction, 1);
      setYear(target.getFullYear());
      setMonth(target.getMonth());
      setSelectedDay(null);
    } else {
      setCurrentDate(addDays(currentDate, (viewMode === 'week' ? 7 : 1) * direction));
    }
  };

  const goToday = () => {
    const n = new Date();
    setYear(n.getFullYear());
    setMonth(n.getMonth());
    setSelectedDay(n.getDate());
    setCurrentDate(n);
  };

  const switchView = (mode: CalendarViewMode) => {
    setViewMode(mode);
    if (mode === 'week' || mode === 'day') {
      setCurrentDate(new Date(year, month, selectedDay ?? 1));
    }
  };

  /** Clicking the selected day again deselects it. */
  const toggleDay = (day: number) => setSelectedDay(day === selectedDay ? null : day);

  const openDay = (date: Date) => {
    setCurrentDate(date);
    setViewMode('day');
  };

  const isNotToday = viewMode === 'month'
    ? (year !== now.getFullYear() || month !== now.getMonth())
    : currentDate.toDateString() !== now.toDateString();

  return {
    viewMode, year, month, selectedDay, currentDate, weekStart, from, to, isNotToday,
    goPrev: () => step(-1),
    goNext: () => step(1),
    goToday, switchView, toggleDay, openDay,
  };
}
