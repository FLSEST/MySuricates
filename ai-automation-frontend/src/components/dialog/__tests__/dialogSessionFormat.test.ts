import { describe, it, expect } from 'vitest';
import { formatDuration, formatSessionStart, projectShortName } from '../dialogSessionFormat';

describe('formatDuration', () => {
  it('says "?" when a point in time is missing', () => {
    expect(formatDuration(null, '2025-06-01T10:00:00Z')).toBe('?');
    expect(formatDuration('2025-06-01T10:00:00Z', null)).toBe('?');
  });

  it('rounds down to minutes, below one minute "<1 min", from an hour on hours and minutes', () => {
    expect(formatDuration('2025-06-01T10:00:00Z', '2025-06-01T10:00:59Z')).toBe('<1 min');
    expect(formatDuration('2025-06-01T10:00:00Z', '2025-06-01T10:59:59Z')).toBe('59 min');
    expect(formatDuration('2025-06-01T10:00:00Z', '2025-06-01T11:05:00Z')).toBe('1h 5min');
  });
});

describe('formatSessionStart', () => {
  it('says "?" without a start and otherwise shows day and time in the app language', () => {
    expect(formatSessionStart(null, 'en')).toBe('?');
    const shown = formatSessionStart('2025-06-01T10:00:00Z', 'en');
    expect(shown).toContain('Jun');
    expect(shown).toMatch(/\d{2}:\d{2}/);
  });
});

describe('projectShortName', () => {
  it('takes the last path segment, falls back to the path, "?" without one', () => {
    expect(projectShortName('/home/me/projects/shop')).toBe('shop');
    expect(projectShortName('/home/me/shop/')).toBe('/home/me/shop/');
    expect(projectShortName('')).toBe('?');
  });
});
