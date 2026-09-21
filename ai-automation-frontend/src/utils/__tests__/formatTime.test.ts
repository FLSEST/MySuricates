import { describe, it, expect } from 'vitest';
import { formatTimeUntil } from '../formatTime';

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
