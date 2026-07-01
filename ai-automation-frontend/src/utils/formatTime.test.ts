import { describe, it, expect } from 'vitest';
import { formatDuration, formatDateTime } from './formatTime';

describe('formatDuration', () => {
  it('returns dash for null', () => {
    expect(formatDuration(null)).toBe('-');
  });

  it('returns dash for 0', () => {
    expect(formatDuration(0)).toBe('-');
  });

  it('formats milliseconds', () => {
    expect(formatDuration(500)).toBe('500ms');
  });

  it('formats seconds', () => {
    expect(formatDuration(3500)).toBe('3.5s');
  });

  it('formats minutes', () => {
    expect(formatDuration(150_000)).toBe('2.5m');
  });
});

describe('formatDateTime', () => {
  it('formats German locale as DD.MM. HH:mm', () => {
    const result = formatDateTime('2026-02-27T14:05:00Z', 'de');
    // Timezone-dependent, just check format pattern
    expect(result).toMatch(/^\d{2}\.\d{2}\. \d{2}:\d{2}$/);
  });

  it('formats English locale (default) as MM/DD HH:mm', () => {
    const result = formatDateTime('2026-02-27T14:05:00Z', 'en');
    expect(result).toMatch(/^\d{2}\/\d{2} \d{2}:\d{2}$/);
  });

  it('defaults to English format when no locale is passed', () => {
    const result = formatDateTime('2026-02-27T14:05:00Z');
    expect(result).toMatch(/^\d{2}\/\d{2} \d{2}:\d{2}$/);
  });
});
