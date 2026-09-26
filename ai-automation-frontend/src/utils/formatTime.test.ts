import { describe, it, expect } from 'vitest';
import { formatDuration, formatDateTime } from './formatTime';

describe('formatDuration', () => {
  it('returns dash for null', () => {
    expect(formatDuration(null, 'en')).toBe('-');
  });

  it('returns dash for 0', () => {
    expect(formatDuration(0, 'en')).toBe('-');
  });

  it('formats milliseconds', () => {
    expect(formatDuration(500, 'en')).toBe('500ms');
  });

  it('formats seconds', () => {
    expect(formatDuration(3500, 'en')).toBe('3.5s');
  });

  it('formats minutes', () => {
    expect(formatDuration(150_000, 'en')).toBe('2.5m');
  });

  // 6bb7ed36: the German UI read "3.5s" with a decimal point
  it('writes the decimal separator of the app language', () => {
    expect(formatDuration(3500, 'de')).toBe('3,5s');
    expect(formatDuration(150_000, 'de')).toBe('2,5m');
    expect(formatDuration(500, 'de')).toBe('500ms');
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

  // The locale is required since e86f6544: the English default hid forgotten call sites, which then
  // showed English in the German UI. Both formats hold whatever language the machine speaks.
  it('keeps the 24-hour clock in English as well', () => {
    expect(formatDateTime(new Date(2026, 1, 27, 14, 5).toISOString(), 'en')).toBe('02/27 14:05');
    expect(formatDateTime(new Date(2026, 1, 27, 14, 5).toISOString(), 'de')).toBe('27.02. 14:05');
  });
});
