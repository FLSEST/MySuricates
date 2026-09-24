import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { getMonday, useCalendarNavigation } from '../useCalendarNavigation';

// Thursday, 24 September 2026; the week runs from Monday the 21st to Sunday the 27th.
// Expected values are built with local-time constructors, so the test holds in any time zone.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 8, 24, 10, 30));
});
afterEach(() => vi.useRealTimers());

const iso = (...args: [number, number, number, number?, number?, number?]) =>
  new Date(args[0], args[1], args[2], args[3] ?? 0, args[4] ?? 0, args[5] ?? 0).toISOString();

describe('getMonday', () => {
  it('returns Monday 00:00 of the week, also for a Sunday', () => {
    expect(getMonday(new Date(2026, 8, 24, 15, 0))).toEqual(new Date(2026, 8, 21));
    expect(getMonday(new Date(2026, 8, 27, 23, 0))).toEqual(new Date(2026, 8, 21));
    expect(getMonday(new Date(2026, 8, 21))).toEqual(new Date(2026, 8, 21));
  });
});

describe('useCalendarNavigation', () => {
  it('starts on the current month with today selected and loads the whole month', () => {
    const { result } = renderHook(() => useCalendarNavigation());
    expect(result.current).toMatchObject({ viewMode: 'month', year: 2026, month: 8, selectedDay: 24, isNotToday: false });
    expect(result.current.from).toBe(iso(2026, 8, 1));
    expect(result.current.to).toBe(iso(2026, 8, 30, 23, 59, 59));
  });

  it('steps through months across the year boundary and returns to today', () => {
    const { result } = renderHook(() => useCalendarNavigation());
    for (let i = 0; i < 4; i++) act(() => result.current.goNext());
    expect(result.current).toMatchObject({ year: 2027, month: 0, selectedDay: null, isNotToday: true });
    expect(result.current.from).toBe(iso(2027, 0, 1));
    expect(result.current.to).toBe(iso(2027, 0, 31, 23, 59, 59));

    act(() => result.current.goPrev());
    expect(result.current).toMatchObject({ year: 2026, month: 11 });

    act(() => result.current.goToday());
    expect(result.current).toMatchObject({ year: 2026, month: 8, selectedDay: 24, isNotToday: false });
  });

  it('opens the week of the selected day and steps by seven days', () => {
    const { result } = renderHook(() => useCalendarNavigation());
    act(() => result.current.toggleDay(22));
    act(() => result.current.switchView('week'));
    expect(result.current.viewMode).toBe('week');
    expect(result.current.currentDate).toEqual(new Date(2026, 8, 22));
    expect(result.current.weekStart).toEqual(new Date(2026, 8, 21));
    expect(result.current.from).toBe(iso(2026, 8, 21));
    expect(result.current.to).toBe(iso(2026, 8, 27, 23, 59, 59));
    // Week and day compare the date itself with today, not the week
    expect(result.current.isNotToday).toBe(true);

    act(() => result.current.goNext());
    expect(result.current.currentDate).toEqual(new Date(2026, 8, 29));
    expect(result.current.from).toBe(iso(2026, 8, 28));

    act(() => result.current.goToday());
    expect(result.current.isNotToday).toBe(false);
    expect(result.current.from).toBe(iso(2026, 8, 21));
  });

  it('opens the day view on the 1st without a selection and steps by one day', () => {
    const { result } = renderHook(() => useCalendarNavigation());
    act(() => result.current.toggleDay(24));
    expect(result.current.selectedDay).toBeNull();
    act(() => result.current.switchView('day'));
    expect(result.current.currentDate).toEqual(new Date(2026, 8, 1));
    expect(result.current.from).toBe(iso(2026, 8, 1));
    expect(result.current.to).toBe(iso(2026, 8, 1, 23, 59, 59));

    act(() => result.current.goPrev());
    expect(result.current.currentDate).toEqual(new Date(2026, 7, 31));
    expect(result.current.from).toBe(iso(2026, 7, 31));
  });

  it('opens a clicked day in the day view and keeps the month view on switching back', () => {
    const { result } = renderHook(() => useCalendarNavigation());
    act(() => result.current.openDay(new Date(2026, 8, 3)));
    expect(result.current).toMatchObject({ viewMode: 'day', currentDate: new Date(2026, 8, 3) });

    act(() => result.current.switchView('month'));
    expect(result.current).toMatchObject({ viewMode: 'month', year: 2026, month: 8, selectedDay: 24 });
    expect(result.current.from).toBe(iso(2026, 8, 1));
  });
});
