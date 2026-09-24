import { describe, it, expect } from 'vitest';
import { formatRelativeShort, formatRelativeTime, formatTimeUntil } from '../formatTime';

const inMs = (ms: number) => new Date(Date.now() + ms).toISOString();

describe('formatTimeUntil', () => {
  it('reads a future point relative to now', () => {
    expect(formatTimeUntil(inMs(30 * 60_000 + 500), 'en')).toBe('in 30 minutes');
    expect(formatTimeUntil(inMs(3 * 3_600_000 + 500), 'en')).toBe('in 3 hours');
    expect(formatTimeUntil(inMs(3 * 86_400_000 + 500), 'en')).toBe('in 3 days');
    expect(formatTimeUntil(inMs(3 * 3_600_000 + 500), 'de')).toBe('in 3 Stunden');
  });

  it('reads a past point like formatRelativeTime', () => {
    expect(formatTimeUntil(inMs(-5 * 60_000), 'en')).toBe('5 minutes ago');
  });
});

describe('formatRelativeTime', () => {
  it('follows the app language', () => {
    expect(formatRelativeTime(inMs(-5 * 60_000), 'en')).toBe('5 minutes ago');
    expect(formatRelativeTime(inMs(-5 * 60_000), 'de')).toBe('vor 5 Minuten');
  });
});

describe('formatRelativeShort', () => {
  it('keeps the compact English form of the todo rows', () => {
    expect(formatRelativeShort(inMs(-5 * 60_000 - 500), 'en')).toBe('5m ago');
    expect(formatRelativeShort(inMs(-3 * 3_600_000 - 500), 'en')).toBe('3h ago');
    expect(formatRelativeShort(inMs(-2 * 86_400_000 - 500), 'en')).toBe('2d ago');
    expect(formatRelativeShort(inMs(-10_000), 'en')).toBe('now');
  });

  // Before e86f6544 every row read English ("5m ago") and a future due date read "just now".
  it('speaks German in the German UI and reads future dates forward', () => {
    expect(formatRelativeShort(inMs(-3 * 3_600_000 - 500), 'de')).toBe('vor 3 Std.');
    expect(formatRelativeShort(inMs(-10_000), 'de')).toBe('jetzt');
    expect(formatRelativeShort(inMs(3 * 86_400_000 + 500), 'en')).toBe('in 3d');
  });

  it('shows the date a month or more away', () => {
    const old = new Date(2026, 0, 5, 12).toISOString();
    expect(formatRelativeShort(old, 'en')).toBe('1/5/2026');
    expect(formatRelativeShort(old, 'de')).toBe('5.1.2026');
  });
});
