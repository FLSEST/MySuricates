import { afterEach, describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { COLOR_PALETTE, useCalendarColors } from '../useCalendarColors';

const STORAGE_KEY = 'calendar-source-colors';

afterEach(() => localStorage.clear());

// 19ebb8a5: the palette consists of theme tokens; choices stored before keep their hue
describe('useCalendarColors', () => {
  it('offers chart tokens only', () => {
    expect(COLOR_PALETTE.every((c) => /^bg-chart-[1-8]$/.test(c))).toBe(true);
  });

  it('maps a colour stored with the old palette onto the chart token of the same hue', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      'google-calendar': 'bg-blue-500',
      runs: 'bg-green-500',
      trello: 'bg-chart-5',
    }));

    const { result } = renderHook(() => useCalendarColors());

    expect(result.current.colors).toEqual({ 'google-calendar': 'bg-chart-1', runs: 'bg-chart-2', trello: 'bg-chart-5' });
    expect(result.current.getColor('google-calendar', 'bg-chart-3')).toBe('bg-chart-1');
    expect(result.current.getColor('schedules', 'bg-chart-3')).toBe('bg-chart-3');
  });

  it('stores a new choice', () => {
    const { result } = renderHook(() => useCalendarColors());

    act(() => result.current.setColor('runs', 'bg-chart-4'));

    expect(result.current.getColor('runs', 'bg-success')).toBe('bg-chart-4');
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toEqual({ runs: 'bg-chart-4' });
  });
});
